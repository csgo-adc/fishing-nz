import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { bundleWorker } from "./load-worker.mjs";

const hash = (value) => createHash("sha256").update(value).digest("hex");

test("trip and catch log sync, with real D1", { timeout: 60_000 }, async (t) => {
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "sync-tests" }, bindings: { AUTH_BASE_URL: "https://fishing.test", PUBLIC_DEVELOPER_NAME: "Tristan", PUBLIC_SUPPORT_EMAIL: "support@example.com" },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
    }
    const now = new Date().toISOString();
    const future = new Date(Date.now() + 86_400_000).toISOString();
    const sessions = { alice: "a".repeat(64), bob: "b".repeat(64) };
    for (const [id, session] of Object.entries(sessions)) {
      await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, display_name, created_at, updated_at, email_verified) VALUES (?, ?, 'hash', 'salt', 'Fisher', ?, ?, 1)").bind(id, `${id}@example.com`, now, now).run();
      await db.prepare("INSERT INTO account_sessions VALUES (?, ?, ?, ?)").bind(hash(session), id, now, future).run();
    }
    const call = (method, body, token = sessions.alice) => mf.dispatchFetch("https://fishing.test/v1/me/sync", {
      method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body),
    });
    const t0 = 1_792_300_000;
    const snapper = { id: "7d0f3c1e-2a4b-4c55-9b1e-0f6b8e2d9a10", caught_at: t0, species: "Snapper", length_cm: 32.5, kept: true, count: 1, conditions: {} };

    await t.test("needs a signed-in account", async () => {
      const response = await call("GET", undefined, "c".repeat(64));
      assert.equal(response.status, 401);
    });

    await t.test("stores items and returns the merged set", async () => {
      const response = await call("POST", { items: [
        { kind: "trips", id: "trips", payload: { saved: ["token-a"], active: "token-a" }, updated_at: t0, deleted: false },
        { kind: "catch", id: snapper.id, payload: snapper, updated_at: t0, deleted: false },
      ] });
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.equal(body.items.length, 2);
      assert.deepEqual(body.items.find((item) => item.kind === "catch").payload, snapper);
      assert.equal(body.items.find((item) => item.kind === "trips").payload.active, "token-a");
    });

    await t.test("the newest change wins, including deletions", async () => {
      // An older copy from another device does not overwrite the newer one.
      await call("POST", { items: [{ kind: "catch", id: snapper.id, payload: { ...snapper, species: "Old" }, updated_at: t0 - 60, deleted: false }] });
      let items = (await (await call("GET")).json()).items;
      assert.equal(items.find((item) => item.kind === "catch").payload.species, "Snapper");
      // A later deletion does.
      await call("POST", { items: [{ kind: "catch", id: snapper.id, payload: snapper, updated_at: t0 + 60, deleted: true }] });
      items = (await (await call("GET")).json()).items;
      assert.equal(items.find((item) => item.kind === "catch").deleted, true);
    });

    await t.test("accounts never see each other's items", async () => {
      const items = (await (await call("GET", undefined, sessions.bob)).json()).items;
      assert.deepEqual(items, []);
    });

    await t.test("refuses malformed or oversized items and stores nothing", async () => {
      const before = (await db.prepare("SELECT COUNT(*) AS count FROM user_sync_items").first()).count;
      for (const items of [
        "not a list",
        [{ kind: "photos", id: "x", payload: {}, updated_at: t0, deleted: false }],
        [{ kind: "catch", id: "bad id!", payload: {}, updated_at: t0, deleted: false }],
        [{ kind: "catch", id: "x", payload: "text", updated_at: t0, deleted: false }],
        [{ kind: "catch", id: "x", payload: {}, updated_at: 5, deleted: false }],
        [{ kind: "catch", id: "x", payload: { notes: "x".repeat(5_000) }, updated_at: t0, deleted: false }],
        [{ kind: "catch", id: "x", payload: {}, updated_at: t0, deleted: false }, { kind: "catch", id: "x", payload: {}, updated_at: t0, deleted: false }],
      ]) assert.equal((await call("POST", { items })).status, 400, JSON.stringify(items).slice(0, 80));
      assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM user_sync_items").first()).count, before);
    });

    await t.test("a large log is merged in one request", async () => {
      const many = Array.from({ length: 600 }, (_, index) => ({ kind: "catch", id: `c${index}`, payload: { ...snapper, id: `c${index}` }, updated_at: t0, deleted: false }));
      const response = await call("POST", { items: many }, sessions.bob);
      assert.equal(response.status, 200);
      assert.equal((await response.json()).items.length, 600);
    });

    await t.test("turning sync off can delete the synced copy", async () => {
      assert.deepEqual(await (await call("DELETE")).json(), { deleted: true });
      assert.deepEqual((await (await call("GET")).json()).items, []);
      assert.equal((await (await call("GET", undefined, sessions.bob)).json()).items.length, 600);
    });

    await t.test("account deletion removes synced items", async () => {
      const response = await mf.dispatchFetch("https://fishing.test/v1/me", {
        method: "DELETE", headers: { authorization: `Bearer ${sessions.bob}`, "content-type": "application/json" }, body: JSON.stringify({ confirm: true }),
      });
      assert.equal(response.status, 200);
      assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM user_sync_items WHERE user_id = 'bob'").first()).count, 0);
    });
  } finally {
    await mf.dispose();
  }
});
