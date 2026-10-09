import type { Env } from "./index";
import { json, readJson } from "./http";
import { allowAttempt, clientAddress, HOUR, rateKey, tooManyRequests } from "./rate-limit";
import { SHARE_ICON_PNG_BASE64 } from "./share-icon";
import { renderInvalidSharePage, renderSharePage, type ShareConfig } from "./share-page";

/**
 * Shared fishing windows. A window is a small JSON snapshot written by the Android and iPhone apps and encoded as an
 * unpadded base64url token (see docs/share-fishing-window.md). It reaches people two ways:
 *   - short link `/w/<8-character id>`: the app posts the token to POST /v1/share, the Worker keeps it for 60 days and
 *     returns the id. This is what people share.
 *   - long link `/w/<token>`: the whole window is in the address, so it needs no storage. The apps use it when the
 *     Worker can't be reached, and links made before short links existed keep working.
 * Either way the Worker validates the snapshot and draws it. Every field is length-limited and plain text, so a forged
 * link can show only a few lines of text, never markup, links or forms.
 */

export type MoodPair = { emoji: string; label: string };
export type ConditionRow = { title: string; value: string } & MoodPair;
export type SharedWindow = {
  name: string;
  area: string;
  boat: boolean;
  /** Epoch seconds. */
  start: number;
  end: number;
  latitude: number | null;
  longitude: number | null;
  outlook: MoodPair | null;
  confidence: MoodPair | null;
  conditions: ConditionRow[];
  reason: string;
  sharedAt: number | null;
};

const MAX_TOKEN_LENGTH = 4096;
const MAX_CONDITIONS = 8;
const EARLIEST = Date.UTC(2024, 0, 1) / 1000;
const LATEST = Date.UTC(2100, 0, 1) / 1000;
const MAX_WINDOW_SECONDS = 24 * 3600;
const WINDOW_ROUTE = /^\/w\/([^/]+)\/?$/;
const SHARE_API_ROUTE = /^\/v1\/share(?:\/([^/]+))?\/?$/;
// 57 characters without the look-alikes 0 O 1 I l, so an id read aloud or typed from a screenshot survives.
const SHORT_ID_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const SHORT_ID = /^[2-9A-HJ-NP-Za-km-z]{8}$/;
const SHARE_LIFETIME_DAYS = 60;
const MAX_SHARE_BODY_BYTES = 8192;
const SHARES_PER_ADDRESS_PER_HOUR = 30;
const SHARES_PER_HOUR_OVERALL = 3000;

// Control characters, line/paragraph separators and bidirectional overrides never belong in a place name.
const BIDI_AND_INVISIBLE = String.fromCharCode(0x200e, 0x200f, 0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069, 0xfeff);
const UNSAFE_CHARACTERS = new RegExp(`[\\p{Cc}\\p{Zl}\\p{Zp}${BIDI_AND_INVISIBLE}]`, "gu");

function clip(text: string, max: number): string {
  const characters = [...text];
  return characters.length <= max ? text : `${characters.slice(0, max - 1).join("").trimEnd()}…`;
}

/** One line of plain text, or null when the value is not a non-empty string. */
function line(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const text = value.replace(UNSAFE_CHARACTERS, " ").replace(/\s+/g, " ").trim();
  return text ? clip(text, max) : null;
}

/** Up to three short lines; a tide row uses a second line for the reference station. */
function lines(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const parts = value.replace(/\r\n?/g, "\n").split("\n").map((part) => line(part, max)).filter((part): part is string => part !== null);
  return parts.length ? parts.slice(0, 3).join("\n") : null;
}

function pair(value: unknown): MoodPair | null {
  if (!Array.isArray(value)) return null;
  const emoji = line(value[0], 8);
  const label = line(value[1], 40);
  return emoji && label ? { emoji, label } : null;
}

function epoch(value: unknown): number | null {
  return typeof value === "number" && Number.isInteger(value) && value >= EARLIEST && value <= LATEST ? value : null;
}

function coordinate(value: unknown, limit: number): number | null {
  return typeof value === "number" && Number.isFinite(value) && Math.abs(value) <= limit ? Math.round(value * 1e4) / 1e4 : null;
}

function decodeBase64Url(token: string): string | null {
  if (!/^[A-Za-z0-9_-]{1,4096}$/.test(token) || token.length > MAX_TOKEN_LENGTH || token.length % 4 === 1) return null;
  try {
    const binary = atob(token.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(token.length / 4) * 4, "="));
    return new TextDecoder("utf-8", { fatal: true }).decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)));
  } catch {
    return null;
  }
}

/** Parse and validate a link token. Returns null for anything that is not a well-formed version 1 snapshot. */
export function decodeSharedWindow(token: string): SharedWindow | null {
  const text = decodeBase64Url(token);
  if (text === null) return null;
  let data: unknown;
  try { data = JSON.parse(text); } catch { return null; }
  if (typeof data !== "object" || data === null || Array.isArray(data)) return null;
  const record = data as Record<string, unknown>;
  if (record.v !== 1) return null;
  const name = line(record.n, 80);
  const area = line(record.a, 80);
  const start = epoch(record.s);
  const end = epoch(record.e);
  if (!name || !area || start === null || end === null || end <= start || end - start > MAX_WINDOW_SECONDS) return null;
  const latitude = coordinate(record.la, 90);
  const longitude = coordinate(record.lo, 180);
  const hasPlace = latitude !== null && longitude !== null;
  const conditions: ConditionRow[] = [];
  for (const row of Array.isArray(record.c) ? record.c.slice(0, MAX_CONDITIONS) : []) {
    if (!Array.isArray(row)) continue;
    const title = line(row[0], 24);
    const value = lines(row[1], 120);
    const mood = pair([row[2], row[3]]);
    if (title && value && mood) conditions.push({ title, value, ...mood });
  }
  return {
    name, area, boat: record.b === 1 || record.b === true, start, end,
    latitude: hasPlace ? latitude : null, longitude: hasPlace ? longitude : null,
    outlook: pair(record.o), confidence: pair(record.f), conditions,
    reason: line(record.r, 200) ?? "", sharedAt: epoch(record.t),
  };
}

function storeUrl(value: string | undefined): string {
  const url = value?.trim() ?? "";
  return /^https:\/\/[^\s"'<>]+$/.test(url) ? url : "#";
}

function shareConfig(env: Env, request: Request): ShareConfig {
  return {
    origin: env.AUTH_BASE_URL?.replace(/\/+$/, "") || new URL(request.url).origin,
    iosUrl: storeUrl(env.IOS_DOWNLOAD_URL),
    androidUrl: storeUrl(env.ANDROID_DOWNLOAD_URL),
  };
}

function appleSiteAssociation(env: Env): Response {
  const team = env.APPLE_TEAM_ID?.trim().toUpperCase() ?? "";
  const details = /^[A-Z0-9]{10}$/.test(team)
    ? [{ appIDs: [`${team}.nz.fishingnz.catchcheck`], components: [{ "/": "/w/*", comment: "Shared fishing windows" }] }]
    : [];
  return Response.json({ applinks: { details } }, { headers: { "cache-control": "public, max-age=3600" } });
}

function androidAssetLinks(env: Env): Response {
  const fingerprints = (env.ANDROID_CERT_SHA256 ?? "").split(",").map((value) => value.trim().toUpperCase())
    .filter((value) => /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(value));
  const links = fingerprints.length
    ? [{ relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: "nz.fishingnz.app", sha256_cert_fingerprints: fingerprints } }]
    : [];
  return Response.json(links, { headers: { "cache-control": "public, max-age=3600" } });
}

function icon(): Response {
  return new Response(Uint8Array.from(atob(SHARE_ICON_PNG_BASE64), (char) => char.charCodeAt(0)), {
    headers: { "content-type": "image/png", "cache-control": "public, max-age=86400", "x-content-type-options": "nosniff" },
  });
}

function randomShareId(): string {
  // Rejection sampling keeps every character equally likely.
  const limit = 256 - (256 % SHORT_ID_ALPHABET.length);
  let id = "";
  while (id.length < 8) {
    for (const byte of crypto.getRandomValues(new Uint8Array(16))) {
      if (byte < limit && id.length < 8) id += SHORT_ID_ALPHABET[byte % SHORT_ID_ALPHABET.length];
    }
  }
  return id;
}

async function createShare(request: Request, env: Env, config: ShareConfig): Promise<Response> {
  if (Number(request.headers.get("content-length") || 0) > MAX_SHARE_BODY_BYTES) return json({ error: "That window is too large to share.", code: "too_large" }, 413);
  // Counted before anything else, so invalid requests use up the allowance too.
  const allowed = await allowAttempt(env.RULES_DB, { key: await rateKey("share", clientAddress(request)), limit: SHARES_PER_ADDRESS_PER_HOUR }, HOUR)
    && await allowAttempt(env.RULES_DB, { key: "share:all", limit: SHARES_PER_HOUR_OVERALL }, HOUR);
  if (!allowed) return tooManyRequests("Too many windows shared just now. Try again later.", HOUR);
  const body = await readJson(request, MAX_SHARE_BODY_BYTES);
  const token = typeof body?.token === "string" ? body.token : "";
  if (!decodeSharedWindow(token)) return json({ error: "This window can't be shared.", code: "invalid_window" }, 400);
  const now = Date.now();
  const expiresAt = new Date(now + SHARE_LIFETIME_DAYS * 86_400_000).toISOString();
  for (let attempt = 0; attempt < 5; attempt++) {
    const id = randomShareId();
    try {
      await env.RULES_DB.prepare("INSERT INTO shared_windows (id, token, created_at, expires_at) VALUES (?, ?, ?, ?)")
        .bind(id, token, new Date(now).toISOString(), expiresAt).run();
      return json({ id, url: `${config.origin}/w/${id}`, expires_at: expiresAt }, 201);
    } catch (error) {
      if (!String(error).includes("UNIQUE")) throw error;
    }
  }
  return json({ error: "Could not make a link just now. Try again.", code: "unavailable" }, 503);
}

async function storedToken(env: Env, id: string): Promise<string | null> {
  const row = await env.RULES_DB.prepare("SELECT token FROM shared_windows WHERE id = ? AND expires_at > ?")
    .bind(id, new Date().toISOString()).first<{ token: string }>();
  return row?.token ?? null;
}

async function handleShareApi(request: Request, env: Env, id: string | undefined): Promise<Response> {
  if (id === undefined) {
    if (request.method !== "POST") return json({ error: "Use POST to share a window." }, 405);
    return createShare(request, env, shareConfig(env, request));
  }
  if (request.method !== "GET") return json({ error: "Use GET to read a shared window." }, 405);
  const token = SHORT_ID.test(id) ? await storedToken(env, id) : null;
  if (!token) return json({ error: "This shared window is no longer available.", code: "not_found" }, 404);
  return json({ token });
}

export async function handleShareRoute(request: Request, env: Env): Promise<Response | null> {
  const pathname = new URL(request.url).pathname;
  const api = pathname.match(SHARE_API_ROUTE);
  if (api) return handleShareApi(request, env, api[1]);
  const match = pathname.match(WINDOW_ROUTE);
  const known = match || pathname === "/w" || pathname === "/w/" || pathname === "/share/icon.png"
    || pathname === "/.well-known/apple-app-site-association" || pathname === "/.well-known/assetlinks.json";
  if (!known) return null;
  if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405, headers: { allow: "GET, HEAD" } });
  if (pathname === "/share/icon.png") return icon();
  if (pathname === "/.well-known/apple-app-site-association") return appleSiteAssociation(env);
  if (pathname === "/.well-known/assetlinks.json") return androidAssetLinks(env);
  const config = shareConfig(env, request);
  if (!match) return renderInvalidSharePage(config, "damaged");
  if (SHORT_ID.test(match[1])) {
    let token: string | null;
    try { token = await storedToken(env, match[1]); } catch (error) {
      console.error("Could not read a shared window", String(error));
      return renderInvalidSharePage(config, "unavailable");
    }
    const stored = token ? decodeSharedWindow(token) : null;
    return stored ? renderSharePage(stored, match[1], config) : renderInvalidSharePage(config, "gone");
  }
  const shared = decodeSharedWindow(match[1]);
  return shared ? renderSharePage(shared, match[1], config) : renderInvalidSharePage(config, "damaged");
}
