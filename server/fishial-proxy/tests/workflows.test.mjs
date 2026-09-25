import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { cpSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const wrangler = join(project, "node_modules", ".bin", "wrangler");
const adminToken = "local-test-admin-token-0123456789abcdef";

function runWrangler(args, cwd) {
  const result = spawnSync(wrangler, args, {
    cwd, encoding: "utf8", timeout: 30_000, env: { ...process.env, CI: "1" },
  });
  if (result.status !== 0) throw new Error(`Wrangler failed: ${result.stderr || result.stdout}`);
}

async function freePort() {
  const server = createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const port = server.address().port;
  server.close();
  await once(server, "close");
  return port;
}

test("local D1 account and CMS workflow", { timeout: 90_000 }, async () => {
  const directory = mkdtempSync(join(tmpdir(), "catchcheck-worker-test-"));
  const state = join(directory, "state");
  const config = join(directory, "wrangler.toml");
  cpSync(join(project, "migrations"), join(directory, "migrations"), { recursive: true });
  writeFileSync(config, `name = "catchcheck-workflow-test"
main = ${JSON.stringify(join(project, "src", "index.ts"))}
compatibility_date = "2024-09-23"

[[d1_databases]]
binding = "RULES_DB"
database_name = "catchcheck-workflow-test"
database_id = "00000000-0000-0000-0000-000000000000"
migrations_dir = "migrations"
`);

  let worker;
  try {
    runWrangler(["d1", "migrations", "apply", "RULES_DB", "--local", "--config", config, "--persist-to", state], directory);
    const port = await freePort();
    const base = `http://127.0.0.1:${port}`;
    let workerLog = "";
    worker = spawn(wrangler, ["dev", "--local", "--config", config, "--persist-to", state,
      "--port", String(port), "--var", `ACCOUNT_ADMIN_TOKEN:${adminToken}`, "--show-interactive-dev-session=false"], {
      cwd: directory, env: { ...process.env, CI: "1" }, stdio: ["ignore", "pipe", "pipe"],
    });
    worker.stdout.on("data", (chunk) => { workerLog = (workerLog + chunk).slice(-4000); });
    worker.stderr.on("data", (chunk) => { workerLog = (workerLog + chunk).slice(-4000); });

    let ready = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (worker.exitCode !== null) throw new Error(`Worker exited before startup: ${workerLog}`);
      try {
        const response = await fetch(`${base}/__health`);
        ready = response.ok;
        if (ready) break;
      } catch { /* Worker is still starting. */ }
      await new Promise((done) => setTimeout(done, 100));
    }
    if (!ready) throw new Error(`Worker did not start: ${workerLog}`);

    async function call(path, method = "GET", body, headers = {}) {
      const response = await fetch(base + path, {
        method, headers: { ...headers, ...(body ? { "content-type": "application/json" } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      });
      return { status: response.status, data: await response.json() };
    }
    function sql(command) {
      runWrangler(["d1", "execute", "RULES_DB", "--local", "--config", config,
        "--persist-to", state, "--command", command], directory);
    }

    const email = "workflow@example.com";
    const password = "ExamplePassword123!";
    const registration = await call("/v1/auth/register", "POST", { email, password, display_name: "Workflow Tester" });
    assert.equal(registration.status, 503);
    assert.equal(registration.data.code, "email_delivery_failed");
    assert.equal((await call("/v1/auth/register", "POST", { email, password })).status, 503);
    assert.equal((await call("/v1/auth/resend-verification", "POST", { email })).status, 503);
    assert.equal((await call("/v1/auth/login", "POST", { email, password })).data.code, "email_not_verified");

    const adminHeaders = { "x-account-admin-token": adminToken };
    const pending = await call("/v1/admin/users?search=workflow", "GET", undefined, adminHeaders);
    assert.equal(pending.status, 200);
    assert.equal(pending.data.total, 1);
    const userId = pending.data.users[0].id;
    assert.equal((await call(`/v1/admin/users/${userId}/plan`, "PATCH", { plan: "paid" }, adminHeaders)).status, 409);

    const verificationToken = "a".repeat(64);
    const hash = createHash("sha256").update(verificationToken).digest("hex");
    sql(`INSERT INTO account_email_verifications (token_hash, user_id, created_at, expires_at) VALUES ('${hash}', '${userId}', '${new Date().toISOString()}', '${new Date(Date.now() + 60_000).toISOString()}')`);
    const verified = await call("/v1/auth/verify-email", "POST", { token: verificationToken });
    assert.equal(verified.status, 200);
    assert.equal(verified.data.verified, true);
    assert.equal((await call("/v1/auth/register", "POST", { email, password })).status, 202);

    const signedIn = await call("/v1/auth/login", "POST", { email, password });
    assert.equal(signedIn.status, 200);
    assert.equal(signedIn.data.user.email_verified, true);
    assert.equal(signedIn.data.user.plan, "free");
    assert.equal(signedIn.data.permissions.features.fish_identity, true);
    const auth = { authorization: `Bearer ${signedIn.data.token}` };
    assert.equal((await call("/v1/me", "GET", undefined, auth)).status, 200);
    assert.equal((await call("/v1/me/permissions", "GET", undefined, auth)).data.features.fish_identity, true);
    assert.equal((await call("/v1/fish/identify", "POST", { image: "invalid" })).status, 401);
    const freeFishRequest = await call("/v1/fish/identify", "POST", { image: "invalid" }, auth);
    assert.equal(freeFishRequest.status, 400);
    assert.match(freeFishRequest.data.error, /Upload a JPEG/);
    assert.equal((await call("/v1/feedback", "POST", { category: "bug", message: "Workflow feedback", rating: 4 }, auth)).status, 201);
    assert.equal((await call("/v1/analytics/events", "POST", { event_name: "feature_used", feature: "map", platform: "web" }, auth)).status, 202);
    assert.equal((await call(`/v1/admin/users/${userId}/plan`, "PATCH", { plan: "paid" }, adminHeaders)).status, 200);
    assert.equal((await call("/v1/me/permissions", "GET", undefined, auth)).data.features.fish_identity, true);

    sql("WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 30) INSERT INTO account_users (id, email, password_hash, password_salt, display_name, country_code, plan, created_at, updated_at, email_verified) SELECT printf('00000000-0000-4000-8000-%012d', n), printf('person%02d@example.com', n), '00', '00', printf('Person %02d', n), 'NZ', 'free', strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 1 FROM seq");
    sql(`WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 25) INSERT INTO account_feedback (id, user_id, category, message, rating, created_at) SELECT printf('feedback-%02d', n), '${userId}', 'general', printf('Page test feedback %02d', n), NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now') FROM seq`);
    const usersFirst = await call("/v1/admin/users?limit=25&offset=0", "GET", undefined, adminHeaders);
    const usersSecond = await call("/v1/admin/users?limit=25&offset=25", "GET", undefined, adminHeaders);
    assert.equal(usersFirst.data.total, 31);
    assert.equal(usersFirst.data.users.length, 25);
    assert.equal(usersSecond.data.users.length, 6);
    assert.equal((await call("/v1/admin/users?search=Person%200", "GET", undefined, adminHeaders)).data.total, 9);
    assert.equal((await call("/v1/admin/users?search=%25", "GET", undefined, adminHeaders)).data.total, 0);
    const feedbackFirst = await call("/v1/admin/feedback?limit=20&offset=0", "GET", undefined, adminHeaders);
    const feedbackSecond = await call("/v1/admin/feedback?limit=20&offset=20", "GET", undefined, adminHeaders);
    assert.equal(feedbackFirst.data.total, 26);
    assert.equal(feedbackFirst.data.feedback.length, 20);
    assert.equal(feedbackSecond.data.feedback.length, 6);
    assert.equal((await call("/v1/admin/users", "GET", undefined, { "x-account-admin-token": "wrong" })).status, 401);

    assert.equal((await call("/v1/auth/logout", "POST", undefined, auth)).status, 200);
    assert.equal((await call("/v1/me", "GET", undefined, auth)).status, 401);
  } finally {
    if (worker && worker.exitCode === null) {
      worker.kill("SIGTERM");
      await Promise.race([once(worker, "exit"), new Promise((done) => setTimeout(done, 3000))]);
      if (worker.exitCode === null) worker.kill("SIGKILL");
    }
    rmSync(directory, { recursive: true, force: true });
  }
});
