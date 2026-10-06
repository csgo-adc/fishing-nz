import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import test from "node:test";
import { loadWorker } from "./load-worker.mjs";

const worker = await loadWorker();

test("fish identification attaches limits only for a selected MPI area", async () => {
  const originalFetch = globalThis.fetch;
  const originalCrypto = globalThis.crypto;
  let mpiHtml = `<html><head><title>Central fishing rules</title></head><body>${"Current MPI rules. ".repeat(40)}</body></html>`;
  let modelIdentification = {
    is_fish: true, common_name_nz: "Snapper", scientific_name: "Pagrus auratus", confidence: 0.9,
    other_possibilities: [], visible_clues: "Pink body", note: "Check identification",
  };
  globalThis.crypto ??= webcrypto;
  globalThis.fetch = async (url) => {
    if (String(url).startsWith("https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/")) {
      return new Response(mpiHtml, { status: 200, headers: { "content-type": "text/html" } });
    }
    assert.equal(url, "https://api.openai.com/v1/responses");
    return Response.json({
      status: "completed",
      output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify(modelIdentification) }] }],
    });
  };
  const queries = [];
  const statusWrites = [];
  let crawlStatus = null;
  let ruleQueryFails = false;
  const ruleRow = {
    area_id: "central", area_name: "Central",
    source_url: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/central-fishing-rules",
    reviewed_at: "04.12.25", page_text: "Previously imported limits", page_html: "<p>Previously imported limits</p>",
    sections_json: JSON.stringify([{ heading: "Finfish", text: "Previously imported limits" }]),
    tables_json: JSON.stringify([[["Finfish species", "Maximum daily limit per active fisher", "Min fish length (cm)"], ["Snapper", "10", "27"]]]),
  };
  const database = {
    async batch(statements) { return Promise.all(statements.map((statement) => statement.run())); },
    prepare(sql) {
      queries.push(sql);
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.includes("account_identification_usage")) return { used: 1 };
              if (sql.includes("FROM account_sessions")) return { id: "tester", plan: "free", email_verified: 1 };
              if (sql.includes("SELECT content_sha256, reviewed_at FROM mpi_fishing_rules")) return { content_sha256: "0".repeat(64), reviewed_at: ruleRow.reviewed_at };
              if (sql.includes("FROM mpi_fishing_rules")) {
                if (ruleQueryFails) throw new Error("D1 unavailable");
                return { ...ruleRow, crawl_status: crawlStatus };
              }
              throw new Error(`Unexpected query: ${sql}`);
            },
            async all() { return { results: [{ ...ruleRow, crawl_status: crawlStatus }] }; },
            async run() {
              if (sql.includes("INSERT INTO mpi_rules_crawl_status")) statusWrites.push(args);
              return { success: true };
            },
          };
        },
      };
    },
  };
  const env = { OPENAI_API_KEY: "test-key", RULES_DB: database };
  const identify = async (areaHeader) => {
    const headers = {
      authorization: `Bearer ${"a".repeat(64)}`,
      "content-type": "image/jpeg",
      "x-location-lat-lon": "-36.85,174.76",
      "x-location-source": "device",
    };
    if (areaHeader !== undefined) headers["x-fishing-rules-area"] = areaHeader;
    const response = await worker.fetch(new Request("https://example.test/v1/fish/identify", {
      method: "POST", headers, body: new Uint8Array([1, 2, 3]),
    }), env);
    assert.equal(response.status, 200);
    return response.json();
  };
  const lookup = async (area, species) => {
    const url = new URL("https://example.test/v1/fish/rules");
    if (area != null) url.searchParams.set("area", area);
    if (species != null) url.searchParams.set("species", species);
    const response = await worker.fetch(new Request(url), env);
    return { status: response.status, body: await response.json() };
  };

  try {
    const unselected = await identify();
    assert.equal(unselected.commonName, "Snapper");
    assert.equal(unselected.areaId, null);
    assert.equal(unselected.areaSelectionRequired, true);
    assert.equal(unselected.areaIsEstimated, false);
    assert.equal(unselected.rulesSourceUrl, null);
    assert.deepEqual(unselected.fishRules, []);
    assert.equal(queries.filter((query) => query.includes("FROM mpi_fishing_rules")).length, 0);

    const invalid = await identify("not-an-mpi-area");
    assert.equal(invalid.areaId, null);
    assert.deepEqual(invalid.fishRules, []);

    const selected = await identify("central");
    assert.equal(selected.areaId, "central");
    assert.equal(selected.areaName, "Central");
    assert.equal(selected.areaSelectionRequired, false);
    assert.equal(selected.areaIsEstimated, false);
    const refreshed = await lookup("central", "Snapper");
    assert.equal(refreshed.status, 200);
    assert.equal(refreshed.body.areaId, "central");
    assert.deepEqual(refreshed.body.fishRules, selected.fishRules);
    const unmatched = await lookup("central", "Spotty");
    assert.equal(unmatched.status, 200);
    assert.deepEqual(unmatched.body.fishRules, []);
    assert.match(unmatched.body.rulesSourceUrl, /central-fishing-rules/);
    assert.equal((await lookup("unknown", "Snapper")).status, 400);
    assert.equal(selected.fishRules[0].dailyLimit, "10");
    assert.equal(selected.fishRules[0].minimumSizeLabel, "Min fish length (cm)");
    assert.equal(queries.filter((query) => query.includes("FROM mpi_fishing_rules")).length, 3);

    const validTablesJSON = ruleRow.tables_json;
    ruleRow.tables_json = "{bad json";
    const malformedLookup = await lookup("central", "Snapper");
    assert.equal(malformedLookup.status, 502);
    assert.match(malformedLookup.body.error, /Please try again/);
    const malformedIdentify = await identify("central");
    assert.equal(malformedIdentify.commonName, "Snapper");
    assert.deepEqual(malformedIdentify.fishRules, []);
    ruleRow.tables_json = validTablesJSON;

    ruleQueryFails = true;
    const failedLookup = await lookup("central", "Snapper");
    assert.equal(failedLookup.status, 502);
    const failedIdentify = await identify("central");
    assert.equal(failedIdentify.commonName, "Snapper");
    assert.deepEqual(failedIdentify.fishRules, []);
    ruleQueryFails = false;

    ruleRow.source_url += "/";
    const trailingSlash = await identify("central");
    assert.equal(trailingSlash.rulesNeedsReview, false);
    assert.equal(trailingSlash.fishRules.length, 1);
    ruleRow.source_url = ruleRow.source_url.replace(/\/+$/, "");

    // An incorrectly labelled cached row must never put Central limits under an Auckland heading.
    const mismatched = await identify("auckland-kermadec");
    assert.equal(mismatched.areaId, "auckland-kermadec");
    assert.equal(mismatched.areaName, "Auckland / Kermadec");
    assert.equal(mismatched.rulesNeedsReview, true);
    assert.deepEqual(mismatched.fishRules, []);
    assert.match(mismatched.rulesSourceUrl, /auckland-kermadec-fishing-rules$/);
    const mismatchedLookup = await lookup("auckland-kermadec", "Snapper");
    assert.equal(mismatchedLookup.status, 200);
    assert.equal(mismatchedLookup.body.rulesNeedsReview, true);
    assert.deepEqual(mismatchedLookup.body.fishRules, []);
    const mismatchedRulesResponse = await worker.fetch(new Request("https://example.test/v1/rules?area=auckland-kermadec"), env);
    assert.equal(mismatchedRulesResponse.status, 200);
    const mismatchedRulesPage = (await mismatchedRulesResponse.json()).rules[0];
    assert.equal(mismatchedRulesPage.area_id, "auckland-kermadec");
    assert.equal(mismatchedRulesPage.area_name, "Auckland / Kermadec");
    assert.equal(mismatchedRulesPage.needsReview, true);
    assert.deepEqual(mismatchedRulesPage.tables, []);
    assert.deepEqual(mismatchedRulesPage.sections, []);
    assert.match(mismatchedRulesPage.source_url, /auckland-kermadec-fishing-rules$/);

    crawlStatus = "source_changed";
    const changed = await identify("central");
    assert.equal(changed.areaId, "central");
    assert.equal(changed.rulesNeedsReview, true);
    assert.equal(changed.rulesReviewedAt, null);
    assert.deepEqual(changed.fishRules, []);
    assert.equal(changed.rulesSourceUrl, ruleRow.source_url);
    const changedLookup = await lookup("central", "Snapper");
    assert.equal(changedLookup.body.rulesNeedsReview, true);
    assert.deepEqual(changedLookup.body.fishRules, []);

    const rulesResponse = await worker.fetch(new Request("https://example.test/v1/rules?area=central"), env);
    assert.equal(rulesResponse.status, 200);
    const rulesPage = (await rulesResponse.json()).rules[0];
    assert.equal(rulesPage.needsReview, true);
    assert.deepEqual(rulesPage.sections, []);
    assert.deepEqual(rulesPage.tables, []);
    assert.doesNotMatch(rulesPage.page_text, /Previously imported limits/);

    await worker.scheduled({ scheduledTime: Date.now() }, env);
    assert.equal(statusWrites.length, 1);
    assert.equal(statusWrites[0][4], "source_changed");
    assert.equal(queries.some((query) => query.includes("INSERT INTO mpi_fishing_rules")), false);

    mpiHtml = `<html><body><p>Last reviewed: 04.12.25</p>${"Current MPI rules. ".repeat(40)}</body></html>`;
    await worker.scheduled({ scheduledTime: Date.now() }, env);
    assert.equal(statusWrites.at(-1)[4], "success");

    const preflight = await worker.fetch(new Request("https://example.test/v1/fish/identify", { method: "OPTIONS" }), env);
    assert.match(preflight.headers.get("access-control-allow-headers"), /x-fishing-rules-area/);

    crawlStatus = null;
    ruleRow.tables_json = JSON.stringify([[
      ["Finfish species", "Maximum daily limit per active fisher", "Min fish length (cm)", "Min set net mesh size (mm)", "Min drag net mesh size (mm)"],
      ["Snapper (Auckland West)**", "10", "27", "125", "125"],
      ["** Note the difference between Snapper (Auckland West) and Snapper (Auckland East - SNA1)."],
      ["Snapper guidance", "", "", "", ""],
      ["Kingfish (Kermadecs)", "75", "100", "100"], // A merged MPI cell shifts values left.
      ["Kingfish (Auckland West)", "3", "75", "100", "100"],
      ["Blue cod", "6", "33", "100", "100"],
      ["** Where permitted, blue cod headed state minimum length is 24cm."],
    ]]);
    const snapper = await lookup("central", "Snapper");
    assert.equal(snapper.status, 200);
    assert.deepEqual(snapper.body.fishRules.map((rule) => rule.species), ["Snapper (Auckland West)**"]);
    const kingfish = await lookup("central", "Kingfish");
    assert.deepEqual(kingfish.body.fishRules.map((rule) => rule.species), ["Kingfish (Auckland West)"]);
    assert.equal(kingfish.body.fishRules[0].dailyLimit, "3");
    const blueCod = await lookup("central", "Blue cod");
    assert.deepEqual(blueCod.body.fishRules.map((rule) => rule.species), ["Blue cod"]);

    ruleRow.tables_json = JSON.stringify([[
      ["Finfish species", "Finfish species", "Maximum daily limit per active fisher", "Min fish length (cm)"],
      ["Blue cod", "Kaikōura Marine Area", "6", "33"],
      ["Blue cod", "Everywhere else within Kaikōura", "10", "33"],
    ]]);
    const subareaCod = await lookup("central", "Blue cod");
    assert.deepEqual(subareaCod.body.fishRules.map((rule) => rule.species), [
      "Blue cod — Kaikōura Marine Area", "Blue cod — Everywhere else within Kaikōura",
    ]);
    assert.deepEqual(subareaCod.body.fishRules.map((rule) => rule.dailyLimit), ["6", "10"]);

    ruleRow.tables_json = JSON.stringify([[ ["Finfish species", "Min mesh size (mm)"], ["Snapper", "125"] ]]);
    const meshOnly = await lookup("central", "Snapper");
    assert.deepEqual(meshOnly.body.fishRules, [], "net mesh size must not appear as the minimum fish length");

    ruleRow.tables_json = JSON.stringify([[
      ["Finfish species", "Maximum daily limit per active fisher", "Min fish length (cm)"],
      ["Snapper", "5", "—"],
    ]]);
    const unspecifiedSize = await lookup("central", "Snapper");
    assert.equal(unspecifiedSize.body.fishRules[0].dailyLimit, "5");
    assert.equal(unspecifiedSize.body.fishRules[0].minimumSize, null);

    modelIdentification = {
      ...modelIdentification,
      common_name_nz: "**Snapper**", scientific_name: "*Pagrus auratus*",
      other_possibilities: ["**Red snapper**"], visible_clues: "### Visible clues\n**Pink** body",
      note: "- **Check** identification",
    };
    const plainText = await identify();
    assert.equal(plainText.commonName, "Snapper");
    assert.equal(plainText.scientificName, "Pagrus auratus");
    assert.deepEqual(plainText.otherPossibilities, ["Red snapper"]);
    assert.equal(plainText.visibleClues, "Visible clues\nPink body");
    assert.equal(plainText.identificationNote, "Check identification");
  } finally {
    globalThis.fetch = originalFetch;
    if (originalCrypto === undefined) delete globalThis.crypto;
  }
});
