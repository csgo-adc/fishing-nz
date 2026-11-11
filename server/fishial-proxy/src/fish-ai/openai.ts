import { FISH_PROMPT, FISH_SCHEMA, ProviderError, arrayBufferToBase64, fetchWithTimeout, parseIdentification, providerFailure,
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
 * Any service with OpenAI's Chat Completions format (OpenRouter, Together, Groq, a self-hosted gateway and so on), at
 * `baseUrl` such as https://openrouter.ai/api/v1. Asks for the JSON schema and still accepts JSON in plain text.
 */
export async function identifyWithOpenAICompatible(call: ProviderCall): Promise<FishIdentification> {
  if (!call.baseUrl) throw new ProviderError("not_configured", "Add the service's base URL in the CMS.");
  const response = await fetchWithTimeout(`${call.baseUrl.replace(/\/+$/, "")}/chat/completions`, {
    method: "POST",
    headers: { authorization: `Bearer ${call.apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: call.model,
      max_tokens: 800,
      messages: [{
        role: "user",
        content: [
          { type: "text", text: FISH_PROMPT },
          { type: "image_url", image_url: { url: `data:${call.contentType};base64,${arrayBufferToBase64(call.image)}` } },
        ],
      }],
      response_format: { type: "json_schema", json_schema: { name: "fish_identification", strict: true, schema: FISH_SCHEMA } },
    }),
  });
  if (!response.ok) throw await providerFailure("The OpenAI-compatible service", response);
  const payload = await response.json<{ choices?: Array<{ finish_reason?: string; message?: { content?: string | null; refusal?: string | null } }> }>();
  const choice = payload.choices?.[0];
  if (choice?.message?.refusal) throw new ProviderError("response", "The model declined to identify this photo.");
  if (choice?.finish_reason === "length") throw new ProviderError("response", "The answer was cut short.");
  return parseIdentification(choice?.message?.content);
}
