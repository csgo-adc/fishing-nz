import { FISH_PROMPT, ProviderError, arrayBufferToBase64, fetchWithTimeout, parseIdentification, providerFailure,
  type FishIdentification, type ProviderCall } from "./shared";

// Gemini's response schema is an OpenAPI subset: no additionalProperties.
const GEMINI_SCHEMA = {
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
};

/** Google's Gemini API (generateContent) with a JSON response schema. */
export async function identifyWithGemini(call: ProviderCall): Promise<FishIdentification> {
  const model = encodeURIComponent(call.model.replace(/^models\//, ""));
  const response = await fetchWithTimeout(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: "POST",
    headers: { "x-goog-api-key": call.apiKey, "content-type": "application/json" },
    body: JSON.stringify({
      contents: [{
        role: "user",
        parts: [
          { text: FISH_PROMPT },
          { inline_data: { mime_type: call.contentType, data: arrayBufferToBase64(call.image) } },
        ],
      }],
      generationConfig: { responseMimeType: "application/json", responseSchema: GEMINI_SCHEMA, maxOutputTokens: 2048 },
    }),
  });
  if (!response.ok) throw await providerFailure("Gemini", response);
  const payload = await response.json<{ promptFeedback?: { blockReason?: string }; candidates?: Array<{ finishReason?: string; content?: { parts?: Array<{ text?: string; thought?: boolean }> } }> }>();
  if (payload.promptFeedback?.blockReason) throw new ProviderError("response", `Gemini blocked the photo (${payload.promptFeedback.blockReason}).`);
  const candidate = payload.candidates?.[0];
  if (candidate?.finishReason === "MAX_TOKENS") throw new ProviderError("response", "The answer was cut short.");
  if (candidate?.finishReason === "SAFETY") throw new ProviderError("response", "Gemini declined to identify this photo.");
  const text = candidate?.content?.parts?.filter((part) => !part.thought).map((part) => part.text || "").join("");
  return parseIdentification(text);
}
