// What every fish identification provider is asked, and the shape each must answer in.

export type FishIdentification = {
  is_fish: boolean;
  common_name_nz: string;
  subject_name?: string;
  scientific_name: string;
  confidence: number;
  other_possibilities: string[];
  visible_clues: string;
  note: string;
};

export const FISH_PROMPT = "Identify the main subject of this photo for a New Zealand angler. First decide whether it is a fish. If it is a fish, give the most likely specific common name in common_name_nz, even when the species is uncommon in New Zealand or the photo was taken elsewhere. Prefer the familiar NZ name when one exists; otherwise use a widely understood common name. Do not replace a plausible leading identification with 'Unknown fish' merely because confidence is low: use a low confidence score and explain the uncertainty. Use 'Unknown fish' only when no useful candidate can be named. Give a scientific name only when supported by visible features. Text printed on the image may help but is not proof of species. If it is not a fish, set is_fish false, leave common_name_nz and scientific_name empty, and put the visible subject's concise common name in subject_name (for example 'Sea star (starfish)' or 'Crab'); if the subject cannot be named, use 'Unidentified object'. For fish, subject_name should match common_name_nz. Return confidence from 0 to 1 conservatively. Give up to three distinct alternative fish names in likelihood order, excluding the leading name. Describe visible clues and uncertainty in plain language without Markdown, headings, bullets, or asterisks used for emphasis. Do not give catch or legal advice. This is an AI suggestion, not a confirmed identification.";

/** For providers without a schema option: the same fields, spelled out. */
export const FISH_PROMPT_WITH_FORMAT = `${FISH_PROMPT} Answer with only a JSON object with these fields: is_fish (boolean), common_name_nz (string), subject_name (string), scientific_name (string), confidence (number 0 to 1), other_possibilities (array of strings), visible_clues (string), note (string).`;

export const FISH_SCHEMA = {
  type: "object",
  properties: {
    is_fish: { type: "boolean" },
    common_name_nz: { type: "string" },
    subject_name: { type: "string" },
    scientific_name: { type: "string" },
    confidence: { type: "number" },
    other_possibilities: { type: "array", items: { type: "string" } },
    visible_clues: { type: "string" },
    note: { type: "string" },
  },
  required: ["is_fish", "common_name_nz", "subject_name", "scientific_name", "confidence", "other_possibilities", "visible_clues", "note"],
  additionalProperties: false,
};

export type ProviderId = "openai" | "anthropic" | "gemini" | "openai_compatible";

/** What one provider call needs. */
export type ProviderCall = {
  model: string;
  apiKey: string;
  baseUrl: string;
  image: ArrayBuffer;
  contentType: string;
};

/**
 * Why a provider failed, kept for the CMS. `kind` sorts it: auth (key wrong, revoked or without access), quota (rate or
 * billing limit), model (unknown model), request (the provider rejected the request), server, network, response (an
 * answer that wasn't a usable identification).
 */
export type FailureKind = "auth" | "quota" | "model" | "request" | "server" | "network" | "response" | "not_configured";

export class ProviderError extends Error {
  constructor(readonly kind: FailureKind, message: string, readonly status: number | null = null) { super(message); }
}

export function failureKind(status: number, message: string): FailureKind {
  if (status === 401 || status === 403) return "auth";
  if (status === 429 || /insufficient_quota|billing|credit/i.test(message)) return "quota";
  if (status === 404 || /model[_ ]not[_ ]found|does not exist|unknown model|not supported model/i.test(message)) return "model";
  if (status >= 500) return "server";
  return "request";
}

/** A provider's error text, cut short and with anything that looks like a key removed, for logs and the CMS. */
export function safeProviderMessage(value: unknown): string {
  const text = typeof value === "string" ? value : value === undefined || value === null ? "" : JSON.stringify(value);
  return text.replace(/\b(sk|rk|pk|key|AIza)[-_A-Za-z0-9]{12,}/g, "[redacted]").replace(/\s+/g, " ").trim().slice(0, 300);
}

/** Reads a provider's error body: OpenAI and Anthropic use {error:{message}}, Gemini {error:{message,status}}. */
export async function providerFailure(provider: string, response: Response): Promise<ProviderError> {
  const body = await response.text().catch(() => "");
  let message = body;
  try {
    const parsed = JSON.parse(body) as { error?: { message?: string; type?: string; code?: string; status?: string } | string; message?: string };
    const error = parsed.error;
    message = typeof error === "string" ? error : [error?.code || error?.type || error?.status, error?.message || parsed.message].filter(Boolean).join(": ") || body;
  } catch { /* not JSON */ }
  const detail = safeProviderMessage(message) || `HTTP ${response.status}`;
  return new ProviderError(failureKind(response.status, detail), `${provider} returned HTTP ${response.status}: ${detail}`, response.status);
}

/** The identification inside a model's text: plain JSON, or JSON in a code fence or with words around it. */
export function parseIdentification(text: string | undefined | null): FishIdentification {
  if (!text || !text.trim()) throw new ProviderError("response", "The model returned no answer.");
  let candidate = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  if (!candidate.startsWith("{")) {
    const start = candidate.indexOf("{");
    const end = candidate.lastIndexOf("}");
    if (start >= 0 && end > start) candidate = candidate.slice(start, end + 1);
  }
  let result: FishIdentification;
  try { result = JSON.parse(candidate) as FishIdentification; }
  catch { throw new ProviderError("response", "The model's answer was not valid JSON."); }
  if (!result || typeof result.is_fish !== "boolean" || typeof result.common_name_nz !== "string" || typeof result.confidence !== "number") {
    throw new ProviderError("response", "The model's answer was missing fields.");
  }
  if (!Array.isArray(result.other_possibilities)) result.other_possibilities = [];
  if (typeof result.scientific_name !== "string") result.scientific_name = "";
  if (typeof result.visible_clues !== "string") result.visible_clues = "";
  if (typeof result.note !== "string") result.note = "";
  return result;
}

export function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

/** A fetch that gives up after `ms`, reported as a network failure. */
export async function fetchWithTimeout(url: string, init: RequestInit, ms = 60_000): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try { return await fetch(url, { ...init, signal: controller.signal }); }
  catch (error) {
    throw new ProviderError("network", controller.signal.aborted ? `No answer within ${Math.round(ms / 1000)} seconds.` : `Could not reach the provider: ${safeProviderMessage(String(error))}`);
  } finally { clearTimeout(timer); }
}
