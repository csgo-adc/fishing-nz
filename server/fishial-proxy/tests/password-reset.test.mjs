import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { bundleWorker, loadWorker } from "./load-worker.mjs";

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const PASSWORD = "Original123";

test("password reset, password change and abuse limits with real D1", { timeout: 120_000 }, async (t) => {
  const emails = [];
  let failDelivery = false;
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "password-reset-tests" },
    bindings: {
      AUTH_BASE_URL: "https://fishing.test", RESEND_API_KEY: "test-key", ACCOUNT_EMAIL_FROM: "accounts@example.com",
      PUBLIC_DEVELOPER_NAME: "Tristan", PUBLIC_SUPPORT_EMAIL: "support@example.com", RULES_INGEST_TOKEN: "ingest-secret",
    },
    outboundService: async (request) => {
      assert.equal(request.url, "https://api.resend.com/emails");
      const message = await request.json();
      if (!failDelivery) emails.push(message);
      return Response.json({ id: "test-email" }, { status: failDelivery ? 503 : 200 });
    },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
    }

    const call = (path, options = {}) => mf.dispatchFetch(`https://fishing.test${path}`, options);
    // Each scenario uses its own network address so the abuse limits in one never affect another.
    const api = async (path, method = "GET", body, { token, ip = "198.51.100.1" } = {}) => {
      const response = await call(path, {
        method,
        headers: { "cf-connecting-ip": ip, ...(body ? { "content-type": "application/json" } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      });
      return { status: response.status, headers: response.headers, data: await response.json().catch(() => null) };
    };
    const form = (path, body, { origin = "https://fishing.test", ip = "198.51.100.1" } = {}) => call(path, {
      method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", origin, "cf-connecting-ip": ip },
      body: new URLSearchParams(body).toString(),
    });
    const sentTo = (address, subject) => emails.filter((mail) => mail.to[0] === address && mail.subject.includes(subject));
    const tokenFrom = (mail) => mail.text.match(/token=([a-f0-9]{64})/)[1];

    async function register(email, ip, { verify = true } = {}) {
      assert.equal((await api("/v1/auth/register", "POST", { email, password: PASSWORD }, { ip })).status, 202);
      if (!verify) return;
      const mail = sentTo(email, "Confirm").at(-1);
      assert.equal((await api("/v1/auth/verify-email", "POST", { token: tokenFrom(mail) }, { ip })).status, 200);
    }
    async function signIn(email, ip, password = PASSWORD) {
      const result = await api("/v1/auth/login", "POST", { email, password }, { ip });
      return result.status === 200 ? result.data.token : result;
    }
    const sessionWorks = async (token, ip) => (await api("/v1/me", "GET", undefined, { token, ip })).status === 200;

    await t.test("serves public reset pages and a version-bearing health check", async () => {
      const forgot = await call("/forgot-password");
      assert.equal(forgot.status, 200);
      assert.match(await forgot.text(), /name="email"/);
      assert.equal((await call("/reset-password?token=not-a-token")).status, 400);
      assert.equal((await call("/reset-password?token=" + "f".repeat(64))).status, 400);
      const health = await api("/__health");
      assert.equal(health.data.ok, true);
      assert.match(health.data.version, /^\d+\.\d+\.\d+$/);
    });

    await t.test("reset link sets a new password, ends every session and can be used once", async () => {
      const ip = "198.51.100.10";
      const email = "reset@example.com";
      await register(email, ip);
      const oldSession = await signIn(email, ip);
      assert.ok(typeof oldSession === "string");

      const missing = await form("/forgot-password", { email: "nobody@example.com" }, { ip });
      const present = await form("/forgot-password", { email }, { ip });
      assert.equal(missing.status, 202);
      assert.equal(present.status, 202);
      assert.equal(await missing.text(), await present.text(), "same answer whether or not the email is registered");
      assert.equal(sentTo("nobody@example.com", "Reset").length, 0);
      assert.equal(sentTo(email, "Reset").length, 1);
      await form("/forgot-password", { email }, { ip });
      assert.equal(sentTo(email, "Reset").length, 1, "repeat requests within a minute send nothing");

      const token = tokenFrom(sentTo(email, "Reset")[0]);
      const stored = await db.prepare("SELECT token_hash FROM account_password_resets").first();
      assert.equal(stored.token_hash, sha256(token));

      assert.equal((await call(`/reset-password?token=${token}`)).status, 200);
      assert.equal((await call(`/reset-password?token=${token}`)).status, 200, "opening the link does not consume it");
      assert.equal((await form("/reset-password", { token, password: "Brand-new-1", confirm: "different-2" }, { ip })).status, 400);
      assert.equal((await form("/reset-password", { token, password: "short", confirm: "short" }, { ip })).status, 400);
      assert.equal((await form("/reset-password", { token, password: "Brand-new-1", confirm: "Brand-new-1" }, { origin: "https://evil.test", ip })).status, 403);
      assert.equal(await sessionWorks(oldSession, ip), true, "rejected attempts change nothing");

      const done = await form("/reset-password", { token, password: "Brand-new-1", confirm: "Brand-new-1" }, { ip });
      assert.equal(done.status, 200);
      assert.equal(await sessionWorks(oldSession, ip), false, "old sessions end");
      assert.equal((await signIn(email, ip)).status, 401, "old password no longer works");
      assert.ok(typeof await signIn(email, ip, "Brand-new-1") === "string");
      assert.equal((await form("/reset-password", { token, password: "Another-new-1", confirm: "Another-new-1" }, { ip })).status, 400, "token cannot be replayed");
      assert.equal(sentTo(email, "password was changed").length, 1);
    });

    await t.test("an expired link changes nothing, including existing sessions", async () => {
      const ip = "198.51.100.11";
      const email = "expired@example.com";
      await register(email, ip);
      const session = await signIn(email, ip);
      const user = await db.prepare("SELECT id FROM account_users WHERE email = ?").bind(email).first();
      const token = "c".repeat(64);
      const past = new Date(Date.now() - 60_000).toISOString();
      await db.prepare("INSERT INTO account_password_resets VALUES (?, ?, ?, ?)").bind(sha256(token), user.id, past, past).run();
      assert.equal((await call(`/reset-password?token=${token}`)).status, 400);
      assert.equal((await form("/reset-password", { token, password: "Brand-new-1", confirm: "Brand-new-1" }, { ip })).status, 400);
      assert.equal(await sessionWorks(session, ip), true);
      assert.ok(typeof await signIn(email, ip) === "string", "original password still works");
    });

    await t.test("a reset also confirms an unconfirmed email, and failed delivery leaves no token behind", async () => {
      const ip = "198.51.100.12";
      const email = "unconfirmed@example.com";
      await register(email, ip, { verify: false });
      assert.equal((await signIn(email, ip)).data.code, "email_not_verified");

      failDelivery = true;
      assert.equal((await form("/forgot-password", { email }, { ip })).status, 202);
      failDelivery = false;
      const user = await db.prepare("SELECT id FROM account_users WHERE email = ?").bind(email).first();
      assert.equal((await db.prepare("SELECT COUNT(*) AS n FROM account_password_resets WHERE user_id = ?").bind(user.id).first()).n, 0);

      assert.equal((await form("/forgot-password", { email }, { ip })).status, 202);
      const token = tokenFrom(sentTo(email, "Reset")[0]);
      assert.equal((await form("/reset-password", { token, password: "Chosen-pass-1", confirm: "Chosen-pass-1" }, { ip })).status, 200);
      assert.ok(typeof await signIn(email, ip, "Chosen-pass-1") === "string");
    });

    await t.test("limits reset requests per network and per email", async () => {
      const perEmail = "flooded@example.com";
      await register(perEmail, "198.51.100.13");
      for (let attempt = 0; attempt < 7; attempt++) await form("/forgot-password", { email: perEmail }, { ip: "198.51.100.13" });
      assert.ok(sentTo(perEmail, "Reset").length <= 1, "throttled to one email per minute");
      // Five requests per email per hour are counted; beyond that the answer stays identical but nothing is sent.
      const user = await db.prepare("SELECT id FROM account_users WHERE email = ?").bind(perEmail).first();
      await db.prepare("DELETE FROM account_password_resets WHERE user_id = ?").bind(user.id).run();
      const before = sentTo(perEmail, "Reset").length;
      const response = await form("/forgot-password", { email: perEmail }, { ip: "198.51.100.14" });
      assert.equal(response.status, 202);
      assert.equal(sentTo(perEmail, "Reset").length, before, "over the per-email limit, nothing is sent");

      const statuses = [];
      for (let attempt = 0; attempt < 12; attempt++) statuses.push((await form("/forgot-password", { email: `person${attempt}@example.com` }, { ip: "198.51.100.15" })).status);
      assert.deepEqual(statuses.slice(0, 10), Array(10).fill(202));
      assert.deepEqual(statuses.slice(10), [429, 429]);
    });

    await t.test("signed-in password change checks the current password and keeps only this session", async () => {
      const ip = "198.51.100.20";
      const email = "change@example.com";
      await register(email, ip);
      const current = await signIn(email, ip);
      const other = await signIn(email, ip);
      const change = (body, token = current) => api("/v1/me/password", "POST", body, { token, ip });

      assert.equal((await api("/v1/me/password", "POST", { current_password: PASSWORD, new_password: "Second-pass-1" }, { ip })).status, 401);
      assert.equal((await change({ current_password: PASSWORD, new_password: "short" })).status, 400);
      assert.equal((await change({ current_password: PASSWORD, new_password: PASSWORD })).status, 400);
      const wrong = await change({ current_password: "Wrong-pass-1", new_password: "Second-pass-1" });
      assert.equal(wrong.status, 401);
      assert.equal(wrong.data.code, "incorrect_password");

      assert.equal((await change({ current_password: PASSWORD, new_password: "Second-pass-1" })).status, 200);
      assert.equal(await sessionWorks(current, ip), true, "the session that made the change stays signed in");
      assert.equal(await sessionWorks(other, ip), false, "other sessions end");
      assert.equal((await signIn(email, ip, PASSWORD)).status, 401);
      assert.ok(typeof await signIn(email, ip, "Second-pass-1") === "string");
      assert.equal(sentTo(email, "password was changed").length, 1);
    });

    await t.test("password change locks out guessing and tells social-only accounts to use reset", async () => {
      const ip = "198.51.100.21";
      const email = "guess@example.com";
      await register(email, ip);
      const session = await signIn(email, ip);
      const change = () => api("/v1/me/password", "POST", { current_password: "Wrong-pass-1", new_password: "Second-pass-1" }, { token: session, ip });
      const statuses = [];
      for (let attempt = 0; attempt < 6; attempt++) statuses.push((await change()).status);
      assert.deepEqual(statuses, [401, 401, 401, 401, 401, 429]);
      const blocked = await api("/v1/me/password", "POST", { current_password: PASSWORD, new_password: "Second-pass-1" }, { token: session, ip });
      assert.equal(blocked.status, 429, "even the right password waits out the window");
      assert.ok(Number(blocked.headers.get("retry-after")) > 0);

      const social = await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, display_name, created_at, updated_at, email_verified) VALUES ('social-user', 'social@example.com', '', '', 'Social', ?, ?, 1)")
        .bind(new Date().toISOString(), new Date().toISOString()).run();
      assert.ok(social.success);
      await db.prepare("INSERT INTO account_sessions VALUES (?, 'social-user', ?, ?)").bind(sha256("d".repeat(64)), new Date().toISOString(), new Date(Date.now() + 86_400_000).toISOString()).run();
      const result = await api("/v1/me/password", "POST", { current_password: "anything-123", new_password: "Second-pass-1" }, { token: "d".repeat(64), ip: "198.51.100.22" });
      assert.equal(result.status, 409);
      assert.equal(result.data.code, "no_password");
    });

    await t.test("sign out everywhere ends every session for the account only", async () => {
      const ip = "198.51.100.30";
      await register("everywhere@example.com", ip);
      await register("bystander@example.com", ip);
      const first = await signIn("everywhere@example.com", ip);
      const second = await signIn("everywhere@example.com", ip);
      const bystander = await signIn("bystander@example.com", ip);
      assert.equal((await api("/v1/auth/logout-all", "POST", undefined, { ip })).status, 401);
      assert.equal((await api("/v1/auth/logout-all", "POST", undefined, { token: first, ip })).status, 200);
      assert.equal(await sessionWorks(first, ip), false);
      assert.equal(await sessionWorks(second, ip), false);
      assert.equal(await sessionWorks(bystander, ip), true);
    });

    await t.test("sign-in throttles repeated failures per account, per email and per network", async () => {
      const email = "throttle@example.com";
      await register(email, "198.51.100.40");
      const attempt = (ip, password = "Wrong-pass-1", address = email) => api("/v1/auth/login", "POST", { email: address, password }, { ip });

      const home = "198.51.100.41";
      const statuses = [];
      for (let count = 0; count < 5; count++) statuses.push((await attempt(home)).status);
      assert.deepEqual(statuses, [401, 401, 401, 401, 401]);
      const locked = await attempt(home, PASSWORD);
      assert.equal(locked.status, 429, "the right password is refused while the account-and-network pair is locked");
      assert.equal(locked.data.code, "rate_limited");
      assert.ok(Number(locked.headers.get("retry-after")) > 0);
      assert.equal((await attempt("198.51.100.42", PASSWORD)).status, 200, "the owner on another network is not locked out by one guesser");

      // Many guesses spread over many networks eventually protect the account itself (20 failures).
      for (let count = 0; count < 15; count++) await attempt(`203.0.113.${count + 1}`);
      assert.equal((await attempt("198.51.100.43", PASSWORD)).status, 429);

      // One network spraying different emails is stopped at 30 failures.
      const spray = "198.51.100.44";
      let sprayed = 0;
      for (let count = 0; count < 31; count++) if ((await attempt(spray, "Wrong-pass-1", `victim${count}@example.com`)).status === 429) sprayed++;
      assert.equal(sprayed, 1);
    });

    await t.test("sign-in takes similar time for unknown emails and wrong passwords", async () => {
      await register("timing@example.com", "198.51.100.50");
      const time = async (email, index) => {
        const started = performance.now();
        await api("/v1/auth/login", "POST", { email, password: "Wrong-pass-1" }, { ip: `192.0.2.${index}` });
        return performance.now() - started;
      };
      await time("timing@example.com", 1);
      const wrong = [];
      const unknown = [];
      for (let index = 0; index < 9; index++) {
        wrong.push(await time("timing@example.com", 10 + index));
        unknown.push(await time(`nobody${index}@example.com`, 30 + index));
      }
      // Compare the fastest runs. Other test files run at the same time and a busy CPU only ever makes a run slower, so the
      // fastest run is the closest to the real cost. If unknown emails skipped the password hash (the bug this guards against)
      // their fastest run would be a small fraction of the wrong-password one.
      const fastestWrong = Math.min(...wrong);
      const fastestUnknown = Math.min(...unknown);
      assert.ok(fastestUnknown > fastestWrong * 0.5, `fastest unknown ${fastestUnknown.toFixed(1)}ms vs fastest wrong ${fastestWrong.toFixed(1)}ms`);
    });

    await t.test("limits sign-ups, verification resends and feedback", async () => {
      const ip = "198.51.100.60";
      const statuses = [];
      for (let count = 0; count < 12; count++) statuses.push((await api("/v1/auth/register", "POST", { email: `bulk${count}@example.com`, password: PASSWORD }, { ip })).status);
      assert.deepEqual(statuses.slice(0, 10), Array(10).fill(202));
      assert.deepEqual(statuses.slice(10), [429, 429]);

      const resend = [];
      for (let count = 0; count < 12; count++) resend.push((await api("/v1/auth/resend-verification", "POST", { email: `bulk${count % 10}@example.com` }, { ip: "198.51.100.61" })).status);
      assert.deepEqual(resend.slice(10), [429, 429]);

      await register("feedback@example.com", "198.51.100.62");
      const session = await signIn("feedback@example.com", "198.51.100.62");
      const feedback = [];
      for (let count = 0; count < 11; count++) feedback.push((await api("/v1/feedback", "POST", { category: "idea", message: `Idea number ${count}` }, { token: session, ip: "198.51.100.62" })).status);
      assert.deepEqual(feedback, [...Array(10).fill(201), 429]);
    });

    await t.test("rules ingestion tokens must match exactly", async () => {
      const source = (token) => call("/v1/rules/source", { method: "POST", headers: token === undefined ? {} : { "x-rules-ingest-token": token }, body: JSON.stringify({ area_id: "central" }) });
      assert.equal((await source()).status, 401);
      assert.equal((await source("")).status, 401);
      assert.equal((await source("ingest-secre")).status, 401);
      assert.equal((await source("ingest-secret-")).status, 401);
      const importRules = await call("/v1/rules/import", { method: "POST", headers: { "x-rules-ingest-token": "wrong" }, body: "{}" });
      assert.equal(importRules.status, 401);
    });

    await t.test("tells the apps whether an account has a password to change", async () => {
      const ip = "198.51.100.70";
      const email = "haspassword@example.com";
      await register(email, ip);
      const login = await api("/v1/auth/login", "POST", { email, password: PASSWORD }, { ip });
      assert.equal(login.data.user.has_password, true);
      assert.equal((await api("/v1/me", "GET", undefined, { token: login.data.token, ip })).data.user.has_password, true);

      // A Google or Apple account that never chose a password.
      const now = new Date().toISOString();
      await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, display_name, created_at, updated_at, email_verified) VALUES ('social-pw', 'socialpw@example.com', '', '', 'Social', ?, ?, 1)").bind(now, now).run();
      await db.prepare("INSERT INTO account_sessions VALUES (?, 'social-pw', ?, ?)").bind(sha256("e".repeat(64)), now, new Date(Date.now() + 86_400_000).toISOString()).run();
      assert.equal((await api("/v1/me", "GET", undefined, { token: "e".repeat(64), ip })).data.user.has_password, false);
      assert.equal((await signIn("socialpw@example.com", ip, "anything-123")).status, 401, "cannot sign in with a password it does not have");

      // The reset link is how such an account adds one.
      assert.equal((await form("/forgot-password", { email: "socialpw@example.com" }, { ip })).status, 202);
      const token = tokenFrom(sentTo("socialpw@example.com", "Reset")[0]);
      assert.equal((await form("/reset-password", { token, password: "Added-pass-1", confirm: "Added-pass-1" }, { ip })).status, 200);
      const after = await api("/v1/auth/login", "POST", { email: "socialpw@example.com", password: "Added-pass-1" }, { ip });
      assert.equal(after.status, 200);
      assert.equal(after.data.user.has_password, true);
    });

    await t.test("daily cleanup removes spent reset links and old rate-limit counters only", async () => {
      const user = await db.prepare("SELECT id FROM account_users WHERE email = 'reset@example.com'").first();
      const now = Date.now();
      const iso = (offset) => new Date(now + offset).toISOString();
      await db.prepare("INSERT INTO account_password_resets VALUES ('expired-reset', ?, ?, ?)").bind(user.id, iso(-7_200_000), iso(-3_600_000)).run();
      await db.prepare("INSERT INTO account_password_resets VALUES ('live-reset', ?, ?, ?)").bind(user.id, iso(0), iso(3_600_000)).run();
      await db.prepare("INSERT INTO rate_limit_counters VALUES ('old-counter', ?, 3)").bind(now - 3 * 86_400_000).run();
      await db.prepare("INSERT INTO rate_limit_counters VALUES ('fresh-counter', ?, 3)").bind(now).run();
      const worker = await loadWorker();
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => new Response(`<html>${"Fishing rules. ".repeat(50)}</html>`, { headers: { "content-type": "text/html" } });
      try { await worker.scheduled({ scheduledTime: now }, { RULES_DB: db }); }
      finally { globalThis.fetch = originalFetch; }
      const ids = async (table, column) => (await db.prepare(`SELECT ${column} AS id FROM ${table}`).all()).results.map((row) => row.id);
      const resets = await ids("account_password_resets", "token_hash");
      assert.ok(!resets.includes("expired-reset"));
      assert.ok(resets.includes("live-reset"));
      const counters = await ids("rate_limit_counters", "key");
      assert.ok(!counters.includes("old-counter"));
      assert.ok(counters.includes("fresh-counter"));
    });
  } finally { await mf.dispose(); }
});
