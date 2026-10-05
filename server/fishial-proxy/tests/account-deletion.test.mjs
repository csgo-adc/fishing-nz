import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { bundleWorker, loadWorker } from "./load-worker.mjs";

const hash = (value) => createHash("sha256").update(value).digest("hex");

test("account deletion removes associated data and verifies web requests with real D1", { timeout: 60_000 }, async (t) => {
  const emails = [];
  let failDelivery = false;
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "account-deletion-tests" },
    bindings: { AUTH_BASE_URL: "https://fishing.test", RESEND_API_KEY: "test-key", ACCOUNT_EMAIL_FROM: "accounts@example.com", PUBLIC_DEVELOPER_NAME: "Tristan", PUBLIC_SUPPORT_EMAIL: "support@example.com" },
    outboundService: async (request) => {
      assert.equal(request.url, "https://api.resend.com/emails");
      emails.push(await request.json());
      return Response.json({ id: "test-email" }, { status: failDelivery ? 503 : 200 });
    },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
    }
    const now = new Date().toISOString();
    const future = new Date(Date.now() + 86_400_000).toISOString();
    const sessions = { first: "a".repeat(64), second: "b".repeat(64), third: "c".repeat(64) };
    for (const [id, session] of Object.entries(sessions)) {
      await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, display_name, created_at, updated_at, email_verified) VALUES (?, ?, 'hash', 'salt', 'Fisher', ?, ?, 1)").bind(id, `${id}@example.com`, now, now).run();
      await db.prepare("INSERT INTO account_sessions VALUES (?, ?, ?, ?)").bind(hash(session), id, now, future).run();
      await db.prepare("INSERT INTO account_feedback VALUES (?, ?, 'bug', 'A private report', 3, ?)").bind(`${id}-feedback`, id, now).run();
      await db.prepare("INSERT INTO account_events VALUES (?, ?, 'app_opened', NULL, 'android', ?)").bind(`${id}-event`, id, now).run();
      await db.prepare("INSERT INTO account_email_verifications VALUES (?, ?, ?, ?)").bind(`${id}-verify`, id, now, future).run();
      await db.prepare("INSERT INTO account_oauth_identities VALUES ('google', ?, ?, ?)").bind(`${id}-subject`, id, now).run();
      await db.prepare("INSERT INTO account_oauth_flows VALUES (?, 'google', 'nonce', 'pkce', 'secret', 'nz.fishingnz.app://auth/callback', ?, ?)").bind(`${id}-flow`, id, future).run();
      await db.prepare("INSERT INTO account_oauth_grants VALUES (?, 'secret', ?, 0, ?)").bind(`${id}-grant`, id, future).run();
    }
    const call = (path, options = {}) => mf.dispatchFetch(`https://fishing.test${path}`, options);
    const jsonDelete = (body, token = sessions.first) => call("/v1/me", { method: "DELETE", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body) });
    const formPost = (path, body, origin = "https://fishing.test") => call(path, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", origin }, body: new URLSearchParams(body).toString() });
    const count = async (table, id, column = "user_id") => (await db.prepare(`SELECT COUNT(*) AS count FROM ${table} WHERE ${column} = ?`).bind(id).first()).count;
    async function assertDeleted(id) {
      assert.equal(await count("account_users", id, "id"), 0);
      for (const table of ["account_sessions", "account_feedback", "account_events", "account_email_verifications", "account_oauth_identities", "account_oauth_grants", "account_deletion_tokens"]) assert.equal(await count(table, id), 0, table);
      assert.equal(await count("account_oauth_flows", id, "link_user_id"), 0);
    }
    await t.test("publishes a public policy and deletion page without authentication", async () => {
      const policy = await call("/privacy");
      assert.equal(policy.status, 200);
      const html = await policy.text();
      for (const text of ["Tristan", "support@example.com", "OpenAI", "Cloudflare", "Resend", "Open-Meteo", "Firebase", "90 days", "365 days"]) assert.ok(html.includes(text), text);
      assert.ok(policy.headers.get("content-security-policy").includes("frame-ancestors 'none'"));
      const deletion = await call("/delete-account");
      assert.equal(deletion.status, 200);
      assert.match(await deletion.text(), /name="email"/);
    });
    await t.test("requires authentication and explicit confirmation; cannot delete another account", async () => {
      assert.equal((await jsonDelete({ confirm: true }, "d".repeat(64))).status, 401);
      assert.equal((await jsonDelete({})).status, 400);
      assert.equal(await count("account_users", "first", "id"), 1);
      const response = await jsonDelete({ confirm: true, user_id: "second" });
      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), { deleted: true });
      await assertDeleted("first");
      assert.equal(await count("account_users", "second", "id"), 1);
      assert.equal((await call("/v1/me", { headers: { authorization: `Bearer ${sessions.first}` } })).status, 401);
    });
    await t.test("does not expose whether an address exists and throttles repeated mail", async () => {
      const missing = await formPost("/delete-account", { email: "missing@example.com" });
      const present = await formPost("/delete-account", { email: "second@example.com" });
      assert.equal(missing.status, 202);
      assert.equal(present.status, 202);
      assert.equal(await missing.text(), await present.text());
      assert.equal(emails.length, 1);
      await formPost("/delete-account", { email: "second@example.com" });
      assert.equal(emails.length, 1);
      assert.equal((await formPost("/delete-account", { email: "second@example.com" }, "https://evil.test")).status, 403);
    });
    await t.test("email-link GET never deletes; POST confirms deletion; tokens cannot be replayed", async () => {
      const link = emails[0].text.match(/https:\/\/fishing\.test\/delete-account\/confirm\?token=([a-f0-9]{64})/);
      assert.ok(link);
      const token = link[1];
      const stored = await db.prepare("SELECT token_hash FROM account_deletion_tokens WHERE user_id = 'second'").first();
      assert.equal(stored.token_hash, hash(token));
      assert.notEqual(stored.token_hash, token);
      assert.equal((await call(`/delete-account/confirm?token=${token}`)).status, 200);
      assert.equal(await count("account_users", "second", "id"), 1);
      assert.equal((await formPost("/delete-account/confirm", { token })).status, 400);
      assert.equal((await formPost("/delete-account/confirm", { token, confirm: "delete" }, "https://evil.test")).status, 403);
      assert.equal((await formPost("/delete-account/confirm", { token, confirm: "delete" })).status, 200);
      await assertDeleted("second");
      assert.equal((await formPost("/delete-account/confirm", { token, confirm: "delete" })).status, 400);
    });
    await t.test("rejects expired tokens and cleans up failed delivery so it can be retried", async () => {
      const expired = "e".repeat(64);
      await db.prepare("INSERT INTO account_deletion_tokens VALUES (?, 'third', ?, ?)").bind(hash(expired), new Date(Date.now() - 2 * 86_400_000).toISOString(), new Date(Date.now() - 86_400_000).toISOString()).run();
      assert.equal((await call(`/delete-account/confirm?token=${expired}`)).status, 400);
      failDelivery = true;
      assert.equal((await formPost("/delete-account", { email: "third@example.com" })).status, 202);
      assert.equal(await count("account_deletion_tokens", "third"), 1, "only the expired token remains");
      failDelivery = false;
      await formPost("/delete-account", { email: "third@example.com" });
      assert.equal(await count("account_deletion_tokens", "third"), 2);
    });
    await t.test("daily retention cleanup removes old records but preserves current data", async () => {
      const old = new Date(Date.now() - 400 * 86_400_000).toISOString();
      await db.prepare("INSERT INTO account_events VALUES ('old-event', 'third', 'feature_used', 'map', 'android', ?)").bind(old).run();
      await db.prepare("INSERT INTO account_feedback VALUES ('old-feedback', 'third', 'bug', 'Old feedback', 1, ?)").bind(old).run();
      const worker = await loadWorker();
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => new Response(`<html>${"Fishing rules. ".repeat(50)}</html>`, { headers: { "content-type": "text/html" } });
      try { await worker.scheduled({ scheduledTime: Date.now() }, { RULES_DB: db }); }
      finally { globalThis.fetch = originalFetch; }
      assert.equal(await count("account_events", "third"), 1);
      assert.equal(await count("account_feedback", "third"), 1);
      assert.equal(await count("account_deletion_tokens", "third"), 1);
      assert.equal(await count("account_users", "third", "id"), 1);
    });
  } finally { await mf.dispose(); }
});
