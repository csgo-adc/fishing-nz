import { FISH_PROMPT, FISH_PROMPT_WITH_FORMAT, FISH_SCHEMA, ProviderError, arrayBufferToBase64, fetchWithTimeout, parseIdentification, providerFailure,
  type FishIdentification, type ProviderCall } from "./shared";

/** GPT-5.1 and later take effort "none"; GPT-5 and o-series reasoning models take "minimal"/"low"; others no reasoning field. */
function reasoningFor(model: string): { effort: string } | undefined {
  if (/^gpt-5\.\d/.test(model)) return { effort: "none" };
  if (/^(gpt-5|o\d)/.test(model)) return { effort: "low" };
  return undefined;
}

/** OpenAI's Responses API with a strict JSON schema. Photos are not stored (`store: false`). */
export async function identifyWithOpenAI(call: ProviderCall): Promise<FishIdentification> {
  const response = await fetchWithTimeout("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${call.apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: call.model,
      store: false,
      reasoning: reasoningFor(call.model),
      max_output_tokens: 800,
      input: [{
        role: "user",
        content: [
          { type: "input_text", text: FISH_PROMPT },
          { type: "input_image", image_url: `data:${call.contentType};base64,${arrayBufferToBase64(call.image)}`, detail: "high" },
        ],
      }],
      text: { format: { type: "json_schema", name: "fish_identification", strict: true, schema: FISH_SCHEMA } },
    }),
  });
  if (!response.ok) throw await providerFailure("OpenAI", response);
  const payload = await response.json<{ status?: string; incomplete_details?: { reason?: string }; output?: Array<{ type?: string; content?: Array<{ type?: string; text?: string; refusal?: string }> }> }>();
  if (payload.status === "incomplete") throw new ProviderError("response", `The answer was cut short (${payload.incomplete_details?.reason || "incomplete"}).`);
  const content = payload.output?.flatMap((item) => item.type === "message" ? item.content || [] : []) ?? [];
  const refusal = content.find((item) => item.type === "refusal");
  if (refusal) throw new ProviderError("response", "The model declined to identify this photo.");
  return parseIdentification(content.find((item) => item.type === "output_text")?.text);
}

/**
 * How a Chat Completions service is asked for JSON: a strict schema (OpenAI, OpenRouter), JSON object mode (most
 * Chinese providers, which then need the fields spelled out in the prompt), or the prompt alone.
 */
export type JsonMode = "schema" | "object" | "prompt";
export type CompatibleOptions = { label: string; jsonMode: JsonMode; maxTokens: number };

const GENERIC: CompatibleOptions = { label: "The OpenAI-compatible service", jsonMode: "schema", maxTokens: 800 };

function compatibleBody(call: ProviderCall, options: CompatibleOptions, jsonMode: JsonMode) {
  const responseFormat = jsonMode === "schema"
    ? { type: "json_schema", json_schema: { name: "fish_identification", strict: true, schema: FISH_SCHEMA } }
    : jsonMode === "object" ? { type: "json_object" } : undefined;
  return {
    model: call.model,
    max_tokens: options.maxTokens,
    messages: [{
      role: "user",
      content: [
        // Image first: some vision models (Qwen-VL, GLM-V) read the picture better before the question.
        { type: "image_url", image_url: { url: `data:${call.contentType};base64,${arrayBufferToBase64(call.image)}` } },
        { type: "text", text: jsonMode === "schema" ? FISH_PROMPT : FISH_PROMPT_WITH_FORMAT },
      ],
    }],
    ...(responseFormat ? { response_format: responseFormat } : {}),
  };
}

/** A 400 that is about the JSON option rather than the photo or the model, worth one try without it. */
const formatRejected = (error: ProviderError) => error.status === 400 && /response_format|json|schema/i.test(error.message);

/**
 * Any service with OpenAI's Chat Completions format at `baseUrl`: OpenRouter, Together, Groq, a self-hosted gateway,
 * and the Chinese providers in PROVIDERS (Qwen, GLM, Kimi, Doubao, DeepSeek). When a service rejects the JSON option,
 * it is asked once more with the format spelled out in the prompt. JSON in a code fence or with words around it is
 * still accepted.
 */
export async function identifyWithOpenAICompatible(call: ProviderCall, options: CompatibleOptions = GENERIC): Promise<FishIdentification> {
  if (!call.baseUrl) throw new ProviderError("not_configured", "Add the service's base URL in the CMS.");
  const url = `${call.baseUrl.replace(/\/+$/, "")}/chat/completions`;
  const send = (jsonMode: JsonMode) => fetchWithTimeout(url, {
    method: "POST",
    headers: { authorization: `Bearer ${call.apiKey}`, "content-type": "application/json" },
    body: JSON.stringify(compatibleBody(call, options, jsonMode)),
  });
  let response = await send(options.jsonMode);
  if (!response.ok) {
    const failure = await providerFailure(options.label, response);
    if (options.jsonMode === "prompt" || !formatRejected(failure)) throw failure;
    response = await send("prompt");
    if (!response.ok) throw await providerFailure(options.label, response);
  }
  const payload = await response.json<{ choices?: Array<{ finish_reason?: string; message?: { content?: string | null; refusal?: string | null } }> }>();
  const choice = payload.choices?.[0];
  if (choice?.message?.refusal) throw new ProviderError("response", "The model declined to identify this photo.");
  if (choice?.finish_reason === "length") throw new ProviderError("response", "The answer was cut short.");
  if (choice?.finish_reason === "content_filter" || choice?.finish_reason === "sensitive") {
    throw new ProviderError("response", "The provider's content filter blocked this photo.");
  }
  return parseIdentification(choice?.message?.content);
}
