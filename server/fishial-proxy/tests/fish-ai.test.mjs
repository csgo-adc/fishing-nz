import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { Miniflare } from "miniflare";
import { bundleWorker } from "./load-worker.mjs";

const hash = (value) => createHash("sha256").update(value).digest("hex");
const ADMIN = "admin-token-".padEnd(40, "x");
const SESSION = "f".repeat(64);
const snapper = { is_fish: true, common_name_nz: "Snapper", subject_name: "Snapper", scientific_name: "Chrysophrys auratus", confidence: 0.86,
  other_possibilities: ["Tarakihi"], visible_clues: "Pink body with blue spots.", note: "AI suggestion." };

test("fish identification providers, backup and CMS settings, with real D1", { timeout: 90_000 }, async (t) => {
  // Every outbound call lands here: `reply` decides each provider's answer, `seen` keeps the requests.
  const seen = [];
  let reply = () => new Response("unexpected", { status: 500 });
  const mf = new Miniflare({
    modules: true, script: await bundleWorker(), compatibilityDate: "2024-09-23",
    d1Databases: { RULES_DB: "fish-ai-tests" },
    bindings: { ACCOUNT_ADMIN_TOKEN: ADMIN, OPENAI_API_KEY: "sk-secret-openai-1234" },
    outboundService: async (request) => {
      const body = request.method === "POST" ? await request.text() : "";
      const entry = { url: request.url, headers: Object.fromEntries(request.headers), body: body ? JSON.parse(body) : null };
      seen.push(entry);
      return reply(entry);
    },
  });
  try {
    const db = await mf.getD1Database("RULES_DB");
    const migrations = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(migrations).filter((name) => name.endsWith(".sql")).sort()) {
      for (const statement of readFileSync(new URL(file, migrations), "utf8").split(";").filter((sql) => sql.trim())) await db.prepare(statement).run();
    }
    const now = new Date().toISOString();
    await db.prepare("INSERT INTO account_users (id, email, password_hash, password_salt, display_name, created_at, updated_at, email_verified) VALUES ('fisher', 'fisher@example.com', 'hash', 'salt', 'Fisher', ?, ?, 1)").bind(now, now).run();
    await db.prepare("INSERT INTO account_sessions VALUES (?, 'fisher', ?, ?)").bind(hash(SESSION), now, new Date(Date.now() + 86_400_000).toISOString()).run();

    const identify = () => mf.dispatchFetch("https://fishing.test/v1/fish/identify", {
      method: "POST", headers: { authorization: `Bearer ${SESSION}`, "content-type": "image/jpeg" }, body: new Uint8Array([255, 216, 255, 1, 2, 3]),
    });
    const admin = (path, method = "GET", body, headers = {}) => mf.dispatchFetch(`https://fishing.test/v1/admin/fish-ai${path}`, {
      method, headers: { "x-account-admin-token": ADMIN, ...(body && !(body instanceof Uint8Array) ? { "content-type": "application/json" } : {}), ...headers },
      body: body instanceof Uint8Array ? body : body === undefined ? undefined : JSON.stringify(body),
    });
    const provider = async (id) => (await (await admin("")).json()).providers.find((row) => row.id === id);
    const used = async () => (await db.prepare("SELECT used FROM account_identification_usage WHERE user_id = 'fisher'").first())?.used ?? 0;
    const openAIAnswer = () => Response.json({ status: "completed", output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify(snapper) }] }] });
    const claudeAnswer = (text) => Response.json({ id: "msg_1", type: "message", role: "assistant", model: "claude-opus-5-5", stop_reason: "end_turn",
      content: [{ type: "text", text }], usage: { input_tokens: 10, output_tokens: 10 } });

    await t.test("admin routes need the admin token", async () => {
      const response = await mf.dispatchFetch("https://fishing.test/v1/admin/fish-ai");
      assert.equal(response.status, 401);
    });

    await t.test("with nothing saved it uses OpenAI gpt-5.6-luna and the Worker secret", async () => {
      reply = () => openAIAnswer();
      const response = await identify();
      assert.equal(response.status, 200);
      assert.equal((await response.json()).commonName, "Snapper");
      const request = seen.at(-1);
      assert.equal(request.url, "https://api.openai.com/v1/responses");
      assert.equal(request.headers.authorization, "Bearer sk-secret-openai-1234");
      assert.equal(request.body.model, "gpt-5.6-luna");
      assert.equal(request.body.store, false);
      const openai = await provider("openai");
      assert.equal(openai.key.source, "secret");
      assert.equal(openai.key.last4, "1234");
      assert.equal(openai.status.successes, 1);
    });

    await t.test("a key saved in the CMS is stored encrypted and never returned", async () => {
      const response = await admin("/keys/anthropic", "PUT", { key: "sk-ant-cms-key-abcd" });
      assert.equal(response.status, 200);
      const text = await response.text();
      assert.doesNotMatch(text, /sk-ant-cms-key/);
      const stored = await db.prepare("SELECT value FROM app_settings WHERE key = 'fish_ai.key.anthropic'").first();
      assert.doesNotMatch(stored.value, /sk-ant-cms-key/);
      const anthropic = await provider("anthropic");
      assert.equal(anthropic.key.source, "cms");
      assert.equal(anthropic.key.last4, "abcd");
      assert.equal((await admin("/keys/anthropic", "PUT", { key: "short" })).status, 400);
    });

    await t.test("rejects an invalid provider setup", async () => {
      assert.equal((await admin("", "PUT", { primary: { provider: "nope", model: "x" } })).status, 400);
      assert.equal((await admin("", "PUT", { primary: { provider: "openai", model: "bad model name" } })).status, 400);
      assert.equal((await admin("", "PUT", { primary: { provider: "openai_compatible", model: "m", base_url: "http://insecure.test" } })).status, 400);
      assert.equal((await admin("", "PUT", { primary: { provider: "openai", model: "gpt-5.6-luna" }, backup: { provider: "openai", model: "gpt-5.6-luna" } })).status, 400);
    });

    await t.test("falls back to the backup when the main provider fails, and records why", async () => {
      const saved = await admin("", "PUT", { primary: { provider: "anthropic", model: "claude-opus-5-5" }, backup: { provider: "openai", model: "gpt-5.6-luna" } });
      assert.equal(saved.status, 200);
      reply = (request) => request.url.startsWith("https://api.anthropic.com/")
        ? Response.json({ type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } }, { status: 401 })
        : openAIAnswer();
      const response = await identify();
      assert.equal(response.status, 200);
      const claudeRequest = seen.find((request) => request.url.startsWith("https://api.anthropic.com/v1/messages"));
      assert.equal(claudeRequest.headers.authorization ?? `Bearer ${claudeRequest.headers["x-api-key"]}`, "Bearer sk-ant-cms-key-abcd");
      assert.equal(claudeRequest.body.model, "claude-opus-5-5");
      assert.equal(claudeRequest.body.output_config.format.type, "json_schema");
      assert.equal(claudeRequest.body.fallbacks, "default");
      assert.match(claudeRequest.headers["anthropic-beta"], /server-side-fallback-2026-07-01/);
      const anthropic = await provider("anthropic");
      assert.equal(anthropic.status.last_error_kind, "auth");
      assert.match(anthropic.status.last_error, /401/);
      assert.equal(anthropic.status.failures, 1);
    });

    await t.test("when every provider is out of quota the app hears busy, and the allowance is given back", async () => {
      const before = await used();
      reply = () => Response.json({ error: { code: "insufficient_quota", message: "You exceeded your current quota" } }, { status: 429 });
      const response = await identify();
      assert.equal(response.status, 429);
      assert.match((await response.json()).error, /busy right now/);
      assert.equal(await used(), before);
    });

    await t.test("any other failure reads as temporarily unavailable, with the detail kept for the CMS", async () => {
      reply = () => Response.json({ error: { message: "The model `gpt-9` does not exist" } }, { status: 404 });
      const response = await identify();
      assert.equal(response.status, 502);
      assert.match((await response.json()).error, /temporarily unavailable/);
      assert.equal((await provider("openai")).status.last_error_kind, "model");
    });

    await t.test("Gemini, with the key from the CMS", async () => {
      await admin("/keys/gemini", "PUT", { key: "AIzaSyTestGeminiKey9876" });
      await admin("", "PUT", { primary: { provider: "gemini", model: "gemini-flash-latest" }, backup: null });
      reply = () => Response.json({ candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify(snapper) }] } }] });
      const response = await identify();
      assert.equal(response.status, 200);
      const request = seen.at(-1);
      assert.equal(request.url, "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent");
      assert.equal(request.headers["x-goog-api-key"], "AIzaSyTestGeminiKey9876");
      assert.equal(request.body.generationConfig.responseMimeType, "application/json");
    });

    await t.test("an OpenAI-compatible service at its own base URL, answering in a code fence", async () => {
      await admin("/keys/openai_compatible", "PUT", { key: "or-test-key-5555" });
      await admin("", "PUT", { primary: { provider: "openai_compatible", model: "vendor/vision-model", base_url: "https://gateway.test/api/v1/" } });
      reply = () => Response.json({ choices: [{ finish_reason: "stop", message: { content: "```json\n" + JSON.stringify(snapper) + "\n```" } }] });
      const response = await identify();
      assert.equal(response.status, 200);
      assert.equal(seen.at(-1).url, "https://gateway.test/api/v1/chat/completions");
      assert.equal(seen.at(-1).body.model, "vendor/vision-model");
    });

    await t.test("Test in the CMS runs a draft provider without saving it and shows the raw error", async () => {
      reply = (request) => request.url.startsWith("https://api.anthropic.com/") ? claudeAnswer(JSON.stringify(snapper)) : new Response("no", { status: 500 });
      const image = new Uint8Array([137, 80, 78, 71]);
      let response = await admin("/test?provider=anthropic&model=claude-sonnet-5-5", "POST", image, { "content-type": "image/png" });
      let body = await response.json();
      assert.equal(body.ok, true);
      assert.equal(body.result.common_name_nz, "Snapper");
      assert.equal(typeof body.latency_ms, "number");
      reply = () => Response.json({ type: "error", error: { type: "not_found_error", message: "model: claude-nope" } }, { status: 404 });
      response = await admin("/test?provider=anthropic&model=claude-nope", "POST", image, { "content-type": "image/png" });
      body = await response.json();
      assert.equal(body.ok, false);
      assert.equal(body.error.kind, "model");
      assert.match(body.error.message, /claude-nope/);
      // The saved setup is unchanged.
      assert.equal((await (await admin("")).json()).config.primary.provider, "openai_compatible");
    });

    await t.test("a saved key that can no longer be read says so and falls back to the Worker secret", async () => {
      await db.prepare("UPDATE app_settings SET value = ? WHERE key = 'fish_ai.key.openai'")
        .bind(JSON.stringify({ v: 1, iv: "AAAAAAAAAAAAAAAA", data: "AAAA", last4: "zzzz", secret: "admin" })).run();
      await db.prepare("INSERT OR REPLACE INTO app_settings (key, value, updated_at) VALUES ('fish_ai.key.openai', ?, ?)")
        .bind(JSON.stringify({ v: 1, iv: "AAAAAAAAAAAAAAAA", data: "AAAA", last4: "zzzz", secret: "admin" }), now).run();
      const openai = await provider("openai");
      assert.equal(openai.key.source, "secret");
      assert.match(openai.key.problem, /Enter the key again/);
    });
  } finally {
    await mf.dispose();
  }
});
