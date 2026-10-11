import { createRemoteJWKSet, decodeJwt, importPKCS8, jwtVerify, SignJWT, type JWTPayload } from "jose";
import type { Account } from "./index";

export interface SocialAuthEnv {
  RULES_DB: D1Database;
  AUTH_BASE_URL?: string;
  GOOGLE_CLIENT_ID?: string;
  GOOGLE_CLIENT_SECRET?: string;
  APPLE_CLIENT_ID?: string;
  APPLE_TEAM_ID?: string;
  APPLE_KEY_ID?: string;
  APPLE_PRIVATE_KEY?: string;
  // The iPhone app's bundle identifier: the audience of tokens from the native Sign in with Apple sheet.
  APPLE_BUNDLE_ID?: string;
}

type Provider = "google" | "apple";
type Flow = {
  provider: Provider; nonce: string; pkce_verifier: string; exchange_secret_hash: string;
  return_uri: string; link_user_id: string | null;
};
type Hooks = {
  authenticate(request: Request, env: SocialAuthEnv): Promise<{ account: Account } | null>;
  createSessionResponse(env: SocialAuthEnv, account: Account, status: number): Promise<Response>;
};
const providers = {
  google: {
    authorize: "https://accounts.google.com/o/oauth2/v2/auth",
    token: "https://oauth2.googleapis.com/token",
    issuer: ["https://accounts.google.com", "accounts.google.com"],
    keys: createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs")),
  },
  apple: {
    authorize: "https://appleid.apple.com/auth/authorize",
    token: "https://appleid.apple.com/auth/token",
    issuer: "https://appleid.apple.com",
    keys: createRemoteJWKSet(new URL("https://appleid.apple.com/auth/keys")),
  },
};
const accountColumns = "id, email, display_name, country_code, plan, created_at, email_verified, (password_hash != '') AS has_password";
// Marks flows started by the iPhone's native Apple sheet; the browser callback never consumes them.
const nativeAppleFlow = "native-apple";

// The signing key is also what revokes Apple tokens when an account is deleted, so no Apple sign-in works without it.
function appleKeyReady(env: SocialAuthEnv): boolean {
  return Boolean(env.APPLE_TEAM_ID && env.APPLE_KEY_ID && env.APPLE_PRIVATE_KEY);
}

function enabled(env: SocialAuthEnv, provider: Provider): boolean {
  return provider === "google" ? Boolean(env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET)
    : Boolean(env.APPLE_CLIENT_ID && appleKeyReady(env));
}

function nativeAppleEnabled(env: SocialAuthEnv): boolean {
  return Boolean(env.APPLE_BUNDLE_ID && appleKeyReady(env));
}

function json(body: object, status = 200): Response {
  return Response.json(body, { status, headers: { "cache-control": "no-store", "access-control-allow-origin": "*" } });
}

function randomSecret(): string {
  return [...crypto.getRandomValues(new Uint8Array(32))].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function digest(value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}

async function hash(value: string): Promise<string> {
  return [...await digest(value)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function base64url(value: Uint8Array): string {
  return btoa(String.fromCharCode(...value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function readBody(request: Request): Promise<Record<string, unknown> | null> {
  if (Number(request.headers.get("content-length")) > 16_384) return null;
  try {
    const body = await request.text();
    if (new TextEncoder().encode(body).byteLength > 16_384) return null;
    const value = JSON.parse(body);
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch { return null; }
}

function validReturnUri(value: unknown): value is string {
  return value === "nz.fishingnz.app://auth/callback" || value === "nz.fishingnz.catchcheck://auth/callback";
}

function callbackUri(request: Request, env: SocialAuthEnv, provider: Provider): string {
  return `${(env.AUTH_BASE_URL || new URL(request.url).origin).replace(/\/+$/, "")}/v1/auth/oauth/${provider}/callback`;
}

function returnToApp(flow: Flow, parameter: string, value: string): Response {
  const url = new URL(flow.return_uri);
  // Only a short-lived grant appears in the URL. The initiating client holds the separate exchange secret.
  url.searchParams.set(parameter, value);
  return new Response(null, { status: 303, headers: { location: url.href, "cache-control": "no-store", "referrer-policy": "no-referrer" } });
}

export async function handleSocialAuth(request: Request, env: SocialAuthEnv, hooks: Hooks): Promise<Response> {
  const path = new URL(request.url).pathname;
  if (path === "/v1/auth/providers" && request.method === "GET") {
    const auth = await hooks.authenticate(request, env);
    const connected = auth ? (await env.RULES_DB.prepare("SELECT provider FROM account_oauth_identities WHERE user_id = ?")
      .bind(auth.account.id).all<{ provider: Provider }>()).results.map((row) => row.provider) : [];
    return json({ google: enabled(env, "google"), apple: enabled(env, "apple"), apple_native: nativeAppleEnabled(env), connected });
  }
  if (path === "/v1/auth/apple/start" && request.method === "POST") return startNativeApple(request, env, hooks);
  if (path === "/v1/auth/apple/native" && request.method === "POST") return finishNativeApple(request, env, hooks);
  if (path === "/v1/auth/oauth/start" && request.method === "POST") return start(request, env, hooks);
  if (path === "/v1/auth/oauth/exchange" && request.method === "POST") return exchange(request, env, hooks);
  const match = path.match(/^\/v1\/auth\/oauth\/(google|apple)\/callback$/);
  if (match && request.method === (match[1] === "apple" ? "POST" : "GET")) return callback(request, env, match[1] as Provider);
  return json({ error: "Not found." }, 404);
}

async function start(request: Request, env: SocialAuthEnv, hooks: Hooks): Promise<Response> {
  const body = await readBody(request);
  if (!body || (body.provider !== "google" && body.provider !== "apple") || !validReturnUri(body.return_uri)) {
    return json({ error: "Choose a supported sign-in provider and return address." }, 400);
  }
  const provider = body.provider;
  if (!enabled(env, provider)) return json({ error: `${provider === "google" ? "Google" : "Apple"} sign-in is not available yet.`, code: "provider_unavailable" }, 503);
  const auth = body.link === true ? await hooks.authenticate(request, env) : null;
  if (body.link === true && !auth) return json({ error: "Sign in before connecting another account." }, 401);
  const state = randomSecret(), nonce = randomSecret(), verifier = randomSecret(), secret = randomSecret();
  const now = new Date().toISOString();
  await env.RULES_DB.batch([
    env.RULES_DB.prepare("DELETE FROM account_oauth_flows WHERE expires_at <= ?").bind(now),
    env.RULES_DB.prepare("DELETE FROM account_oauth_grants WHERE expires_at <= ?").bind(now),
    env.RULES_DB.prepare(`INSERT INTO account_oauth_flows
      (state_hash, provider, nonce, pkce_verifier, exchange_secret_hash, return_uri, link_user_id, expires_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(await hash(state), provider, nonce, verifier, await hash(secret), body.return_uri, auth?.account.id || null,
        new Date(Date.now() + 600_000).toISOString()),
  ]);
  const url = new URL(providers[provider].authorize);
  url.search = new URLSearchParams({
    client_id: provider === "google" ? env.GOOGLE_CLIENT_ID! : env.APPLE_CLIENT_ID!,
    redirect_uri: callbackUri(request, env, provider), response_type: "code", state, nonce,
    scope: provider === "google" ? "openid email profile" : "name email",
  }).toString();
  if (provider === "google") {
    url.searchParams.set("code_challenge", base64url(await digest(verifier)));
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("prompt", "select_account");
  } else url.searchParams.set("response_mode", "form_post");
  return json({ authorization_url: url.href, exchange_secret: secret });
}

// clientId is the Services ID for the browser flow, or the bundle ID for the iPhone's native sheet.
async function appleClientSecret(env: SocialAuthEnv, clientId: string): Promise<string> {
  const key = await importPKCS8(env.APPLE_PRIVATE_KEY!.replace(/\\n/g, "\n"), "ES256");
  return new SignJWT({}).setProtectedHeader({ alg: "ES256", kid: env.APPLE_KEY_ID! })
    .setIssuer(env.APPLE_TEAM_ID!).setSubject(clientId).setAudience("https://appleid.apple.com")
    .setIssuedAt().setExpirationTime("5m").sign(key);
}

/** Ends the app's Apple authorization (App Store guideline 5.1.1(v)). Best effort: never blocks account deletion. */
export async function revokeAppleToken(env: SocialAuthEnv, clientId: string, refreshToken: string): Promise<void> {
  if (!appleKeyReady(env)) { console.error("Apple token not revoked: the Apple signing key is not configured"); return; }
  try {
    const response = await fetch("https://appleid.apple.com/auth/revoke", {
      method: "POST", signal: AbortSignal.timeout(10_000),
      body: new URLSearchParams({ client_id: clientId, client_secret: await appleClientSecret(env, clientId),
        token: refreshToken, token_type_hint: "refresh_token" }),
    });
    if (!response.ok) console.error(`Apple token revocation failed (${response.status})`);
  } catch { console.error("Apple token revocation failed"); }
}

export async function verifyIdentityToken(token: string, provider: Provider, env: SocialAuthEnv, nonce: string,
  audience = provider === "google" ? env.GOOGLE_CLIENT_ID : env.APPLE_CLIENT_ID): Promise<JWTPayload> {
  const { payload } = await jwtVerify(token, providers[provider].keys, {
    issuer: providers[provider].issuer,
    audience,
    algorithms: ["RS256"], requiredClaims: ["sub", "exp", "iat", "nonce"], clockTolerance: 5, maxTokenAge: "10m",
  });
  if (payload.nonce !== nonce || typeof payload.sub !== "string" || !payload.sub || payload.sub.length > 255) throw new Error("Invalid identity.");
  return payload;
}

class AccountConflict extends Error {}

async function resolveAccount(env: SocialAuthEnv, flow: Flow, claims: JWTPayload, appleUser: string | null): Promise<string> {
  const identity = await env.RULES_DB.prepare("SELECT user_id FROM account_oauth_identities WHERE provider = ? AND subject = ?")
    .bind(flow.provider, claims.sub!).first<{ user_id: string }>();
  if (identity) {
    if (flow.link_user_id && identity.user_id !== flow.link_user_id) throw new AccountConflict();
    return identity.user_id;
  }
  const now = new Date().toISOString();
  if (flow.link_user_id) {
    await env.RULES_DB.prepare("INSERT INTO account_oauth_identities (provider, subject, user_id, created_at) VALUES (?, ?, ?, ?)")
      .bind(flow.provider, claims.sub!, flow.link_user_id, now).run();
    return flow.link_user_id;
  }
  const email = typeof claims.email === "string" ? claims.email.trim().toLowerCase() : "";
  if (!(claims.email_verified === true || claims.email_verified === "true") || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new Error("A verified email is required.");
  }
  const existing = await env.RULES_DB.prepare("SELECT id FROM account_users WHERE email = ? COLLATE NOCASE").bind(email).first();
  // Email alone never connects a provider to an existing account. Require an authenticated linking flow.
  if (existing) throw new AccountConflict();
  let name = typeof claims.name === "string" ? claims.name : "";
  if (flow.provider === "apple" && appleUser) {
    try {
      const user = JSON.parse(appleUser);
      name = [user.name?.firstName, user.name?.lastName].filter((part) => typeof part === "string").join(" ");
    } catch { /* Apple supplies the name on the first authorization only. */ }
  }
  const id = crypto.randomUUID();
  await env.RULES_DB.batch([
    env.RULES_DB.prepare(`INSERT INTO account_users
      (id, email, password_hash, password_salt, display_name, country_code, plan, created_at, updated_at, email_verified)
      VALUES (?, ?, '', '', ?, 'NZ', 'free', ?, ?, 1)`).bind(id, email, name.trim().slice(0, 80), now, now),
    env.RULES_DB.prepare("INSERT INTO account_oauth_identities (provider, subject, user_id, created_at) VALUES (?, ?, ?, ?)")
      .bind(flow.provider, claims.sub!, id, now),
  ]);
  return id;
}

async function callback(request: Request, env: SocialAuthEnv, provider: Provider): Promise<Response> {
  let params: URLSearchParams;
  if (provider === "apple") {
    if (!request.headers.get("content-type")?.startsWith("application/x-www-form-urlencoded")) return json({ error: "Invalid sign-in response." }, 400);
    const body = await request.text();
    if (body.length > 16_384) return json({ error: "Invalid sign-in response." }, 400);
    params = new URLSearchParams(body);
  } else params = new URL(request.url).searchParams;
  const state = params.get("state") || "";
  if (!/^[a-f0-9]{64}$/.test(state)) return json({ error: "This sign-in request is invalid or expired." }, 400);
  // Consume the state atomically, including cancelled requests, to prevent callback replay.
  const flow = await env.RULES_DB.prepare(`DELETE FROM account_oauth_flows
    WHERE state_hash = ? AND provider = ? AND return_uri != ? AND expires_at > ? RETURNING *`)
    .bind(await hash(state), provider, nativeAppleFlow, new Date().toISOString()).first<Flow>();
  if (!flow) return json({ error: "This sign-in request is invalid or expired. Please start again." }, 400);
  if (params.has("error")) return returnToApp(flow, "oauth_error", "cancelled");
  const code = params.get("code");
  if (!code || code.length > 4096 || !enabled(env, provider)) return returnToApp(flow, "oauth_error", "failed");
  try {
    const body = new URLSearchParams({
      grant_type: "authorization_code", code, redirect_uri: callbackUri(request, env, provider),
      client_id: provider === "google" ? env.GOOGLE_CLIENT_ID! : env.APPLE_CLIENT_ID!,
      client_secret: provider === "google" ? env.GOOGLE_CLIENT_SECRET! : await appleClientSecret(env, env.APPLE_CLIENT_ID!),
    });
    if (provider === "google") body.set("code_verifier", flow.pkce_verifier);
    const response = await fetch(providers[provider].token, { method: "POST", body, signal: AbortSignal.timeout(10_000) });
    const result = await response.json() as { id_token?: string; refresh_token?: string };
    if (!response.ok || typeof result.id_token !== "string") throw new Error("Provider rejected sign-in.");
    const claims = await verifyIdentityToken(result.id_token, provider, env, flow.nonce);
    const userId = await resolveAccountKeepingAppleToken(env, flow, claims, params.get("user"), env.APPLE_CLIENT_ID!, result.refresh_token);
    const grant = randomSecret();
    await env.RULES_DB.prepare(`INSERT INTO account_oauth_grants (code_hash, exchange_secret_hash, user_id, linked, expires_at)
      VALUES (?, ?, ?, ?, ?)`)
      .bind(await hash(grant), flow.exchange_secret_hash, userId, flow.link_user_id ? 1 : 0, new Date(Date.now() + 120_000).toISOString()).run();
    return returnToApp(flow, "oauth_code", grant);
  } catch (error) {
    return returnToApp(flow, "oauth_error", error instanceof AccountConflict ? "account_exists" : "failed");
  }
}

async function exchange(request: Request, env: SocialAuthEnv, hooks: Hooks): Promise<Response> {
  const body = await readBody(request);
  if (!body || typeof body.code !== "string" || typeof body.exchange_secret !== "string"
    || !/^[a-f0-9]{64}$/.test(body.code) || !/^[a-f0-9]{64}$/.test(body.exchange_secret)) {
    return json({ error: "This sign-in request is invalid. Please start again." }, 400);
  }
  const grant = await env.RULES_DB.prepare(`DELETE FROM account_oauth_grants
    WHERE code_hash = ? AND exchange_secret_hash = ? AND expires_at > ? RETURNING user_id, linked`)
    .bind(await hash(body.code), await hash(body.exchange_secret), new Date().toISOString()).first<{ user_id: string; linked: number }>();
  if (!grant) return json({ error: "This sign-in request is invalid or expired. Please start again." }, 401);
  return session(env, hooks, grant.user_id, grant.linked === 1);
}

async function session(env: SocialAuthEnv, hooks: Hooks, userId: string, linked: boolean): Promise<Response> {
  const account = await env.RULES_DB.prepare(`SELECT ${accountColumns} FROM account_users WHERE id = ? AND email_verified = 1`)
    .bind(userId).first<Account>();
  if (!account) return json({ error: "Account is unavailable." }, 401);
  const response = await hooks.createSessionResponse(env, account, 200);
  const result = await response.json() as object;
  return json({ ...result, linked });
}

/**
 * resolveAccount for Apple, keeping the refresh token so account deletion can revoke it.
 * A sign-in without one fails; when no account results, the new token is revoked straight away.
 */
async function resolveAccountKeepingAppleToken(env: SocialAuthEnv, flow: Flow, claims: JWTPayload, appleUser: string | null,
  clientId: string, refreshToken: unknown): Promise<string> {
  if (flow.provider !== "apple") return resolveAccount(env, flow, claims, appleUser);
  if (typeof refreshToken !== "string" || !refreshToken || refreshToken.length > 4096) throw new Error("Apple sent no refresh token.");
  try {
    const userId = await resolveAccount(env, flow, claims, appleUser);
    await env.RULES_DB.prepare(`UPDATE account_oauth_identities SET refresh_token = ?, token_client_id = ?
      WHERE provider = 'apple' AND subject = ?`).bind(refreshToken, clientId, claims.sub!).run();
    return userId;
  } catch (error) {
    await revokeAppleToken(env, clientId, refreshToken);
    throw error;
  }
}

/** Step 1 of the iPhone's native Sign in with Apple: a single-use nonce for the Apple sheet. */
async function startNativeApple(request: Request, env: SocialAuthEnv, hooks: Hooks): Promise<Response> {
  const body = await readBody(request);
  if (!body) return json({ error: "Send a JSON object." }, 400);
  if (!nativeAppleEnabled(env)) return json({ error: "Apple sign-in is not available yet.", code: "provider_unavailable" }, 503);
  const auth = body.link === true ? await hooks.authenticate(request, env) : null;
  if (body.link === true && !auth) return json({ error: "Sign in before connecting another account." }, 401);
  const state = randomSecret(), nonce = randomSecret();
  const now = new Date().toISOString();
  await env.RULES_DB.batch([
    env.RULES_DB.prepare("DELETE FROM account_oauth_flows WHERE expires_at <= ?").bind(now),
    env.RULES_DB.prepare(`INSERT INTO account_oauth_flows
      (state_hash, provider, nonce, pkce_verifier, exchange_secret_hash, return_uri, link_user_id, expires_at)
      VALUES (?, 'apple', ?, '', '', ?, ?, ?)`)
      .bind(await hash(state), nonce, nativeAppleFlow, auth?.account.id || null, new Date(Date.now() + 600_000).toISOString()),
  ]);
  return json({ state, nonce });
}

/** Step 2: the sheet's identity token (bound to the nonce) and authorization code become a session. */
async function finishNativeApple(request: Request, env: SocialAuthEnv, hooks: Hooks): Promise<Response> {
  const body = await readBody(request);
  const name = body?.name && typeof body.name === "object" ? body.name as Record<string, unknown> : {};
  if (!body || typeof body.state !== "string" || !/^[a-f0-9]{64}$/.test(body.state)
    || typeof body.identity_token !== "string" || body.identity_token.length > 8192
    || typeof body.authorization_code !== "string" || !body.authorization_code || body.authorization_code.length > 4096
    || [name.given, name.family].some((part) => part !== undefined && (typeof part !== "string" || part.length > 80))) {
    return json({ error: "This sign-in request is invalid. Please start again." }, 400);
  }
  if (!nativeAppleEnabled(env)) return json({ error: "Apple sign-in is not available yet.", code: "provider_unavailable" }, 503);
  // Consume the nonce first, so a token can be presented once.
  const flow = await env.RULES_DB.prepare(`DELETE FROM account_oauth_flows
    WHERE state_hash = ? AND provider = 'apple' AND return_uri = ? AND expires_at > ? RETURNING *`)
    .bind(await hash(body.state), nativeAppleFlow, new Date().toISOString()).first<Flow>();
  if (!flow) return json({ error: "This sign-in request is invalid or expired. Please start again." }, 400);
  const bundleId = env.APPLE_BUNDLE_ID!;
  try {
    const claims = await verifyIdentityToken(body.identity_token, "apple", env, flow.nonce, bundleId);
    const response = await fetch(providers.apple.token, {
      method: "POST", signal: AbortSignal.timeout(10_000),
      body: new URLSearchParams({ grant_type: "authorization_code", code: body.authorization_code,
        client_id: bundleId, client_secret: await appleClientSecret(env, bundleId) }),
    });
    const result = await response.json() as { id_token?: string; refresh_token?: string };
    if (!response.ok || typeof result.id_token !== "string" || decodeJwt(result.id_token).sub !== claims.sub) {
      throw new Error("Apple rejected the authorization code.");
    }
    // Apple gives the name to the app, on the first authorization only.
    const appleUser = JSON.stringify({ name: { firstName: name.given, lastName: name.family } });
    const userId = await resolveAccountKeepingAppleToken(env, flow, claims, appleUser, bundleId, result.refresh_token);
    return session(env, hooks, userId, Boolean(flow.link_user_id));
  } catch (error) {
    return error instanceof AccountConflict
      ? json({ error: "This account already exists or is connected elsewhere. Sign in with your usual method, then connect Apple from your profile.", code: "account_exists" }, 409)
      : json({ error: "Could not finish Apple sign-in. Please try again.", code: "failed" }, 400);
  }
}
