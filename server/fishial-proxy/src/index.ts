export interface Env {
  OPENAI_API_KEY: string;
  RULES_DB: D1Database;
  RULES_INGEST_TOKEN: string;
}

const rulesAreas = new Map([
  ["auckland-kermadec", "Auckland / Kermadec"], ["central", "Central"],
  ["challenger", "Challenger"], ["south-east", "South-East"],
  ["southland", "Southland"], ["kaikoura", "Kaikōura"],
  ["chatham-rise", "Chatham Rise"], ["fiordland", "Fiordland"],
]);
const rulesSlugs: Record<string, string> = {
  "auckland-kermadec": "auckland-kermadec-fishing-rules",
  central: "central-fishing-rules",
  challenger: "challenger-fishing-rules",
  "south-east": "south-east-fishing-rules",
  southland: "southland-fishing-rules",
  kaikoura: "kaikoura-fishing-rules",
  "chatham-rise": "chatham-rise-area-recreational-fishing-rules",
  fiordland: "fiordland-marine-area-fishing-rules",
};

type OpenAIFishIdentification = {
  is_fish: boolean;
  common_name_nz: string;
  scientific_name: string;
  confidence: number;
  other_possibilities: string[];
  visible_clues: string;
  note: string;
};

const openAIFishSchema = {
  type: "object",
  properties: {
    is_fish: { type: "boolean" },
    common_name_nz: { type: "string" },
    scientific_name: { type: "string" },
    confidence: { type: "number" },
    other_possibilities: { type: "array", items: { type: "string" } },
    visible_clues: { type: "string" },
    note: { type: "string" },
  },
  required: ["is_fish", "common_name_nz", "scientific_name", "confidence", "other_possibilities", "visible_clues", "note"],
  additionalProperties: false,
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
    const pathname = new URL(request.url).pathname;
    if (pathname === "/__health") return json({ ok: true });
    if (pathname === "/v1/rules/status" && request.method === "GET") return await getCrawlStatus(env);
    if (pathname === "/v1/rules" && request.method === "GET") return await getRules(request, env);
    if (pathname === "/v1/rules/import" && request.method === "POST") return await importRules(request, env);
    if (pathname === "/v1/rules/source" && request.method === "POST") return await getMpiSource(request, env);
    if (request.method !== "POST" || new URL(request.url).pathname !== "/v1/fish/identify") {
      return json({ error: "Not found" }, 404);
    }
    const contentType = request.headers.get("content-type") || "";
    const contentLength = Number(request.headers.get("content-length") || 0);
    if (!contentType.startsWith("image/") || contentLength > 20 * 1024 * 1024) {
      return json({ error: "Upload a JPEG, PNG, or other image smaller than 20 MB." }, 400);
    }

    try {
      if (!env.OPENAI_API_KEY) return json({ error: "Fish identification is not configured. Add the OpenAI API key to the Worker secrets." }, 503);
      const image = await request.arrayBuffer();
      if (image.byteLength === 0 || image.byteLength > 20 * 1024 * 1024) {
        return json({ error: "Upload an image smaller than 20 MB." }, 400);
      }
      const identification = await identifyFishWithOpenAI(image, contentType, env.OPENAI_API_KEY);
      if (!identification.is_fish) return json({ error: "No fish could be identified in this photo." }, 422);
      const commonName = identification.common_name_nz || "Unknown fish";
      const point = parseLocation(request.headers.get("x-location-lat-lon"));
      const areaId = rulesAreaForLocation(point.latitude, point.longitude);
      const rulePage = await env.RULES_DB.prepare("SELECT area_name, source_url, reviewed_at, tables_json FROM mpi_fishing_rules WHERE area_id = ?")
        .bind(areaId).first<{ area_name: string; source_url: string; reviewed_at: string | null; tables_json: string }>();
      const rules = rulePage ? findFishRules(rulePage.tables_json, commonName) : [];
      return json({
        commonName,
        scientificName: identification.scientific_name || "",
        confidence: Math.max(0, Math.min(1, Number(identification.confidence) || 0)),
        confidenceLevel: confidenceLevel(identification.confidence),
        otherPossibilities: identification.other_possibilities,
        visibleClues: identification.visible_clues,
        identificationNote: identification.note,
        areaId,
        areaName: rulePage?.area_name || rulesAreas.get(areaId),
        areaEstimated: request.headers.get("x-location-source") !== "device",
        rulesReviewedAt: rulePage?.reviewed_at || null,
        rulesSourceUrl: rulePage?.source_url || null,
        fishRules: rules,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Fish identification is temporarily unavailable.";
      return json({ error: message }, message.startsWith("OpenAI API usage limit") ? 429 : 502);
    }
    } catch (error) {
      return json({ error: "Internal Worker error.", detail: String(error) }, 500);
    }
  },
  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    const areaIds = [...rulesAreas.keys()];
    const day = Math.floor(controller.scheduledTime / 86_400_000);
    const areaId = areaIds[day % areaIds.length];
    const refreshed = await refreshRuleArea(areaId, env);
    if (!refreshed) throw new Error(`MPI rules refresh failed for ${areaId}. See /v1/rules/status.`);
  },
};

async function identifyFishWithOpenAI(image: ArrayBuffer, contentType: string, apiKey: string): Promise<OpenAIFishIdentification> {
  if (!apiKey) throw new Error("Fish identification is not configured yet. Add the OpenAI API key to the Worker secrets.");
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: "gpt-5.6-luna",
      reasoning: { effort: "none" },
      max_output_tokens: 800,
      input: [{
        role: "user",
        content: [
          {
            type: "input_text",
            text: "Identify the fish for a New Zealand angler. Prefer the familiar New Zealand common name (for example snapper, kahawai, kingfish, blue cod, or tarakihi), then give the scientific name if you can support it. Use visible features and NZ species knowledge. Do not invent a species. If this is not clearly a fish, set is_fish false. If the species cannot be distinguished, use common_name_nz 'Unknown fish', an empty scientific_name, and low confidence. Return confidence from 0 to 1 conservatively: 1 means unmistakable visible features; 0.5 means uncertain. Give up to three alternatives and briefly state visible clues and uncertainty. Do not give catch or legal advice. This is an AI suggestion, not a confirmed identification.",
          },
          { type: "input_image", image_url: `data:${contentType};base64,${arrayBufferToBase64(image)}`, detail: "high" },
        ],
      }],
      text: { format: { type: "json_schema", name: "fish_identification", strict: true, schema: openAIFishSchema } },
    }),
  });
  const payload = await response.json<{ error?: { message?: string }; status?: string; output?: Array<{ type?: string; content?: Array<{ type?: string; text?: string }> }> }>();
  if (!response.ok) {
    if (response.status === 429) throw new Error("OpenAI API usage limit reached. Check the API account's billing and limits.");
    throw new Error("Fish identification is temporarily unavailable.");
  }
  if (payload.status === "incomplete") throw new Error("The fish identification response was incomplete. Please try again.");
  const resultText = payload.output?.flatMap((item) => item.type === "message" ? item.content || [] : []).find((item) => item.type === "output_text")?.text;
  if (!resultText) throw new Error("No fish species could be identified in this photo.");
  let result: OpenAIFishIdentification;
  try { result = JSON.parse(resultText) as OpenAIFishIdentification; }
  catch { throw new Error("The fish identification response was incomplete. Please try again."); }
  if (typeof result.is_fish !== "boolean" || typeof result.common_name_nz !== "string" || typeof result.confidence !== "number") {
    throw new Error("The fish identification response was invalid. Please try again.");
  }
  return result;
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function confidenceLevel(value: number): string {
  return value >= 0.8 ? "high" : value >= 0.5 ? "medium" : "low";
}

type RulePage = {
  area_id: string;
  area_name: string;
  source_url: string;
  page_title: string;
  reviewed_at: string | null;
  fetched_at: string;
  content_sha256: string;
  page_text: string;
  sections_json: string;
  tables_json: string;
  page_html?: string;
};

type FishRuleDetail = { label: string; value: string };
type FishRuleMatch = { species: string; dailyLimit: string | null; minimumSize: string | null; details: FishRuleDetail[] };

function parseLocation(value: string | null): { latitude: number; longitude: number } {
  if (value) {
    const [latitude, longitude] = value.split(",").map(Number);
    if (Number.isFinite(latitude) && Number.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) return { latitude, longitude };
  }
  return { latitude: -36.85, longitude: 174.76 };
}

function rulesAreaForLocation(latitude: number, longitude: number): string {
  if (longitude < -175 && latitude < -40 && latitude > -49) return "chatham-rise";
  if (latitude <= -44.5 && longitude >= 166 && longitude <= 168.8) return "fiordland";
  if (latitude <= -46.3) return "southland";
  if (latitude < -42.4 && latitude > -44 && longitude >= 172.2 && longitude <= 174.4) return "kaikoura";
  if (latitude <= -40 && latitude >= -46.3 && longitude < 171) return "challenger";
  if (latitude <= -42.5 && latitude > -46.3 && longitude >= 171) return "south-east";
  if (latitude > -37.7) return "auckland-kermadec";
  if (latitude > -41.6) return "central";
  return "challenger";
}

function findFishRules(tablesJson: string, commonName: string): FishRuleMatch[] {
  const tables = JSON.parse(tablesJson) as string[][][];
  const normalize = (value: string) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const target = normalize(commonName);
  if (!target) return [];
  const found = new Map<string, FishRuleMatch>();
  for (const table of tables) {
    const headers = table[0];
    if (!headers || table.length < 2) continue;
    const dailyIndex = headers.findIndex((header) => /daily|bag|catch/i.test(header) && /limit|maximum|take/i.test(header));
    const sizeIndex = headers.findIndex((header) => /min(imum)?.*(size|length)|legal.*(size|length)/i.test(header));
    for (const row of table.slice(1)) {
      const species = row[0] || "";
      const candidate = normalize(species);
      if (!candidate || !(candidate === target || candidate.startsWith(`${target} `) || candidate.includes(` ${target} `))) continue;
      const dailyLimit = dailyIndex >= 0 ? row[dailyIndex]?.trim() || null : null;
      const minimumSize = sizeIndex >= 0 ? row[sizeIndex]?.trim() || null : null;
      const details = headers.flatMap((header, index) => {
        if (index === 0 || index === dailyIndex || index === sizeIndex) return [];
        const value = row[index]?.trim();
        return value && value !== "—" ? [{ label: header.trim(), value }] : [];
      });
      const item = { species, dailyLimit, minimumSize, details };
      found.set(JSON.stringify(item), item);
    }
  }
  return [...found.values()].slice(0, 8);
}

async function getMpiSource(request: Request, env: Env): Promise<Response> {
  if (!env.RULES_INGEST_TOKEN || request.headers.get("x-rules-ingest-token") !== env.RULES_INGEST_TOKEN) {
    return json({ error: "Unauthorized." }, 401);
  }
  let payload: { area_id?: unknown };
  try { payload = await request.json(); } catch { return json({ error: "Expected a JSON object with area_id." }, 400); }
  if (typeof payload.area_id !== "string" || !rulesAreas.has(payload.area_id)) return json({ error: "Unknown fishing area." }, 400);
  const url = `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(payload.area_id)}`;
  let response: Response;
  try {
    response = await fetch(url, { headers: { "user-agent": "CatchCheckNZ-rules-crawler/1.0", accept: "text/html" } });
  } catch (error) {
    return json({ error: "Could not fetch the MPI rules page.", detail: String(error) }, 502);
  }
  const body = await response.text();
  const contentType = response.headers.get("content-type") || "";
  const blockedPage = /ROBOTS[^>]*NOINDEX|Request unsuccessful|incident ID/i.test(body);
  if (!response.ok || !contentType.includes("text/html") || body.length < 500 || blockedPage) {
    return json({ error: `MPI did not return a crawlable rules page for ${payload.area_id}.`, status: response.status, contentType, bodyLength: body.length, blockedPage }, 502);
  }
  return new Response(body, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

async function getRules(request: Request, env: Env): Promise<Response> {
  const area = new URL(request.url).searchParams.get("area");
  const query = area
    ? await env.RULES_DB.prepare("SELECT * FROM mpi_fishing_rules WHERE area_id = ?").bind(area).all<RulePage>()
    : await env.RULES_DB.prepare("SELECT * FROM mpi_fishing_rules ORDER BY area_name").all<RulePage>();
  if (area && !rulesAreas.has(area)) return json({ error: "Unknown fishing area." }, 400);
  if (area && query.results.length === 0) return json({ error: "No cached rules for this area yet." }, 404);
  const results = query.results.map((row) => ({
    ...row,
    sections: JSON.parse(row.sections_json),
    tables: JSON.parse(row.tables_json),
    sections_json: undefined,
    tables_json: undefined,
  }));
  return json({ source: "Fisheries New Zealand (MPI)", count: results.length, rules: results });
}

async function getCrawlStatus(env: Env): Promise<Response> {
  const query = await env.RULES_DB.prepare("SELECT * FROM mpi_rules_crawl_status ORDER BY last_attempt_at DESC").all();
  return json({ schedule: "daily at 16:00 UTC; one area per run", areas: query.results });
}

async function importRules(request: Request, env: Env): Promise<Response> {
  if (!env.RULES_INGEST_TOKEN || request.headers.get("x-rules-ingest-token") !== env.RULES_INGEST_TOKEN) {
    return json({ error: "Unauthorized." }, 401);
  }
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > 4 * 1024 * 1024) return json({ error: "Import is too large." }, 413);
  let payload: unknown;
  try { payload = await request.json(); } catch { return json({ error: "Expected a JSON array of rule pages." }, 400); }
  if (!Array.isArray(payload) || payload.length === 0 || payload.length > rulesAreas.size) {
    return json({ error: "Expected one or more rule pages." }, 400);
  }

  const pages: RulePage[] = [];
  const seen = new Set<string>();
  for (const candidate of payload) {
    if (!candidate || typeof candidate !== "object") return json({ error: "Invalid rule page." }, 400);
    const row = candidate as Partial<RulePage>;
    const expectedName = rulesAreas.get(row.area_id || "");
    const expectedUrl = row.area_id ? `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(row.area_id)}` : "";
    if (!expectedName || seen.has(row.area_id!) || row.area_name !== expectedName || row.source_url !== expectedUrl ||
      typeof row.page_title !== "string" || typeof row.fetched_at !== "string" ||
      typeof row.content_sha256 !== "string" || !/^[a-f0-9]{64}$/.test(row.content_sha256) ||
      typeof row.page_text !== "string" || row.page_text.length < 500 ||
      typeof row.sections_json !== "string" || typeof row.tables_json !== "string" ||
      (row.page_html !== undefined && (typeof row.page_html !== "string" || row.page_html.length > 1_800_000))) {
      return json({ error: "Invalid or incomplete MPI rule data." }, 400);
    }
    try { JSON.parse(row.sections_json); JSON.parse(row.tables_json); } catch { return json({ error: "Rule sections and tables must be valid JSON." }, 400); }
    seen.add(row.area_id!);
    pages.push(row as RulePage);
  }

  const statements = pages.flatMap((row) => [
    env.RULES_DB.prepare(
      `INSERT INTO mpi_fishing_rules
        (area_id, area_name, source_url, page_title, reviewed_at, fetched_at, content_sha256, page_text, sections_json, tables_json, page_html)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(area_id) DO UPDATE SET
          area_name=excluded.area_name, source_url=excluded.source_url, page_title=excluded.page_title,
          reviewed_at=excluded.reviewed_at, fetched_at=excluded.fetched_at, content_sha256=excluded.content_sha256,
          page_text=excluded.page_text, sections_json=excluded.sections_json, tables_json=excluded.tables_json,
          page_html=excluded.page_html`
    ).bind(row.area_id, row.area_name, row.source_url, row.page_title, row.reviewed_at || null, row.fetched_at,
      row.content_sha256, row.page_text, row.sections_json, row.tables_json, row.page_html || ""),
    env.RULES_DB.prepare(
      `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
       VALUES (?, ?, ?, ?, 'success', 200, NULL)
       ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name,
         last_attempt_at=excluded.last_attempt_at, last_success_at=excluded.last_success_at,
         status='success', http_status=200, error=NULL`
    ).bind(row.area_id, row.area_name, row.fetched_at, row.fetched_at),
  ]);
  await env.RULES_DB.batch(statements);
  return json({ saved: pages.length, areas: pages.map((row) => row.area_id) });
}

function areaSlug(id: string): string {
  return rulesSlugs[id] || "";
}

async function refreshRuleArea(areaId: string, env: Env): Promise<boolean> {
  const areaName = rulesAreas.get(areaId);
  if (!areaName) return false;
  const attemptedAt = new Date().toISOString();
  const url = `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(areaId)}`;
  try {
    const response = await fetch(url, { headers: { "user-agent": "CatchCheckNZ-rules-crawler/1.0", accept: "text/html" } });
    const html = await response.text();
    const contentType = response.headers.get("content-type") || "";
    const blocked = /NOINDEX|Request unsuccessful|incident ID|Access denied/i.test(html);
    if (!response.ok || !contentType.includes("text/html") || html.length < 500 || html.length > 1_800_000 || blocked) {
      const reason = blocked ? "MPI returned an anti-bot/interstitial page." : "MPI returned an invalid or oversized page.";
      await saveCrawlFailure(env, areaId, areaName, attemptedAt, response.status, reason);
      return false;
    }

    const title = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]?.replace(/<[^>]+>/g, " ").trim() || `${areaName} fishing rules`;
    const review = html.match(/Last reviewed\s*:?\s*([^<\n]{4,30})/i)?.[1]?.trim() || null;
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(html));
    const contentHash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const sourceText = `${title}\nOfficial source: ${url}`;
    await env.RULES_DB.batch([
      env.RULES_DB.prepare(
        `INSERT INTO mpi_fishing_rules
          (area_id, area_name, source_url, page_title, reviewed_at, fetched_at, content_sha256, page_text, sections_json, tables_json, page_html)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, source_url=excluded.source_url,
           page_title=excluded.page_title, reviewed_at=excluded.reviewed_at, fetched_at=excluded.fetched_at,
           content_sha256=excluded.content_sha256, page_text=excluded.page_text,
           sections_json=excluded.sections_json, tables_json=excluded.tables_json, page_html=excluded.page_html`
      ).bind(areaId, areaName, url, title, review, attemptedAt, contentHash, sourceText,
        JSON.stringify([{ heading: title, text: sourceText }]), "[]", html),
      env.RULES_DB.prepare(
        `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
         VALUES (?, ?, ?, ?, 'success', ?, NULL)
         ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, last_attempt_at=excluded.last_attempt_at,
           last_success_at=excluded.last_success_at, status='success', http_status=excluded.http_status, error=NULL`
      ).bind(areaId, areaName, attemptedAt, attemptedAt, response.status),
    ]);
    return true;
  } catch (error) {
    await saveCrawlFailure(env, areaId, areaName, attemptedAt, null, String(error).slice(0, 500));
    return false;
  }
}

async function saveCrawlFailure(env: Env, areaId: string, areaName: string, attemptedAt: string, httpStatus: number | null, error: string): Promise<void> {
  await env.RULES_DB.prepare(
    `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
     VALUES (?, ?, ?, NULL, 'failed', ?, ?)
     ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, last_attempt_at=excluded.last_attempt_at,
       status='failed', http_status=excluded.http_status, error=excluded.error`
  ).bind(areaId, areaName, attemptedAt, httpStatus, error).run();
}

function json(body: object, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } });
}
