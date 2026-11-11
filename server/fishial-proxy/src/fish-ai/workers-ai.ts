import { FISH_PROMPT_WITH_FORMAT, ProviderError, arrayBufferToBase64, parseIdentification, safeProviderMessage,
  type FishIdentification, type ProviderCall } from "./shared";

/**
 * Cloudflare Workers AI through the Worker's `AI` binding (wrangler.toml): no API key, the photo stays with Cloudflare,
 * and the account's free daily allowance (10,000 neurons, shared by every model) covers light use. Its vision models
 * have no strict JSON mode, so the answer's fields are spelled out in the prompt.
 */
export async function identifyWithWorkersAI(call: ProviderCall): Promise<FishIdentification> {
  if (!call.ai) throw new ProviderError("not_configured", "The API has no Workers AI binding. Add [ai] binding = \"AI\" to wrangler.toml and deploy.");
  let timer: ReturnType<typeof setTimeout> | undefined;
  let output: unknown;
  try {
    output = await Promise.race([
      call.ai.run(call.model, {
        messages: [{
          role: "user",
          content: [
            { type: "image_url", image_url: { url: `data:${call.contentType};base64,${arrayBufferToBase64(call.image)}` } },
            { type: "text", text: FISH_PROMPT_WITH_FORMAT },
          ],
        }],
        max_tokens: 1024,
        temperature: 0.2,
      }),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new ProviderError("network", "No answer from Workers AI within 60 seconds.")), 60_000); }),
    ]);
  } catch (error) {
    if (error instanceof ProviderError) throw error;
    throw workersAiFailure(error);
  } finally {
    if (timer) clearTimeout(timer);
  }
  // Text models answer { response: "…" }; some answer with an object already, newer ones in the Chat Completions shape.
  const result = output as { response?: unknown; choices?: Array<{ message?: { content?: unknown } }> } | null;
  const answer = result?.response ?? result?.choices?.[0]?.message?.content;
  const text = typeof answer === "string" ? answer : answer && typeof answer === "object" ? JSON.stringify(answer) : "";
  return parseIdentification(text);
}

/** Workers AI errors carry a code in the message, such as 3036 when the daily free allocation is used up. */
function workersAiFailure(error: unknown): ProviderError {
  const message = safeProviderMessage(error instanceof Error ? error.message : String(error)) || "Unexpected error.";
  const detail = `Workers AI: ${message}`;
  if (/\b3036\b|neuron|daily free allocation|quota|rate limit|capacity|\b429\b/i.test(message)) return new ProviderError("quota", detail);
  if (/\b5007\b|no such model|model not found|unknown model|not a valid model/i.test(message)) return new ProviderError("model", detail);
  if (/\b5006\b|\b5021\b|invalid input|input validation|unsupported|image/i.test(message)) return new ProviderError("request", detail);
  return new ProviderError("server", detail);
}
