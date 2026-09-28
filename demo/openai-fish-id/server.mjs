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
    subject_name: { type: "string" },
    scientific_name: { type: "string" },
    confidence: { type: "string", enum: ["high", "medium", "low"] },
    other_possibilities: { type: "array", items: { type: "string" } },
    visible_clues: { type: "string" },
    note: { type: "string" },
  },
  required: ["is_fish", "common_name_nz", "subject_name", "scientific_name", "confidence", "other_possibilities", "visible_clues", "note"],
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
              text: "Identify the main subject of this photo for a New Zealand angler. If it is a fish, put the most likely specific common name in common_name_nz even if it is uncommon in New Zealand. Prefer the familiar NZ name when available, otherwise a widely understood name. Do not use 'Unknown fish' when a plausible leading name can be given; show uncertainty with low confidence and alternatives. Give a scientific name only when supported by visible features. Image captions are clues, not proof. If it is not a fish, set is_fish false, leave common_name_nz and scientific_name empty, and give the subject's concise common name in subject_name, such as 'Sea star (starfish)'. For fish, subject_name should match common_name_nz. Give up to three distinct alternative fish names in likelihood order, excluding the leading name. Describe visible clues and uncertainty in plain language. Do not give catch or legal advice. This is an AI suggestion, not a confirmed identification.",
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
