import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { bundleWorker } from "./load-worker.mjs";

const module = await build({ entryPoints: [new URL("../src/identification-quota.ts", import.meta.url).pathname], write: false, bundle: true, format: "esm" });
const { identificationDay, reserveIdentification, releaseIdentification, identificationQuota } = await import(`data:text/javascript;base64,${Buffer.from(module.outputFiles[0].text).toString("base64")}`);
const hash = (value) => createHash("sha256").update(value).digest("hex");

test("identification day resets at NZ midnight in standard time and daylight saving", () => {
  for (const [instant, day] of [
    ["2026-06-30T11:59:59Z", "2026-06-30"], ["2026-06-30T12:00:00Z", "2026-07-01"],
    ["2026-10-06T10:59:59Z", "2026-10-06"], ["2026-10-06T11:00:00Z", "2026-10-07"],
    ["2026-09-26T13:59:59Z", "2026-09-27"], ["2026-09-26T14:00:00Z", "2026-09-27"],
    ["2026-04-04T13:59:59Z", "2026-04-05"], ["2026-04-04T14:00:00Z", "2026-04-05"],
  ]) assert.equal(identificationDay(new Date(instant)), day);
});

test("five daily identifications are enforced per account with real D1", { timeout: 60_000 }, async (t) => {
  let modelCalls = 0;
  let failModel = false;
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "identification-quota-tests" }, bindings: { OPENAI_API_KEY: "test-key" },
    outboundService: async (request) => {
      assert.equal(request.url, "https://api.openai.com/v1/responses");
      modelCalls += 1;
      if (failModel) return Response.json({ error: { message: "Unavailable" } }, { status: 503 });
      return Response.json({ status: "completed", output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify({
        is_fish: true, common_name_nz: "Snapper", scientific_name: "Pagrus auratus", confidence: 0.9,
        other_possibilities: [], visible_clues: "Silver pink scales.", note: "AI suggestion only.",
      }) }] }] });
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
    for (const [id, plan, tokens] of [["first", "free", ["a", "b"]], ["second", "paid", ["c"]]]) {
      await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, plan, created_at, updated_at, email_verified) VALUES (?, ?, 'hash', 'salt', ?, ?, ?, 1)").bind(id, `${id}@example.com`, plan, now, now).run();
      for (const token of tokens) await db.prepare("INSERT INTO account_sessions VALUES (?, ?, ?, ?)").bind(hash(token.repeat(64)), id, now, future).run();
    }
    const call = (path, options) => mf.dispatchFetch(`https://fishing.test${path}`, options);
    const headers = (token) => ({ authorization: `Bearer ${token.repeat(64)}` });
    const identify = (token = "a", type = "image/jpeg", body = new Uint8Array([1, 2, 3])) => call("/v1/fish/identify", { method: "POST", headers: { ...headers(token), "content-type": type }, body });
    const quota = async (token = "a") => (await (await call("/v1/me/permissions", { headers: headers(token) })).json()).fish_identity_quota;
    const reset = () => db.prepare("DELETE FROM account_identification_usage").run();
    await t.test("backfills successful checks made before quota deployment", async () => {
      await db.prepare("INSERT INTO account_events (id, user_id, event_name, feature, platform, occurred_at) VALUES ('legacy-check', 'first', 'fish_identity_used', 'fish_identity', 'android', '2026-10-06T06:00:00.000Z')").run();
      const backfill = readFileSync(new URL("../migrations/0008_backfill_identification_usage_2026_10_06.sql", import.meta.url), "utf8");
      await db.prepare(backfill).run();
      assert.equal((await identificationQuota(db, "first", "2026-10-06")).used, 1);
      assert.equal((await identificationQuota(db, "second", "2026-10-06")).used, 0);
      await reset();
    });
    await t.test("rejects unauthenticated and invalid uploads without using allowance", async () => {
      assert.equal((await identify("d")).status, 401);
      assert.equal((await identify("a", "image/heic")).status, 400);
      assert.equal((await identify("a", "image/jpeg", new Uint8Array())).status, 400);
      assert.equal((await quota()).remaining, 5);
      assert.equal(modelCalls, 0);
    });
    await t.test("allows five results then blocks across sessions before calling OpenAI", async () => {
      for (let used = 1; used <= 5; used++) {
        const response = await identify(used % 2 ? "a" : "b");
        assert.equal(response.status, 200);
        const result = await response.json();
        assert.equal(result.commonName, "Snapper");
        assert.equal(result.fish_identity_quota.remaining, 5 - used);
      }
      const before = modelCalls;
      const response = await identify("b");
      assert.equal(response.status, 429);
      const result = await response.json();
      assert.equal(result.code, "daily_identification_limit");
      assert.equal(result.fish_identity_quota.remaining, 0);
      assert.match(result.error, /midnight New Zealand time/);
      assert.equal(modelCalls, before);
      assert.equal((await quota("b")).used, 5);
      assert.equal((await quota("c")).remaining, 5);
    });
    await t.test("parallel requests cannot exceed five, including paid accounts", async () => {
      await reset();
      const before = modelCalls;
      const responses = await Promise.all(Array.from({ length: 12 }, () => identify("c")));
      assert.equal(responses.filter((response) => response.status === 200).length, 5);
      assert.equal(responses.filter((response) => response.status === 429).length, 7);
      assert.equal(modelCalls - before, 5);
      assert.equal((await quota("c")).remaining, 0);
    });
    await t.test("provider failures release the reserved use", async () => {
      await reset();
      failModel = true;
      assert.equal((await identify()).status, 502);
      assert.equal((await quota()).remaining, 5);
      failModel = false;
      assert.equal((await identify()).status, 200);
      assert.equal((await quota()).remaining, 4);
    });
    await t.test("a new NZ day starts fresh and a late refund belongs to its original day", async () => {
      await reset();
      const yesterday = "2020-01-01";
      for (let count = 0; count < 5; count++) assert.ok(await reserveIdentification(db, "first", yesterday));
      assert.equal(await reserveIdentification(db, "first", yesterday), null);
      assert.equal((await quota()).remaining, 5);
      assert.ok(await reserveIdentification(db, "first"));
      await releaseIdentification(db, "first", yesterday);
      assert.equal((await identificationQuota(db, "first", yesterday)).remaining, 1);
      assert.equal((await quota()).remaining, 4);
    });
    await t.test("account deletion also removes allowance records", async () => {
      const response = await call("/v1/me", { method: "DELETE", headers: { ...headers("a"), "content-type": "application/json" }, body: JSON.stringify({ confirm: true }) });
      assert.equal(response.status, 200);
      assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM account_identification_usage WHERE user_id = 'first'").first()).count, 0);
    });
  } finally { await mf.dispose(); }
});
