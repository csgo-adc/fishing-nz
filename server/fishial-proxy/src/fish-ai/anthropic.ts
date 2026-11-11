import Anthropic from "@anthropic-ai/sdk";
import { FISH_PROMPT, FISH_SCHEMA, ProviderError, arrayBufferToBase64, failureKind, parseIdentification, safeProviderMessage,
  type FishIdentification, type ProviderCall } from "./shared";

type ImageType = "image/jpeg" | "image/png" | "image/webp" | "image/gif";

/** Models that accept the server-side refusal fallback (`fallbacks: "default"`). */
const FALLBACK_MODELS = /^claude-(fable-5|mythos-5|opus-5|sonnet-5-5)/;
/** Claude 5 models take an effort level; a photo identification needs little reasoning, so it runs at "low". */
const EFFORT_MODELS = /^claude-(fable|mythos|opus|sonnet|haiku)-5/;

/** Anthropic's Messages API through the official SDK, with structured JSON output. */
export async function identifyWithAnthropic(call: ProviderCall): Promise<FishIdentification> {
  // The SDK is given the global fetch at call time, so the Worker's fetch (and tests' stand-in) is the one used.
  const client = new Anthropic({ apiKey: call.apiKey, maxRetries: 1, timeout: 60_000, fetch: (input, init) => fetch(input, init) });
  const fallbacks = FALLBACK_MODELS.test(call.model);
  let message: Anthropic.Beta.BetaMessage;
  try {
    message = await client.beta.messages.create({
      model: call.model,
      max_tokens: 16000,
      ...(fallbacks ? { betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" as const } : {}),
      output_config: {
        format: { type: "json_schema", schema: FISH_SCHEMA },
        ...(EFFORT_MODELS.test(call.model) ? { effort: "low" as const } : {}),
      },
      messages: [{
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: call.contentType as ImageType, data: arrayBufferToBase64(call.image) } },
          { type: "text", text: FISH_PROMPT },
        ],
      }],
    });
  } catch (error) {
    if (error instanceof Anthropic.APIConnectionTimeoutError) throw new ProviderError("network", "No answer from Anthropic within 60 seconds.");
    if (error instanceof Anthropic.APIConnectionError) throw new ProviderError("network", `Could not reach Anthropic: ${safeProviderMessage(error.message)}`);
    if (error instanceof Anthropic.APIError) {
      const inner = (error.error as { error?: { type?: string; message?: string } } | undefined)?.error;
      const detail = safeProviderMessage(inner ? [inner.type, inner.message].filter(Boolean).join(": ") : error.message) || `HTTP ${error.status}`;
      const status = typeof error.status === "number" ? error.status : 0;
      throw new ProviderError(failureKind(status, detail), `Anthropic returned HTTP ${status}: ${detail}`, status || null);
    }
    throw error;
  }
  if (message.stop_reason === "refusal") throw new ProviderError("response", "Claude declined to identify this photo.");
  if (message.stop_reason === "max_tokens") throw new ProviderError("response", "The answer was cut short.");
  const text = message.content.filter((block): block is Anthropic.Beta.BetaTextBlock => block.type === "text").map((block) => block.text).join("");
  return parseIdentification(text);
}
