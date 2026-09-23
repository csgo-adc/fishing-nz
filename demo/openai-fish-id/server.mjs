import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT || 4178);
const model = process.env.OPENAI_FISH_MODEL || "gpt-5.6-luna";
const maxImageBytes = 10 * 1024 * 1024;
const allowedTypes = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);

const schema = {
  type: "object",
  properties: {
    is_fish: { type: "boolean" },
    common_name_nz: { type: "string" },
    scientific_name: { type: "string" },
    confidence: { type: "string", enum: ["high", "medium", "low"] },
    other_possibilities: { type: "array", items: { type: "string" } },
    visible_clues: { type: "string" },
    note: { type: "string" },
  },
  required: ["is_fish", "common_name_nz", "scientific_name", "confidence", "other_possibilities", "visible_clues", "note"],
  additionalProperties: false,
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);
  if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
    response.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    response.end(await readFile(join(here, "index.html")));
    return;
  }
  if (request.method !== "POST" || url.pathname !== "/identify") {
    sendJson(response, 404, { error: "Not found." });
    return;
  }
  if (!process.env.OPENAI_API_KEY) {
    sendJson(response, 503, { error: "Set OPENAI_API_KEY in the terminal, then restart the demo." });
    return;
  }

  try {
    const payload = await readJson(request);
    const mimeType = payload.mimeType;
    const base64 = payload.image;
    if (!allowedTypes.has(mimeType) || typeof base64 !== "string" || !base64.length) {
      sendJson(response, 400, { error: "Choose a JPEG, PNG, WebP, or GIF fish photo." });
      return;
    }
    if (base64.length > Math.ceil(maxImageBytes * 4 / 3) + 8) {
      sendJson(response, 413, { error: "That photo is too large. Choose an image under 10 MB." });
      return;
    }

    const apiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      signal: AbortSignal.timeout(90_000),
      headers: {
        authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model,
        reasoning: { effort: "none" },
        max_output_tokens: 800,
        input: [{
          role: "user",
          content: [
            {
              type: "input_text",
              text: "Identify the fish in this photo for a New Zealand angler. Return the New Zealand common name first (for example snapper, kahawai, kingfish, blue cod, or tarakihi), and the scientific name when you can support it. Use visible features and New Zealand species knowledge. If the photo is not clearly a fish, set is_fish to false. If you cannot distinguish species reliably, say Unknown fish and use low confidence; do not guess. Give up to three plausible alternatives, confidence as high/medium/low, visible identification clues, and a brief note that this is an AI suggestion rather than a confirmed identification. Do not give catch or legal advice.",
            },
            { type: "input_image", image_url: `data:${mimeType};base64,${base64}`, detail: "high" },
          ],
        }],
        text: { format: { type: "json_schema", name: "fish_identification", strict: true, schema } },
      }),
    });

    const apiData = await apiResponse.json();
    if (!apiResponse.ok) {
      const detail = apiData?.error?.message;
      sendJson(response, apiResponse.status === 429 ? 429 : 502, {
        error: apiResponse.status === 429
          ? "The API rejected this request because of a rate or billing limit. Check your OpenAI API account."
          : "OpenAI could not identify this photo right now.",
        detail: typeof detail === "string" ? detail.slice(0, 240) : undefined,
      });
      return;
    }

    if (apiData.status === "incomplete") {
      sendJson(response, 502, { error: "The identification response was incomplete. Try again with a smaller or clearer photo." });
      return;
    }

    const outputText = apiData.output
      ?.flatMap((item) => item.type === "message" ? item.content || [] : [])
      .find((item) => item.type === "output_text")?.text;
    if (typeof outputText !== "string") {
      sendJson(response, 502, { error: "The model returned no identification. Try another clear photo." });
      return;
    }
    try {
      sendJson(response, 200, JSON.parse(outputText));
    } catch {
      sendJson(response, 502, { error: "The identification response was incomplete. Try again." });
    }
  } catch (error) {
    const message = error instanceof Error && error.name === "TimeoutError"
      ? "Identification took too long. Try again with a smaller photo."
      : error instanceof Error ? error.message : "Could not process this photo.";
    sendJson(response, 400, { error: message });
  }
});

function readJson(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxImageBytes * 2) {
        reject(new Error("That photo is too large. Choose an image under 10 MB."));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
      catch { reject(new Error("Could not read the uploaded photo.")); }
    });
    request.on("error", reject);
  });
}

function sendJson(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}

server.listen(port, "127.0.0.1", () => {
  console.log(`NZ fish ID demo running at http://127.0.0.1:${port}`);
});
