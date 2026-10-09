import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { bundleWorker, loadWorker } from "./load-worker.mjs";

// The golden link also used by the Android and iPhone tests: Takapuna Beach, Sunday 11 October 2026, 6-8 PM.
const golden = "eyJ2IjoxLCJuIjoiVGFrYXB1bmEgQmVhY2giLCJhIjoiQXVja2xhbmQiLCJiIjowLCJzIjoxNzkxNjk0ODAwLCJlIjoxNzkxNzAyMDAwLCJsYSI6LTM2Ljc4NzEsImxvIjoxNzQuNzcwNSwibyI6WyLwn5mCIiwiT2theSJdLCJmIjpbIvCfl5PvuI8iLCJQbGFubmluZyBmb3JlY2FzdCJdLCJyIjoiTG93ZXIgZGlzY29tZm9ydCIsInQiOjE3OTE2MDg0MDAsImMiOltbIldpbmQiLCIxMiBrbS9oIMK3IGd1c3QgMjAgwrcgU1ciLCLwn5iMIiwiQ29tZm9ydGFibGUiXSxbIlRpZGUiLCJOZXh0IGhpZ2ggNzoxMiBQTSDCtyAyLjQgbSBDRFxuQXVja2xhbmQgKFdhaXRlbWF0xIEpIiwi8J-VkiIsIlRpZGUgdGltaW5nIl1dfQ";
const idPattern = /^[2-9A-HJ-NP-Za-km-z]{8}$/;

test("short links for shared windows, with real D1", { timeout: 60_000 }, async (t) => {
  const worker = await loadWorker();
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "share-short-link-tests" }, bindings: { AUTH_BASE_URL: "https://fishing.test", PUBLIC_DEVELOPER_NAME: "Tristan", PUBLIC_SUPPORT_EMAIL: "support@example.com" },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
    }
    let counter = 0;
    const call = (path, options = {}) => mf.dispatchFetch(`https://fishing.test${path}`, options);
    // Each request comes from its own network address unless a test says otherwise, so rate limits do not leak between tests.
    const create = (token, address = `198.51.100.${++counter}`, raw) => call("/v1/share", {
      method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": address }, body: raw ?? JSON.stringify({ token }),
    });

    await t.test("a posted window gets a short link that shows the window", async () => {
      const response = await create(golden);
      assert.equal(response.status, 201);
      const made = await response.json();
      assert.match(made.id, idPattern);
      assert.equal(made.url, `https://fishing.test/w/${made.id}`);
      const days = (Date.parse(made.expires_at) - Date.now()) / 86_400_000;
      assert.ok(days > 59.9 && days < 60.1, `expires in ${days} days`);
      assert.ok(made.url.length < 45, made.url);

      const page = await call(`/w/${made.id}`);
      assert.equal(page.status, 200);
      const html = await page.text();
      for (const text of ["Takapuna Beach", "Sunday 11 October", "6:00 PM – 8:00 PM", "Okay", "Next high 7:12 PM · 2.4 m CD<br>Auckland (Waitematā)"]) assert.ok(html.includes(text), text);
      assert.ok(html.includes(`<link rel="canonical" href="https://fishing.test/w/${made.id}">`));
      assert.ok(html.includes(`data-open-ios="nz.fishingnz.catchcheck://w/${made.id}"`));
      assert.ok(html.includes(`intent://fishing.test/w/${made.id}#Intent;scheme=https;package=nz.fishingnz.app`));
      assert.ok(!html.includes(golden), "the long token is not repeated on the short page");

      const read = await call(`/v1/share/${made.id}`);
      assert.equal(read.status, 200);
      assert.deepEqual(await read.json(), { token: golden });
      assert.equal((await call(`/w/${made.id}`, { method: "HEAD" })).status, 200);
    });

    await t.test("the long link keeps working beside it", async () => {
      assert.equal((await call(`/w/${golden}`)).status, 200);
    });

    await t.test("bad requests are refused and stored nothing", async () => {
      const before = (await db.prepare("SELECT COUNT(*) AS count FROM shared_windows").first()).count;
      const bad = [
        ["{}", 400], ['{"token":42}', 400], ['{"token":"not-a-window"}', 400], ["not json", 400], ["[]", 400],
        [JSON.stringify({ token: Buffer.from(JSON.stringify({ v: 2, n: "A", a: "B", s: 1791694800, e: 1791702000 })).toString("base64url") }), 400],
        [JSON.stringify({ token: "A".repeat(20_000) }), 413],
      ];
      for (const [raw, status] of bad) {
        const response = await create(undefined, undefined, raw);
        // The body limit applies to a declared length; Miniflare may omit it, in which case the reader's own limit answers 400.
        assert.ok(response.status === status || (status === 413 && response.status === 400), `${raw.slice(0, 30)} -> ${response.status}`);
        assert.ok(["invalid_window", "too_large"].includes((await response.json()).code));
      }
      assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM shared_windows").first()).count, before);
      assert.equal((await call("/v1/share")).status, 405);
      assert.equal((await call("/v1/share/abcdefgh", { method: "POST" })).status, 405);
    });

    await t.test("one network address can make 30 links an hour", async () => {
      const address = "203.0.113.50";
      for (let index = 0; index < 30; index++) assert.equal((await create(golden, address)).status, 201, `share ${index + 1}`);
      const limited = await create(golden, address);
      assert.equal(limited.status, 429);
      assert.equal((await limited.json()).code, "rate_limited");
      assert.ok(Number(limited.headers.get("retry-after")) > 0);
      assert.equal((await create(golden, "203.0.113.51")).status, 201, "another address is unaffected");
    });

    await t.test("ids do not repeat", async () => {
      const ids = new Set();
      for (let index = 0; index < 25; index++) ids.add((await (await create(golden)).json()).id);
      assert.equal(ids.size, 25);
    });

    await t.test("missing, expired and look-alike ids get a friendly page", async () => {
      await db.prepare("INSERT INTO shared_windows (id, token, created_at, expires_at) VALUES ('Expired2', ?, ?, ?)")
        .bind(golden, new Date(Date.now() - 90 * 86_400_000).toISOString(), new Date(Date.now() - 1000).toISOString()).run();
      for (const id of ["Expired2", "AbsentX2"]) {
        const page = await call(`/w/${id}`);
        assert.equal(page.status, 404, id);
        assert.ok((await page.text()).includes("This shared window is no longer available"), id);
        const read = await call(`/v1/share/${id}`);
        assert.equal(read.status, 404, id);
        assert.equal((await read.json()).code, "not_found");
      }
      // Eight characters, but with a character the ids never use: treated as a (damaged) long link, not looked up.
      const damaged = await call("/w/AbsentI0");
      assert.equal(damaged.status, 404);
      assert.ok((await damaged.text()).includes("This link can't be opened"));
      assert.equal((await call("/v1/share/AbsentI0")).status, 404);
    });

    await t.test("a stored value that is no longer a valid window is not drawn", async () => {
      await db.prepare("INSERT INTO shared_windows (id, token, created_at, expires_at) VALUES ('Broken22', 'garbage', ?, ?)")
        .bind(new Date().toISOString(), new Date(Date.now() + 86_400_000).toISOString()).run();
      assert.equal((await call("/w/Broken22")).status, 404);
    });

    await t.test("the daily cleanup deletes expired links and keeps live ones", async () => {
      const live = (await (await create(golden)).json()).id;
      try { await worker.scheduled({ scheduledTime: Date.now() }, { RULES_DB: db }); }
      catch (error) { assert.match(String(error), /MPI rules refresh failed|fetch|network/i); }
      assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM shared_windows WHERE id = 'Expired2'").first()).count, 0);
      assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM shared_windows WHERE id = ?").bind(live).first()).count, 1);
    });

    await t.test("the privacy policy says what is stored and for how long", async () => {
      const html = await (await call("/privacy")).text();
      for (const text of ["Sharing a fishing window", "60 days", "does not include your name, email, account, location or device identifier"]) assert.ok(html.includes(text), text);
    });
  } finally {
    await mf.dispose();
  }
});
