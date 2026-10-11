import assert from "node:assert/strict";
import { createHash, webcrypto } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { exportJWK, exportPKCS8, generateKeyPair, jwtVerify, SignJWT } from "jose";
import { bundleWorker } from "./load-worker.mjs";

globalThis.crypto ??= webcrypto;
const hash = (value) => createHash("sha256").update(value).digest("hex");

test("Google and Apple sign-in with signed provider tokens and real D1", { timeout: 60_000 }, async (t) => {
  const rsa = await generateKeyPair("RS256", { extractable: true });
  const appleKey = await generateKeyPair("ES256", { extractable: true });
  const jwk = { ...await exportJWK(rsa.publicKey), kid: "provider-key", alg: "RS256", use: "sig" };
  const codes = new Map();
  const bindings = {
    AUTH_BASE_URL: "https://fishing.test",
    GOOGLE_CLIENT_ID: "test-google-client", GOOGLE_CLIENT_SECRET: "test-google-secret",
    APPLE_CLIENT_ID: "nz.fishingnz.web", APPLE_TEAM_ID: "TESTTEAM01", APPLE_KEY_ID: "TESTKEY001",
    APPLE_PRIVATE_KEY: await exportPKCS8(appleKey.privateKey), APPLE_BUNDLE_ID: "nz.fishingnz.catchcheck",
  };
  let tokenExchanges = 0;
  const revoked = [];
  async function appleSecretFor(clientSecret) {
    const secret = await jwtVerify(clientSecret, appleKey.publicKey, {
      issuer: bindings.APPLE_TEAM_ID, audience: "https://appleid.apple.com", algorithms: ["ES256"],
    });
    assert.equal(secret.protectedHeader.kid, bindings.APPLE_KEY_ID);
    return secret.payload.sub;
  }
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "social-sign-in-tests" }, bindings,
    outboundService: async (request) => {
      const url = new URL(request.url);
      if (url.pathname === "/oauth2/v3/certs" || url.pathname === "/auth/keys") return Response.json({ keys: [jwk] });
      assert.ok(["oauth2.googleapis.com", "appleid.apple.com"].includes(url.hostname));
      const body = new URLSearchParams(await request.text());
      if (url.pathname === "/auth/revoke") {
        assert.equal(body.get("token_type_hint"), "refresh_token");
        assert.equal(await appleSecretFor(body.get("client_secret")), body.get("client_id"));
        revoked.push({ client_id: body.get("client_id"), token: body.get("token") });
        return new Response(null, { status: 200 });
      }
      tokenExchanges++;
      const entry = codes.get(body.get("code"));
      if (!entry) return Response.json({ error: "invalid_grant" }, { status: 400 });
      assert.equal(body.get("grant_type"), "authorization_code");
      if (entry.native) {
        assert.equal(body.has("redirect_uri"), false, "the native sheet's code has no redirect URI");
        assert.equal(await appleSecretFor(body.get("client_secret")), bindings.APPLE_BUNDLE_ID);
        assert.equal(body.get("client_id"), bindings.APPLE_BUNDLE_ID);
      } else {
        assert.equal(body.get("redirect_uri"), `https://fishing.test/v1/auth/oauth/${entry.provider}/callback`);
      }
      if (entry.provider === "google") {
        assert.equal(body.get("client_secret"), bindings.GOOGLE_CLIENT_SECRET);
        assert.equal(createHash("sha256").update(body.get("code_verifier")).digest("base64url"), entry.challenge);
      } else if (!entry.native) {
        assert.equal(await appleSecretFor(body.get("client_secret")), bindings.APPLE_CLIENT_ID);
      }
      return Response.json({ id_token: entry.token, ...(entry.provider === "apple" ? { refresh_token: `refresh-${body.get("code")}` } : {}) });
    },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      const sql = readFileSync(new URL(file, migrations), "utf8");
      for (const statement of sql.split(";").filter((value) => value.trim())) await db.prepare(statement).run();
    }
    async function call(path, body, token, method = body ? "POST" : "GET") {
      const response = await mf.dispatchFetch(`https://fishing.test${path}`, {
        method, redirect: "manual",
        headers: { ...(body ? { "content-type": "application/json" } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      });
      return { response, data: await response.json() };
    }
    async function start(provider = "google", { token, link = false, returnUri = "nz.fishingnz.app://auth/callback" } = {}) {
      const result = await call("/v1/auth/oauth/start", { provider, link, return_uri: returnUri }, token);
      assert.equal(result.response.status, 200);
      const url = new URL(result.data.authorization_url);
      assert.equal(url.searchParams.get("response_type"), "code");
      assert.equal(url.searchParams.get("redirect_uri"), `https://fishing.test/v1/auth/oauth/${provider}/callback`);
      assert.equal(url.searchParams.get("scope"), provider === "google" ? "openid email profile" : "name email");
      if (provider === "apple") assert.equal(url.searchParams.get("response_mode"), "form_post");
      return { provider, url, state: url.searchParams.get("state"), secret: result.data.exchange_secret };
    }
    async function callback(flow, overrides = {}, { error, user, signingKey = rsa.privateKey, expiry = "5m" } = {}) {
      const code = crypto.randomUUID();
      const claims = {
        sub: `${flow.provider}-person`, email: `${flow.provider}@example.com`, email_verified: true,
        nonce: flow.url.searchParams.get("nonce"), name: "Fishing Person", ...overrides,
      };
      const token = await new SignJWT(claims).setProtectedHeader({ alg: "RS256", kid: "provider-key" })
        .setIssuer(flow.provider === "google" ? "https://accounts.google.com" : "https://appleid.apple.com")
        .setAudience(flow.provider === "google" ? bindings.GOOGLE_CLIENT_ID : bindings.APPLE_CLIENT_ID)
        .setIssuedAt().setExpirationTime(expiry).sign(signingKey);
      codes.set(code, { token, provider: flow.provider, challenge: flow.url.searchParams.get("code_challenge") });
      const params = new URLSearchParams({ state: flow.state, ...(error ? { error } : { code }) });
      if (user) params.set("user", JSON.stringify(user));
      return mf.dispatchFetch(`https://fishing.test/v1/auth/oauth/${flow.provider}/callback${flow.provider === "google" ? `?${params}` : ""}`, {
        method: flow.provider === "google" ? "GET" : "POST", redirect: "manual",
        ...(flow.provider === "apple" ? { headers: { "content-type": "application/x-www-form-urlencoded" }, body: params.toString() } : {}),
      });
    }
    async function finish(flow, overrides, options) {
      const response = await callback(flow, overrides, options);
      assert.equal(response.status, 303);
      const location = new URL(response.headers.get("location"));
      const code = location.searchParams.get("oauth_code");
      assert.ok(code, `Callback failed: ${location}`);
      const result = await call("/v1/auth/oauth/exchange", { code, exchange_secret: flow.secret });
      assert.equal(result.response.status, 200);
      return { ...result.data, code };
    }
    let googleSession;
    await t.test("rejects unexpected return URLs and requires authentication for linking", async () => {
      assert.deepEqual((await call("/v1/auth/providers")).data, { google: true, apple: true, apple_native: true, connected: [] });
      for (const return_uri of ["https://evil.example/account", "https://cms.test/account", "http://localhost:3000/account", "nz.fishingnz.app://auth/callback?next=evil", "nz.fishingnz.app://evil/callback"]) {
        assert.equal((await call("/v1/auth/oauth/start", { provider: "google", return_uri })).response.status, 400);
      }
      assert.equal((await call("/v1/auth/oauth/start", { provider: "google", return_uri: "nz.fishingnz.app://auth/callback", link: true })).response.status, 401);
    });
    await t.test("creates a verified Google account and preserves it on subsequent sign-in", async () => {
      const flow = await start();
      googleSession = await finish(flow);
      assert.equal(googleSession.user.email_verified, true);
      assert.equal(googleSession.user.plan, "free");
      assert.equal(googleSession.user.has_password, false, "a social account has no password until it adds one");
      assert.equal(googleSession.permissions.features.fish_identity, true);
      assert.equal((await call("/v1/auth/oauth/exchange", { code: googleSession.code, exchange_secret: flow.secret })).response.status, 401);
      assert.equal((await call("/v1/auth/login", { email: googleSession.user.email, password: "whatever" })).response.status, 401);
      const returning = await finish(await start());
      assert.equal(returning.user.id, googleSession.user.id);
      assert.deepEqual((await call("/v1/auth/providers", undefined, returning.token)).data.connected, ["google"]);
    });
    await t.test("supports Apple form-post and private relay email", async () => {
      const session = await finish(await start("apple", { returnUri: "nz.fishingnz.catchcheck://auth/callback" }), {
        email: "relay@privaterelay.appleid.com", email_verified: "true", name: undefined,
      }, { user: { name: { firstName: "Apple", lastName: "Fisher" } } });
      assert.equal(session.user.email, "relay@privaterelay.appleid.com");
      assert.equal(session.user.display_name, "Apple Fisher");
      const returning = await finish(await start("apple"), { email: undefined, email_verified: undefined, name: undefined });
      assert.equal(returning.user.id, session.user.id);
      assert.equal(returning.user.display_name, "Apple Fisher");
    });
    await t.test("binds grants to the initiating client and consumes callbacks once", async () => {
      const flow = await start("google", { returnUri: "nz.fishingnz.app://auth/callback" });
      const response = await callback(flow);
      const url = new URL(response.headers.get("location"));
      const code = url.searchParams.get("oauth_code");
      assert.equal(url.protocol, "nz.fishingnz.app:");
      assert.equal(url.searchParams.has("token"), false);
      assert.equal((await call("/v1/auth/oauth/exchange", { code, exchange_secret: "f".repeat(64) })).response.status, 401);
      assert.equal((await call("/v1/auth/oauth/exchange", { code, exchange_secret: flow.secret })).response.status, 200);
      const count = tokenExchanges;
      assert.equal((await callback(flow)).status, 400);
      assert.equal(tokenExchanges, count);
    });
    await t.test("rejects wrong nonce, audience, issuer, expired tokens, and invalid signatures", async () => {
      const otherKey = await generateKeyPair("RS256");
      for (const [claims, options] of [
        [{ nonce: "wrong" }, {}], [{ aud: "another-client" }, {}], [{ iss: "https://evil.example" }, {}],
        [{ sub: "unverified-user", email_verified: false }, {}], [{}, { expiry: "-10m" }], [{}, { signingKey: otherKey.privateKey }],
      ]) {
        // These standard claims are overridden by the signing helper below, so alter the JWT explicitly for those cases.
        const flow = await start();
        if (claims.aud || claims.iss) {
          const code = crypto.randomUUID();
          const token = await new SignJWT({ sub: "subject", nonce: flow.url.searchParams.get("nonce") })
            .setProtectedHeader({ alg: "RS256", kid: "provider-key" }).setIssuedAt().setExpirationTime("5m")
            .setIssuer(claims.iss || "https://accounts.google.com").setAudience(claims.aud || bindings.GOOGLE_CLIENT_ID).sign(rsa.privateKey);
          codes.set(code, { token, provider: "google", challenge: flow.url.searchParams.get("code_challenge") });
          const response = await mf.dispatchFetch(`https://fishing.test/v1/auth/oauth/google/callback?${new URLSearchParams({ state: flow.state, code })}`, { redirect: "manual" });
          assert.equal(new URL(response.headers.get("location")).searchParams.get("oauth_error"), "failed");
        } else {
          const response = await callback(flow, claims, options);
          assert.equal(new URL(response.headers.get("location")).searchParams.get("oauth_error"), "failed");
        }
      }
    });
    await t.test("handles cancellation and expired flows and grants", async () => {
      const cancelled = await start("apple");
      const response = await callback(cancelled, {}, { error: "user_cancelled_authorize" });
      assert.equal(new URL(response.headers.get("location")).searchParams.get("oauth_error"), "cancelled");
      const expired = await start();
      await db.prepare("UPDATE account_oauth_flows SET expires_at = '2000-01-01' WHERE state_hash = ?").bind(hash(expired.state)).run();
      assert.equal((await callback(expired)).status, 400);
      const grantFlow = await start();
      const grantResponse = await callback(grantFlow);
      const code = new URL(grantResponse.headers.get("location")).searchParams.get("oauth_code");
      await db.prepare("UPDATE account_oauth_grants SET expires_at = '2000-01-01' WHERE code_hash = ?").bind(hash(code)).run();
      assert.equal((await call("/v1/auth/oauth/exchange", { code, exchange_secret: grantFlow.secret })).response.status, 401);
    });
    async function nativeApple(overrides = {}, { name, token, link = false, audience = bindings.APPLE_BUNDLE_ID } = {}) {
      const begun = await call("/v1/auth/apple/start", { link }, token);
      assert.equal(begun.response.status, 200);
      assert.match(begun.data.state, /^[a-f0-9]{64}$/);
      const claims = { sub: "native-apple-person", email: "native@privaterelay.appleid.com", email_verified: "true", nonce: begun.data.nonce, ...overrides };
      const sign = (aud) => new SignJWT(claims).setProtectedHeader({ alg: "RS256", kid: "provider-key" })
        .setIssuer("https://appleid.apple.com").setAudience(aud).setIssuedAt().setExpirationTime("5m").sign(rsa.privateKey);
      const code = crypto.randomUUID();
      codes.set(code, { token: await sign(bindings.APPLE_BUNDLE_ID), provider: "apple", native: true });
      const body = { state: begun.data.state, identity_token: await sign(audience), authorization_code: code, ...(name ? { name } : {}) };
      return { ...await call("/v1/auth/apple/native", body, token), body, code };
    }
    let nativeSession;
    await t.test("signs in with the iPhone's native Apple sheet and keeps the token for revocation", async () => {
      const first = await nativeApple({}, { name: { given: "Native", family: "Angler" } });
      assert.equal(first.response.status, 200, JSON.stringify(first.data));
      nativeSession = first.data;
      assert.equal(nativeSession.user.email, "native@privaterelay.appleid.com");
      assert.equal(nativeSession.user.display_name, "Native Angler");
      assert.equal(nativeSession.linked, false);
      assert.equal((await call("/v1/auth/apple/native", first.body)).response.status, 400, "a nonce is single use");
      const stored = await db.prepare("SELECT refresh_token, token_client_id FROM account_oauth_identities WHERE subject = 'native-apple-person'").first();
      assert.deepEqual({ ...stored }, { refresh_token: `refresh-${first.code}`, token_client_id: bindings.APPLE_BUNDLE_ID });
      const returning = await nativeApple({ email: undefined, email_verified: undefined });
      assert.equal(returning.data.user.id, nativeSession.user.id);
      assert.equal(returning.data.user.display_name, "Native Angler", "Apple sends the name only the first time");
      assert.deepEqual((await call("/v1/auth/providers", undefined, returning.data.token)).data.connected, ["apple"]);
    });
    await t.test("rejects native Apple tokens with the wrong nonce or audience, and linking without a session", async () => {
      assert.equal((await nativeApple({ nonce: "f".repeat(64) })).response.status, 400);
      assert.equal((await nativeApple({}, { audience: bindings.APPLE_CLIENT_ID })).response.status, 400);
      assert.equal((await call("/v1/auth/apple/start", { link: true })).response.status, 401);
      const invalid = await call("/v1/auth/apple/native", { state: "a".repeat(64), identity_token: "x", authorization_code: "y" });
      assert.equal(invalid.response.status, 400);
    });
    await t.test("revokes Apple's token when a native sign-in finds an existing email account", async () => {
      const before = revoked.length;
      const conflict = await nativeApple({ sub: "another-native-person", email: googleSession.user.email });
      assert.equal(conflict.response.status, 409);
      assert.equal(conflict.data.code, "account_exists");
      assert.deepEqual(revoked.slice(before), [{ client_id: bindings.APPLE_BUNDLE_ID, token: `refresh-${conflict.code}` }]);
    });
    await t.test("deleting an Apple account revokes its Apple token", async () => {
      const stored = await db.prepare("SELECT refresh_token FROM account_oauth_identities WHERE subject = 'native-apple-person'").first();
      const before = revoked.length;
      const deleted = await call("/v1/me", { confirm: true }, nativeSession.token, "DELETE");
      assert.equal(deleted.response.status, 200);
      assert.deepEqual(revoked.slice(before), [{ client_id: bindings.APPLE_BUNDLE_ID, token: stored.refresh_token }]);
      assert.equal(await db.prepare("SELECT 1 FROM account_oauth_identities WHERE subject = 'native-apple-person'").first(), null);
    });
    await t.test("requires explicit linking instead of claiming an existing email account", async () => {
      const conflict = await callback(await start(), { sub: "second-google-person", email: googleSession.user.email });
      assert.equal(new URL(conflict.headers.get("location")).searchParams.get("oauth_error"), "account_exists");
      const linked = await finish(await start("apple", { token: googleSession.token, link: true }), { sub: "linked-apple-person" });
      assert.equal(linked.user.id, googleSession.user.id);
      assert.equal(linked.linked, true);
      const returning = await finish(await start("apple"), { sub: "linked-apple-person" });
      assert.equal(returning.user.id, googleSession.user.id);
      const connected = (await call("/v1/auth/providers", undefined, returning.token)).data.connected;
      assert.deepEqual(connected.sort(), ["apple", "google"]);
      const wrongAccount = await callback(await start("apple", { token: googleSession.token, link: true }));
      assert.equal(new URL(wrongAccount.headers.get("location")).searchParams.get("oauth_error"), "account_exists");
    });
  } finally { await mf.dispose(); }
});
