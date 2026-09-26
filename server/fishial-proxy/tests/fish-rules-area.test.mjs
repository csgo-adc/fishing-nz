import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = readFileSync(resolve(project, "src", "index.ts"), "utf8");
const javascript = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText;
const { default: worker } = await import(`data:text/javascript;base64,${Buffer.from(javascript).toString("base64")}`);

test("fish identification attaches limits only for a selected MPI area", async () => {
  const originalFetch = globalThis.fetch;
  const originalCrypto = globalThis.crypto;
  let mpiHtml = `<html><head><title>Central fishing rules</title></head><body>${"Current MPI rules. ".repeat(40)}</body></html>`;
  globalThis.crypto ??= webcrypto;
  globalThis.fetch = async (url) => {
    if (String(url).startsWith("https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/")) {
      return new Response(mpiHtml, { status: 200, headers: { "content-type": "text/html" } });
    }
    assert.equal(url, "https://api.openai.com/v1/responses");
    return Response.json({
      status: "completed",
      output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify({
        is_fish: true, common_name_nz: "Snapper", scientific_name: "Pagrus auratus", confidence: 0.9,
        other_possibilities: [], visible_clues: "Pink body", note: "Check identification",
      }) }] }],
    });
  };
  const queries = [];
  const statusWrites = [];
  let crawlStatus = null;
  const ruleRow = {
    area_id: "central", area_name: "Central",
    source_url: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/central-fishing-rules",
    reviewed_at: "04.12.25", page_text: "Previously imported limits", page_html: "<p>Previously imported limits</p>",
    sections_json: JSON.stringify([{ heading: "Finfish", text: "Previously imported limits" }]),
    tables_json: JSON.stringify([[["Finfish species", "Maximum daily limit per active fisher", "Min fish length (cm)"], ["Snapper", "10", "27"]]]),
  };
  const database = {
    prepare(sql) {
      queries.push(sql);
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.includes("FROM account_sessions")) return { id: "tester", plan: "free", email_verified: 1 };
              if (sql.includes("SELECT content_sha256, reviewed_at FROM mpi_fishing_rules")) return { content_sha256: "0".repeat(64), reviewed_at: ruleRow.reviewed_at };
              if (sql.includes("FROM mpi_fishing_rules")) return { ...ruleRow, crawl_status: crawlStatus };
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
    assert.equal(selected.fishRules[0].dailyLimit, "10");
    assert.equal(selected.fishRules[0].minimumSizeLabel, "Min fish length (cm)");
    assert.equal(queries.filter((query) => query.includes("FROM mpi_fishing_rules")).length, 1);

    crawlStatus = "source_changed";
    const changed = await identify("central");
    assert.equal(changed.areaId, "central");
    assert.equal(changed.rulesNeedsReview, true);
    assert.equal(changed.rulesReviewedAt, null);
    assert.deepEqual(changed.fishRules, []);
    assert.equal(changed.rulesSourceUrl, ruleRow.source_url);

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
  } finally {
    globalThis.fetch = originalFetch;
    if (originalCrypto === undefined) delete globalThis.crypto;
  }
});
