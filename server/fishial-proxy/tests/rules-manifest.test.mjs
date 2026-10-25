import assert from "node:assert/strict";
import test from "node:test";
import { loadWorker } from "./load-worker.mjs";

const worker = await loadWorker();

test("the rules manifest lists each area's version and the species pictures", async () => {
  const rows = [
    { area_id: "central", source_url: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/central-fishing-rules",
      reviewed_at: "04.12.25", content_sha256: "a".repeat(64), crawl_status: "success" },
    { area_id: "southland", source_url: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/southland-fishing-rules",
      reviewed_at: "15.07.26", content_sha256: "b".repeat(64), crawl_status: "source_changed" },
    { area_id: "old-area", source_url: "https://example.com", reviewed_at: null, content_sha256: "c".repeat(64), crawl_status: null },
  ];
  const database = {
    prepare(sql) {
      const statement = {
        bind() { return statement; },
        async all() { return { results: /FROM mpi_fishing_rules r/.test(sql) ? rows : [] }; },
        async first() { return null; },
        async run() { return { success: true, meta: {} }; },
      };
      return statement;
    },
    async batch(statements) { return Promise.all(statements.map((statement) => statement.run())); },
  };
  const response = await worker.fetch(new Request("https://fishing.fishnz.space/v1/rules/manifest"), { RULES_DB: database }, { waitUntil() {} });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body.rules, [
    { area_id: "central", version: "a".repeat(32), reviewed_at: "04.12.25", needs_review: false },
    { area_id: "southland", version: `review-${"b".repeat(32)}`, reviewed_at: null, needs_review: true },
  ]);
  assert.equal(body.pictures_path, "/species/");
  const snapper = body.species.species.find((item) => item.id === "snapper");
  assert.deepEqual(snapper.match, ["snapper"]);
  assert.equal(snapper.pictures[0].file, "snapper.webp");
  assert.match(snapper.pictures[0].sha256, /^[a-f0-9]{64}$/);
  assert.match(body.species.version, /^[a-f0-9]{16}$/);
});
