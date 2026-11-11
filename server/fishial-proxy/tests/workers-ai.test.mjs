import assert from "node:assert/strict";
import test from "node:test";
import { build } from "esbuild";

async function loadAdapter() {
  const result = await build({ entryPoints: [new URL("../src/fish-ai/workers-ai.ts", import.meta.url).pathname], bundle: true, write: false, format: "esm", platform: "neutral" });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`);
}

const snapper = { is_fish: true, common_name_nz: "Snapper", subject_name: "Snapper", scientific_name: "Chrysophrys auratus", confidence: 0.8,
  other_possibilities: [], visible_clues: "Pink with blue spots.", note: "AI suggestion." };
const call = (ai) => ({ model: "@cf/meta/llama-4-scout-17b-16e-instruct", apiKey: "builtin", baseUrl: "", image: new Uint8Array([255, 216, 1]).buffer, contentType: "image/jpeg", ai });

test("Workers AI: the photo goes first, the format is in the prompt, and every answer shape is read", async () => {
  const { identifyWithWorkersAI } = await loadAdapter();
  const calls = [];
  const answers = [{ response: "```json\n" + JSON.stringify(snapper) + "\n```" }, { response: snapper }, { choices: [{ message: { content: JSON.stringify(snapper) } }] }];
  const ai = { async run(model, input) { calls.push({ model, input }); return answers[calls.length - 1]; } };
  for (let index = 0; index < answers.length; index++) assert.equal((await identifyWithWorkersAI(call(ai))).common_name_nz, "Snapper");
  assert.equal(calls[0].model, "@cf/meta/llama-4-scout-17b-16e-instruct");
  const content = calls[0].input.messages[0].content;
  assert.equal(content[0].type, "image_url");
  assert.match(content[0].image_url.url, /^data:image\/jpeg;base64,/);
  assert.match(content[1].text, /JSON object with these fields/);
  assert.equal(calls[0].input.max_tokens, 1024);
});

test("Workers AI: errors are sorted so the CMS can say what to do", async () => {
  const { identifyWithWorkersAI } = await loadAdapter();
  const failing = (message) => ({ async run() { throw new Error(message); } });
  await assert.rejects(identifyWithWorkersAI(call(failing("3036: Account limited: you have used up your daily free allocation of 10,000 neurons"))), (error) => error.kind === "quota");
  await assert.rejects(identifyWithWorkersAI(call(failing("5007: No such model @cf/nope"))), (error) => error.kind === "model");
  await assert.rejects(identifyWithWorkersAI(call(failing("Internal server error"))), (error) => error.kind === "server" && /Workers AI/.test(error.message));
  await assert.rejects(identifyWithWorkersAI(call(undefined)), (error) => error.kind === "not_configured");
});
