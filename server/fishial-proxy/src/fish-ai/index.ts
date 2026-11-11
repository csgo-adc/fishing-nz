import { identifyWithAnthropic } from "./anthropic";
import { identifyWithGemini } from "./gemini";
import { identifyWithOpenAI, identifyWithOpenAICompatible } from "./openai";
import { ProviderError, safeProviderMessage, type FailureKind, type FishIdentification, type ProviderCall, type ProviderId } from "./shared";

/**
 * Fish identification AI, chosen in the admin CMS (docs/fish-identification-ai.md): a main provider and model, an
 * optional backup tried when the main one fails, API keys stored encrypted in D1, and each provider's last result so
 * the CMS can say why identification stopped working. With nothing saved it is OpenAI gpt-5.6-luna with the
 * OPENAI_API_KEY secret, as before.
 */
export interface FishAiEnv {
  RULES_DB: D1Database;
  OPENAI_API_KEY?: string;
  ANTHROPIC_API_KEY?: string;
  GEMINI_API_KEY?: string;
  /** Encrypts API keys saved from the CMS. Without it they are encrypted with a key derived from ACCOUNT_ADMIN_TOKEN. */
  SETTINGS_ENCRYPTION_KEY?: string;
  ACCOUNT_ADMIN_TOKEN?: string;
}

type ProviderInfo = {
  label: string;
  /** "china" for the Chinese providers, listed together in the CMS. */
  group: "international" | "china";
  defaultModel: string;
  secret: "OPENAI_API_KEY" | "ANTHROPIC_API_KEY" | "GEMINI_API_KEY" | null;
  /** True when the CMS shows a base URL field; `defaultBaseUrl` fills it, and an empty field uses it. */
  baseUrl: boolean;
  defaultBaseUrl?: string;
  /** Other regions' addresses or model notes, shown under the field. */
  hint?: string;
  /** Where to create an API key. */
  keyUrl?: string;
  run: (call: ProviderCall) => Promise<FishIdentification>;
};

/** A Chinese provider reached through its OpenAI-compatible Chat Completions API (JSON object mode). */
const chinese = (label: string, defaultModel: string, defaultBaseUrl: string, hint: string, keyUrl: string): ProviderInfo => ({
  label, group: "china", defaultModel, secret: null, baseUrl: true, defaultBaseUrl, hint, keyUrl,
  // Room for vision models that think before answering.
  run: (call) => identifyWithOpenAICompatible(call, { label, jsonMode: "object", maxTokens: 4096 }),
});

export const PROVIDERS: Record<ProviderId, ProviderInfo> = {
  openai: { label: "OpenAI", group: "international", defaultModel: "gpt-5.6-luna", secret: "OPENAI_API_KEY", baseUrl: false,
    keyUrl: "https://platform.openai.com/api-keys", run: identifyWithOpenAI },
  anthropic: { label: "Anthropic Claude", group: "international", defaultModel: "claude-opus-5-5", secret: "ANTHROPIC_API_KEY", baseUrl: false,
    keyUrl: "https://platform.claude.com/", run: identifyWithAnthropic },
  gemini: { label: "Google Gemini", group: "international", defaultModel: "gemini-flash-latest", secret: "GEMINI_API_KEY", baseUrl: false,
    keyUrl: "https://aistudio.google.com/apikey", run: identifyWithGemini },
  openai_compatible: { label: "OpenAI-compatible service", group: "international", defaultModel: "", secret: null, baseUrl: true,
    hint: "Any service with OpenAI's chat completions format, such as OpenRouter (https://openrouter.ai/api/v1).",
    run: (call) => identifyWithOpenAICompatible(call) },
  // Chinese providers. Model names change often: Test in the CMS checks the one typed in.
  qwen: chinese("Alibaba Qwen (Model Studio)", "qwen-vl-max", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    "International (Singapore). Mainland China: https://dashscope.aliyuncs.com/compatible-mode/v1. Vision models: qwen-vl-max, qwen-vl-plus, qwen3-vl-plus.",
    "https://modelstudio.console.alibabacloud.com/"),
  glm: chinese("Zhipu GLM (BigModel / Z.ai)", "glm-4.5v", "https://open.bigmodel.cn/api/paas/v4",
    "Mainland China. International (Z.ai): https://api.z.ai/api/paas/v4. Vision models end in v, such as glm-4.5v.",
    "https://open.bigmodel.cn/"),
  kimi: chinese("Moonshot Kimi", "kimi-k2.6", "https://api.moonshot.ai/v1",
    "International. Mainland China: https://api.moonshot.cn/v1. Use a model that accepts images, such as kimi-k2.6.",
    "https://platform.moonshot.ai/"),
  doubao: chinese("ByteDance Doubao (Volcano Engine Ark)", "doubao-seed-1-6-vision-250815", "https://ark.cn-beijing.volces.com/api/v3",
    "Beijing region. Use a vision model ID or your Ark endpoint ID (ep-…) from the Ark console.",
    "https://console.volcengine.com/ark"),
  deepseek: chinese("DeepSeek", "deepseek-v4-flash-vision-exp", "https://api.deepseek.com",
    "Only DeepSeek's vision models accept photos; text models such as deepseek-chat answer HTTP 400.",
    "https://platform.deepseek.com/api_keys"),
};

export type SlotConfig = { provider: ProviderId; model: string; base_url: string };
export type FishAiConfig = { primary: SlotConfig; backup: SlotConfig | null };

const DEFAULT_CONFIG: FishAiConfig = { primary: { provider: "openai", model: PROVIDERS.openai.defaultModel, base_url: "" }, backup: null };
const CONFIG_KEY = "fish_ai.config";
const keyName = (provider: ProviderId) => `fish_ai.key.${provider}`;
const statusName = (provider: ProviderId) => `fish_ai.status.${provider}`;

type StoredKey = { v: 1; iv: string; data: string; last4: string; secret: "settings" | "admin" };
export type ProviderStatus = {
  model: string;
  last_success_at: string | null;
  last_latency_ms: number | null;
  last_error_at: string | null;
  last_error: string | null;
  last_error_kind: FailureKind | null;
  successes: number;
  failures: number;
};

// --- Settings rows -------------------------------------------------------------------------------------------------

async function readSetting<T>(db: D1Database, key: string): Promise<{ value: T; updated_at: string } | null> {
  try {
    const row = await db.prepare("SELECT value, updated_at FROM app_settings WHERE key = ?").bind(key).first<{ value: string; updated_at: string }>();
    return row ? { value: JSON.parse(row.value) as T, updated_at: row.updated_at } : null;
  } catch (error) {
    // Before migration 0014 the table is missing; fall back to the defaults rather than failing identification.
    console.error(`Could not read setting ${key}`, String(error));
    return null;
  }
}

async function writeSetting(db: D1Database, key: string, value: unknown): Promise<void> {
  await db.prepare(`INSERT INTO app_settings (key, value, updated_at) VALUES (?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`)
    .bind(key, JSON.stringify(value), new Date().toISOString()).run();
}

function validSlot(value: unknown): SlotConfig | null {
  if (!value || typeof value !== "object") return null;
  const slot = value as Record<string, unknown>;
  if (typeof slot.provider !== "string" || !(slot.provider in PROVIDERS)) return null;
  const provider = slot.provider as ProviderId;
  const model = typeof slot.model === "string" ? slot.model.trim() : "";
  const typed = typeof slot.base_url === "string" ? slot.base_url.trim().replace(/\/+$/, "") : "";
  const baseUrl = typed || PROVIDERS[provider].defaultBaseUrl || "";
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,119}$/.test(model)) return null;
  if (PROVIDERS[provider].baseUrl) {
    try {
      const url = new URL(baseUrl);
      if (url.protocol !== "https:" || baseUrl.length > 200 || url.username || url.password) return null;
    } catch { return null; }
  }
  return { provider, model, base_url: PROVIDERS[provider].baseUrl ? baseUrl : "" };
}

export async function loadConfig(env: FishAiEnv): Promise<FishAiConfig> {
  const saved = await readSetting<FishAiConfig>(env.RULES_DB, CONFIG_KEY);
  const primary = validSlot(saved?.value?.primary);
  if (!primary) return DEFAULT_CONFIG;
  return { primary, backup: validSlot(saved?.value?.backup) };
}

// --- API keys, encrypted with AES-GCM ------------------------------------------------------------------------------

function encryptionSecret(env: FishAiEnv, which: "settings" | "admin"): string | undefined {
  return which === "settings" ? env.SETTINGS_ENCRYPTION_KEY : env.ACCOUNT_ADMIN_TOKEN;
}

async function aesKey(secret: string): Promise<CryptoKey> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`fishdays-settings-v1\n${secret}`));
  return crypto.subtle.importKey("raw", digest, "AES-GCM", false, ["encrypt", "decrypt"]);
}

const toBase64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const fromBase64 = (text: string) => Uint8Array.from(atob(text), (char) => char.charCodeAt(0));

async function encryptKey(env: FishAiEnv, plain: string): Promise<StoredKey> {
  const which = env.SETTINGS_ENCRYPTION_KEY ? "settings" : "admin";
  const secret = encryptionSecret(env, which);
  if (!secret) throw new ProviderError("not_configured", "Set the SETTINGS_ENCRYPTION_KEY secret before saving API keys.");
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(secret), new TextEncoder().encode(plain)));
  return { v: 1, iv: toBase64(iv), data: toBase64(data), last4: plain.slice(-4), secret: which };
}

async function decryptKey(env: FishAiEnv, stored: StoredKey): Promise<string | null> {
  const secret = encryptionSecret(env, stored.secret);
  if (!secret) return null;
  try {
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromBase64(stored.iv) }, await aesKey(secret), fromBase64(stored.data));
    return new TextDecoder().decode(plain);
  } catch { return null; }
}

type KeyInfo = { source: "cms" | "secret" | null; last4: string | null; updated_at: string | null; problem: string | null };

async function resolveKey(env: FishAiEnv, provider: ProviderId): Promise<{ key: string | null; info: KeyInfo }> {
  const stored = await readSetting<StoredKey>(env.RULES_DB, keyName(provider));
  let problem: string | null = null;
  if (stored) {
    const key = await decryptKey(env, stored.value);
    if (key) return { key, info: { source: "cms", last4: stored.value.last4, updated_at: stored.updated_at, problem: null } };
    problem = "The saved key can't be read because the encryption secret changed. Enter the key again.";
  }
  const secretName = PROVIDERS[provider].secret;
  const secret = secretName ? env[secretName] : undefined;
  if (secret) return { key: secret, info: { source: "secret", last4: secret.slice(-4), updated_at: null, problem } };
  return { key: null, info: { source: null, last4: stored?.value.last4 ?? null, updated_at: stored?.updated_at ?? null, problem } };
}

// --- Running a provider --------------------------------------------------------------------------------------------

async function recordResult(env: FishAiEnv, provider: ProviderId, model: string, outcome: { latency: number } | { error: ProviderError }): Promise<void> {
  const previous = (await readSetting<ProviderStatus>(env.RULES_DB, statusName(provider)))?.value;
  const now = new Date().toISOString();
  const status: ProviderStatus = {
    model,
    last_success_at: previous?.last_success_at ?? null,
    last_latency_ms: previous?.last_latency_ms ?? null,
    last_error_at: previous?.last_error_at ?? null,
    last_error: previous?.last_error ?? null,
    last_error_kind: previous?.last_error_kind ?? null,
    successes: previous?.successes ?? 0,
    failures: previous?.failures ?? 0,
  };
  if ("latency" in outcome) {
    status.last_success_at = now;
    status.last_latency_ms = outcome.latency;
    status.successes += 1;
  } else {
    status.last_error_at = now;
    status.last_error = outcome.error.message.slice(0, 400);
    status.last_error_kind = outcome.error.kind;
    status.failures += 1;
  }
  await writeSetting(env.RULES_DB, statusName(provider), status).catch((error) => console.error("Could not record fish ID provider status", String(error)));
}

async function runSlot(env: FishAiEnv, slot: SlotConfig, image: ArrayBuffer, contentType: string): Promise<{ identification: FishIdentification; latency: number }> {
  const info = PROVIDERS[slot.provider];
  const started = Date.now();
  try {
    if (!slot.model) throw new ProviderError("not_configured", `Choose a ${info.label} model in the CMS.`);
    const { key, info: keyInfo } = await resolveKey(env, slot.provider);
    if (!key) throw new ProviderError("not_configured", keyInfo.problem || `No ${info.label} API key. Add one in the CMS (Fish ID).`);
    const identification = await info.run({ model: slot.model, apiKey: key, baseUrl: slot.base_url || info.defaultBaseUrl || "", image, contentType });
    const latency = Date.now() - started;
    await recordResult(env, slot.provider, slot.model, { latency });
    return { identification, latency };
  } catch (caught) {
    const error = caught instanceof ProviderError ? caught : new ProviderError("response", safeProviderMessage(String(caught)) || "Unexpected error.");
    console.error(`Fish identification with ${info.label} (${slot.model}) failed: [${error.kind}] ${error.message}`);
    await recordResult(env, slot.provider, slot.model, { error });
    throw error;
  }
}

export type IdentifyOutcome = { identification: FishIdentification; provider: ProviderId; model: string; usedBackup: boolean };

/** The main provider, then the backup if one is set. Throws the main provider's error when both fail. */
export async function identifyFish(env: FishAiEnv, image: ArrayBuffer, contentType: string): Promise<IdentifyOutcome> {
  const config = await loadConfig(env);
  const slots = config.backup ? [config.primary, config.backup] : [config.primary];
  const errors: ProviderError[] = [];
  for (const [index, slot] of slots.entries()) {
    try {
      const { identification } = await runSlot(env, slot, image, contentType);
      return { identification, provider: slot.provider, model: slot.model, usedBackup: index > 0 };
    } catch (error) { errors.push(error as ProviderError); }
  }
  // Every provider out of quota reads as busy; any other failure as unavailable.
  if (errors.every((error) => error.kind === "quota")) throw errors[0];
  throw errors.find((error) => error.kind !== "quota") ?? errors[0];
}

/** The app-facing status and message for a failed identification. Provider details stay in the CMS and logs. */
export function identificationFailure(error: unknown): { status: number; message: string } {
  const kind = error instanceof ProviderError ? error.kind : "response";
  if (kind === "quota") return { status: 429, message: "Fish identification is busy right now. Try again in a few minutes." };
  if (kind === "not_configured") return { status: 503, message: "Fish identification is not set up yet. Try again later." };
  return { status: 502, message: "Fish identification is temporarily unavailable. Try again later." };
}

// --- Admin routes (the caller checks the admin token) ----------------------------------------------------------------

const json = (body: unknown, status = 200) => Response.json(body, { status, headers: { "cache-control": "no-store" } });

async function overview(env: FishAiEnv): Promise<Response> {
  const config = await loadConfig(env);
  const providers = await Promise.all((Object.keys(PROVIDERS) as ProviderId[]).map(async (id) => {
    const { info } = await resolveKey(env, id);
    const status = (await readSetting<ProviderStatus>(env.RULES_DB, statusName(id)))?.value ?? null;
    const provider = PROVIDERS[id];
    return { id, label: provider.label, group: provider.group, default_model: provider.defaultModel, needs_base_url: provider.baseUrl,
      default_base_url: provider.defaultBaseUrl ?? "", hint: provider.hint ?? "", key_url: provider.keyUrl ?? "",
      secret_name: provider.secret, key: info, status };
  }));
  const since = new Date(Date.now() - 13 * 86_400_000).toISOString().slice(0, 10);
  const usage = await env.RULES_DB.prepare(`SELECT day, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily
    WHERE route = 'fish_identify' AND day >= ? GROUP BY day ORDER BY day`).bind(since).all<{ day: string; calls: number; errors: number }>()
    .then((result) => result.results).catch(() => []);
  return json({
    config, providers, usage, daily_limit: 5,
    key_encryption: env.SETTINGS_ENCRYPTION_KEY ? "SETTINGS_ENCRYPTION_KEY" : env.ACCOUNT_ADMIN_TOKEN ? "ACCOUNT_ADMIN_TOKEN" : null,
  });
}

async function readBody(request: Request): Promise<Record<string, unknown> | null> {
  if (Number(request.headers.get("content-length") || 0) > 8_192) return null;
  const body = await request.json().catch(() => null);
  return body && typeof body === "object" && !Array.isArray(body) ? body as Record<string, unknown> : null;
}

async function saveConfig(request: Request, env: FishAiEnv): Promise<Response> {
  const body = await readBody(request);
  const primary = validSlot(body?.primary);
  const backup = body?.backup === null || body?.backup === undefined ? null : validSlot(body.backup);
  if (!primary || (body?.backup && !backup)) return json({ error: "Choose a provider and a model name (letters, numbers and . _ : / @ + - only). An OpenAI-compatible service also needs an https base URL." }, 400);
  if (backup && backup.provider === primary.provider && backup.model === primary.model && backup.base_url === primary.base_url) {
    return json({ error: "The backup is the same as the main provider. Choose another provider or model, or no backup." }, 400);
  }
  await writeSetting(env.RULES_DB, CONFIG_KEY, { primary, backup });
  return overview(env);
}

async function saveKey(request: Request, env: FishAiEnv, provider: ProviderId): Promise<Response> {
  const body = await readBody(request);
  const key = typeof body?.key === "string" ? body.key.trim() : "";
  if (key.length < 10 || key.length > 400 || /\s/.test(key)) return json({ error: "Paste the whole API key, without spaces." }, 400);
  try { await writeSetting(env.RULES_DB, keyName(provider), await encryptKey(env, key)); }
  catch (error) { return json({ error: error instanceof ProviderError ? error.message : "Could not save the key." }, 503); }
  return overview(env);
}

async function testProvider(request: Request, env: FishAiEnv, url: URL): Promise<Response> {
  const config = await loadConfig(env);
  const slotName = url.searchParams.get("slot");
  // A draft from the form (not saved yet) can be tested too; its key must already be saved or be a Worker secret.
  const draft = url.searchParams.has("provider")
    ? validSlot({ provider: url.searchParams.get("provider"), model: url.searchParams.get("model") ?? "", base_url: url.searchParams.get("base_url") ?? "" })
    : null;
  const slot = draft ?? (slotName === "backup" ? config.backup : config.primary);
  if (!slot) return json({ error: url.searchParams.has("provider") ? "Choose a provider and a valid model name first." : "No backup provider is set." }, 400);
  const contentType = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  const image = await request.arrayBuffer();
  if (!["image/jpeg", "image/png", "image/webp"].includes(contentType) || image.byteLength === 0 || image.byteLength > 8 * 1024 * 1024) {
    return json({ error: "Test with a JPEG, PNG or WebP photo under 8 MB." }, 400);
  }
  try {
    const { identification, latency } = await runSlot(env, slot, image, contentType);
    return json({ ok: true, provider: slot.provider, model: slot.model, latency_ms: latency, result: identification });
  } catch (error) {
    const failure = error as ProviderError;
    return json({ ok: false, provider: slot.provider, model: slot.model, error: { kind: failure.kind, status: failure.status, message: failure.message } });
  }
}

/** `/v1/admin/fish-ai` routes, or null for other paths. */
export async function handleFishAiAdmin(request: Request, env: FishAiEnv, pathname: string): Promise<Response | null> {
  if (pathname === "/v1/admin/fish-ai") {
    if (request.method === "GET") return overview(env);
    if (request.method === "PUT") return saveConfig(request, env);
    return null;
  }
  if (pathname === "/v1/admin/fish-ai/test" && request.method === "POST") return testProvider(request, env, new URL(request.url));
  const keyMatch = pathname.match(/^\/v1\/admin\/fish-ai\/keys\/([a-z_]+)$/);
  if (keyMatch && keyMatch[1] in PROVIDERS) {
    const provider = keyMatch[1] as ProviderId;
    if (request.method === "PUT") return saveKey(request, env, provider);
    if (request.method === "DELETE") {
      await env.RULES_DB.prepare("DELETE FROM app_settings WHERE key = ?").bind(keyName(provider)).run();
      return overview(env);
    }
  }
  return null;
}
