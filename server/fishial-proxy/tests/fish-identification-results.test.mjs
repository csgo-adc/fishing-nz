import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import test from "node:test";
import { loadWorker } from "./load-worker.mjs";

const worker = await loadWorker();

test("fish identification always returns a useful result for model responses", async () => {
  const originalFetch = globalThis.fetch;
  const originalCrypto = globalThis.crypto;
  globalThis.crypto ??= webcrypto;
  let modelResult;
  let rulesQueries = 0;
  let prompt = "";
  globalThis.fetch = async (url, options) => {
    assert.equal(url, "https://api.openai.com/v1/responses");
    const request = JSON.parse(options.body);
    assert.equal(request.store, false, "Fish photos must not create stored Responses state");
    prompt = request.input[0].content[0].text;
    assert.ok(request.text.format.schema.required.includes("subject_name"));
    return Response.json({ status: "completed", output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify(modelResult) }] }] });
  };
  const database = {
    prepare(sql) {
      return {
        bind() {
          return {
            async first() {
              if (sql.includes("account_identification_usage")) return { used: 1 };
              if (sql.includes("FROM account_sessions")) return { id: "tester", plan: "free", email_verified: 1 };
              if (sql.includes("FROM mpi_fishing_rules")) { rulesQueries += 1; return null; }
              throw new Error(`Unexpected query: ${sql}`);
            },
            async run() { return { success: true }; },
          };
        },
      };
    },
  };
  const env = { OPENAI_API_KEY: "test-key", RULES_DB: database };
  const identify = async (result, area = "central") => {
    modelResult = result;
    const headers = { authorization: `Bearer ${"a".repeat(64)}`, "content-type": "image/jpeg" };
    if (area) headers["x-fishing-rules-area"] = area;
    const response = await worker.fetch(new Request("https://example.test/v1/fish/identify", {
      method: "POST",
      headers,
      body: new Uint8Array([1, 2, 3]),
    }), env);
    assert.equal(response.status, 200);
    return response.json();
  };

  try {
    const seaStar = await identify({
      is_fish: false, common_name_nz: "", scientific_name: "", confidence: 0.99,
      other_possibilities: [],
      visible_clues: "The image shows a spiny sea star (starfish) on wet rock, with multiple arms and a rough, armoured surface. A shell is also visible near the top.",
      note: "This is not a fish.",
    }, null);
    assert.equal(seaStar.isFish, false);
    assert.equal(seaStar.commonName, "Spiny sea star (starfish)");
    assert.match(seaStar.visibleClues, /multiple arms/);
    assert.deepEqual(seaStar.fishRules, []);
    assert.equal(seaStar.areaSelectionRequired, false);
    assert.equal(rulesQueries, 0);

    const emperor = await identify({
      is_fish: true, common_name_nz: "Unknown fish", scientific_name: "", confidence: 0.35,
      other_possibilities: ["Redthroat emperor (sweetlip)", "Snapper", "Spangled emperor"],
      visible_clues: "Deep-bodied reef fish with orange-red fins.", note: "AI suggestion only.",
    });
    assert.equal(emperor.isFish, true);
    assert.equal(emperor.commonName, "Redthroat emperor (sweetlip)");
    assert.deepEqual(emperor.otherPossibilities, ["Snapper", "Spangled emperor"]);
    assert.equal(emperor.confidence, 0.35);
    assert.equal(emperor.confidenceLevel, "low");

    const snapper = await identify({
      is_fish: true, common_name_nz: "Unknown fish", scientific_name: "", confidence: 0.42,
      other_possibilities: ["Snapper", "Pink snapper (Australian snapper)", "Spangled emperor"],
      visible_clues: "Deep-bodied silvery-pink fish with reddish fins.", note: "AI suggestion only.",
    });
    assert.equal(snapper.commonName, "Snapper");
    assert.deepEqual(snapper.otherPossibilities, ["Pink snapper (Australian snapper)", "Spangled emperor"]);
    assert.match(prompt, /outside|elsewhere/);

    const unidentified = await identify({
      is_fish: false, common_name_nz: "", subject_name: "Crab", scientific_name: "", confidence: 0.8,
      other_possibilities: [], visible_clues: "A shell and claws are visible.", note: "Not a fish.",
    });
    assert.equal(unidentified.commonName, "Crab");
    assert.equal(unidentified.isFish, false);

    const genericSubject = await identify({
      is_fish: false, common_name_nz: "", subject_name: "Not a fish.", scientific_name: "", confidence: 0.96,
      other_possibilities: [],
      visible_clues: "The photo appears to show a spiny sea star (starfish) on wet rock, with multiple arms.",
      note: "This is not a fish.",
    });
    assert.equal(genericSubject.commonName, "Spiny sea star (starfish)");
    assert.equal(genericSubject.isFish, false);

    const unsupported = await worker.fetch(new Request("https://example.test/v1/fish/identify", {
      method: "POST",
      headers: { authorization: `Bearer ${"a".repeat(64)}`, "content-type": "image/heic" },
      body: new Uint8Array([1, 2, 3]),
    }), env);
    assert.equal(unsupported.status, 400);
    assert.match((await unsupported.json()).error, /Export HEIC photos as JPEG/);
  } finally {
    globalThis.fetch = originalFetch;
    if (originalCrypto === undefined) delete globalThis.crypto;
  }
});
