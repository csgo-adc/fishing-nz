import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { bundleWorker } from "./load-worker.mjs";

const ADMIN = "admin-token-".padEnd(40, "r");
const page = readFileSync(new URL("./fixtures/mpi-chatham-rise.html", import.meta.url), "utf8");
const expected = JSON.parse(readFileSync(new URL("./fixtures/mpi-chatham-rise.expected.json", import.meta.url), "utf8"));

async function loadParser() {
  const result = await build({ entryPoints: [new URL("../src/rules-parse.ts", import.meta.url).pathname], bundle: true, write: false, format: "esm", platform: "neutral", mainFields: ["module", "main"] });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`);
}

test("the TypeScript parser reads a real MPI page exactly as tools/crawl_mpi_rules.py does", async () => {
  const { parseRulesPage } = await loadParser();
  const parsed = parseRulesPage(page, "Chatham Rise");
  assert.deepEqual(parsed.tables, expected.tables);
  assert.deepEqual(parsed.sections, expected.sections);
  assert.equal(parsed.title, expected.title);
  assert.equal(parsed.reviewedAt, "15.07.26");
  assert.equal(parsed.sourceUrl, "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/chatham-rise-area-recreational-fishing-rules");
  assert.doesNotMatch(parsed.pageText, /not rules|Menu/);
});

test("the parser keeps spans under the right headings and stops on a broken table", async () => {
  const { parseStructuredRules, parseFragment: parse, RulesPageError } = await loadParser();
  const root = parse(`<main><table>
    <tr><th>Species</th><th>Daily limit</th><th>Minimum length</th></tr>
    <tr><td>Groper (Kermadecs)</td><td rowspan="2">5 combined; no more than 3 kingfish</td><td>—</td></tr>
    <tr><td>Kingfish (Kermadecs)</td><td>75</td></tr>
    <tr><td colspan="3">** Read the subarea note.</td></tr></table></main>`).querySelector("main");
  const { tables } = parseStructuredRules(root, "Auckland");
  assert.deepEqual(tables[0][2], ["Kingfish (Kermadecs)", "5 combined; no more than 3 kingfish", "75"]);
  assert.deepEqual(tables[0][3], ["** Read the subarea note."]);
  const broken = parse("<main><table><tr><th>A</th><th>B</th><th>C</th></tr><tr><td>x</td><td>1</td></tr></table></main>").querySelector("main");
  assert.throws(() => parseStructuredRules(broken, "Area"), (error) => error instanceof RulesPageError && /inconsistent row widths/.test(error.message));
});

test("CMS rules update: overview, preview with changes, publish, and reminder emails, with real D1", { timeout: 90_000 }, async (t) => {
  const emails = [];
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "rules-admin-tests" },
    bindings: { ACCOUNT_ADMIN_TOKEN: ADMIN, PUBLIC_SUPPORT_EMAIL: "owner@example.com", RESEND_API_KEY: "re_test", ACCOUNT_EMAIL_FROM: "Fishdays <a@example.com>", CMS_BASE_URL: "https://cms.example.com" },
    outboundService: async (request) => {
      if (request.url.startsWith("https://api.resend.com/")) { emails.push(await request.json()); return Response.json({ id: "email" }); }
      // MPI's bot protection answers the daily check with an interstitial.
      return new Response(`<html><head><META NAME="robots" CONTENT="noindex,nofollow"></head><body></body></html>`, { headers: { "content-type": "text/html" } });
    },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
    }
    // Saved rules from 26 September, with MPI's list naming a change from 1 November 2026.
    const oldTables = JSON.parse(JSON.stringify(expected.tables));
    oldTables[1][1][1] = "10";
    const oldSections = [{ heading: "RECENT RULE CHANGES", text: "From 1 November 2026: octopus daily limit reduced to 1.\nFrom 1 March 2027: new closure at Waitangi." }, ...expected.sections];
    await db.prepare(`INSERT INTO mpi_fishing_rules (area_id, area_name, source_url, page_title, reviewed_at, fetched_at, content_sha256, page_text, sections_json, tables_json, page_html)
      VALUES ('chatham-rise', 'Chatham Rise', 'https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/chatham-rise-area-recreational-fishing-rules', ?, '01.07.26', '2026-09-26T07:32:51+00:00', ?, 'text', ?, ?, '')`)
      .bind(expected.title, "a".repeat(64), JSON.stringify(oldSections), JSON.stringify(oldTables)).run();

    const admin = (path, method = "GET", body) => mf.dispatchFetch(`https://fishing.test/v1/admin/rules${path}`, {
      method, headers: { "x-account-admin-token": ADMIN, ...(body ? { "content-type": "application/json" } : {}) }, body: body ? JSON.stringify(body) : undefined,
    });

    await t.test("needs the admin token", async () => {
      assert.equal((await mf.dispatchFetch("https://fishing.test/v1/admin/rules")).status, 401);
    });

    await t.test("overview lists every area and says why one may be out of date", async () => {
      const body = await (await admin("")).json();
      assert.equal(body.areas.length, 8);
      const chatham = body.areas.find((area) => area.area_id === "chatham-rise");
      assert.equal(chatham.reviewed_at, "01.07.26");
      assert.ok(chatham.reasons.some((reason) => /1 November 2026.*octopus/.test(reason)));
      assert.ok(chatham.reasons.some((reason) => /Saved \d+ days ago/.test(reason)));
      assert.deepEqual(chatham.upcoming_changes.map((change) => change.date), ["2027-03-01"]);
      assert.match(body.areas.find((area) => area.area_id === "central").reasons[0], /No rules saved/);
      assert.equal(body.reminders.email, "owner@example.com");
      assert.equal(body.reminders.enabled, true);
    });

    await t.test("preview shows the changed limits without saving anything", async () => {
      const response = await admin("/preview", "POST", { area_id: "chatham-rise", html: page });
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.equal(body.reviewed_at, "15.07.26");
      assert.equal(body.tables, expected.tables.length);
      assert.equal(body.same_as_saved, false);
      const blueCod = body.changes.find((change) => change.species === "Blue cod");
      assert.equal(blueCod.kind, "changed");
      assert.match(blueCod.before, /^10 \|/);
      assert.match(blueCod.after, /^5 \|/);
      const saved = await db.prepare("SELECT reviewed_at FROM mpi_fishing_rules WHERE area_id = 'chatham-rise'").first();
      assert.equal(saved.reviewed_at, "01.07.26");
    });

    await t.test("refuses another area's page, a page that isn't rules, and an unknown area", async () => {
      let response = await admin("/preview", "POST", { area_id: "central", html: page });
      assert.equal(response.status, 422);
      assert.match((await response.json()).error, /This page is for Chatham Rise, not Central/);
      response = await admin("/preview", "POST", { area_id: "central", html: `<html><body><h1>Hi</h1><p>${"x".repeat(600)}</p></body></html>` });
      assert.equal(response.status, 422);
      assert.match((await response.json()).error, /No rule tables/);
      assert.equal((await admin("/preview", "POST", { area_id: "nowhere", html: page })).status, 400);
    });

    await t.test("publish saves the area, changes the manifest version the apps sync from, and records it", async () => {
      const manifest = async () => (await (await mf.dispatchFetch("https://fishing.test/v1/rules/manifest")).json()).rules.find((row) => row.area_id === "chatham-rise");
      const before = await manifest();
      const response = await admin("/publish", "POST", { area_id: "chatham-rise", html: page });
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.equal(body.history[0].area_id, "chatham-rise");
      assert.ok(body.history[0].changes > 0);
      const after = await manifest();
      assert.notEqual(after.version, before.version);
      assert.equal(after.reviewed_at, "15.07.26");
      const rules = await (await mf.dispatchFetch("https://fishing.test/v1/rules?area=chatham-rise")).json();
      assert.deepEqual(rules.rules[0].tables, expected.tables);
      assert.equal((await (await admin("/preview", "POST", { area_id: "chatham-rise", html: page })).json()).same_as_saved, true);
    });

    await t.test("reminder settings are checked and saved", async () => {
      assert.equal((await admin("/reminders", "PUT", { enabled: true, email: "nope", stale_days: 30 })).status, 400);
      assert.equal((await admin("/reminders", "PUT", { enabled: true, email: "rules@example.com", stale_days: 2 })).status, 400);
      const response = await admin("/reminders", "PUT", { enabled: true, email: "rules@example.com", stale_days: 30 });
      assert.equal((await response.json()).reminders.email, "rules@example.com");
    });

    await t.test("the daily cron emails a reminder once, and again only when something new is due", async () => {
      // Put back the old copy so a change has started since it was saved.
      await db.prepare("UPDATE mpi_fishing_rules SET fetched_at = '2026-09-26T07:32:51+00:00', sections_json = ? WHERE area_id = 'chatham-rise'").bind(JSON.stringify(oldSections)).run();
      const worker = await mf.getWorker();
      const at = Date.parse("2026-11-11T16:00:00Z");
      await worker.scheduled({ scheduledTime: new Date(at), cron: "0 16 * * *" }).catch(() => {});
      assert.equal(emails.length, 1);
      assert.deepEqual(emails[0].to, ["rules@example.com"]);
      assert.equal(emails[0].subject, "Fishing rules to check: Chatham Rise");
      assert.match(emails[0].text, /1 November 2026/);
      assert.match(emails[0].text, /https:\/\/cms\.example\.com\/admin\/rules/);
      await worker.scheduled({ scheduledTime: new Date(at + 86_400_000), cron: "0 16 * * *" }).catch(() => {});
      assert.equal(emails.length, 1, "the same reasons are not sent again within a week");
      await worker.scheduled({ scheduledTime: new Date(at + 8 * 86_400_000), cron: "0 16 * * *" }).catch(() => {});
      assert.equal(emails.length, 2, "a week later it reminds again");
      await admin("/reminders", "PUT", { enabled: false, email: "rules@example.com", stale_days: 30 });
      await worker.scheduled({ scheduledTime: new Date(at + 20 * 86_400_000), cron: "0 16 * * *" }).catch(() => {});
      assert.equal(emails.length, 2, "nothing is sent when reminders are off");
    });
  } finally {
    await mf.dispose();
  }
});
