import type { Env } from "./index";
import { SHARE_ICON_PNG_BASE64 } from "./share-icon";
import { renderInvalidSharePage, renderSharePage, type ShareConfig } from "./share-page";

/**
 * Shared fishing windows. A link is `https://fishing.fishnz.space/w/<token>` where the token is the unpadded base64url of a
 * small JSON snapshot written by the Android and iPhone apps (see docs/share-fishing-window.md). The Worker stores nothing:
 * it validates the snapshot and draws it. Every field is length-limited and plain text, so a forged link can show only a
 * few lines of text, never markup, links or forms.
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

export async function handleShareRoute(request: Request, env: Env): Promise<Response | null> {
  const pathname = new URL(request.url).pathname;
  const match = pathname.match(WINDOW_ROUTE);
  const known = match || pathname === "/w" || pathname === "/w/" || pathname === "/share/icon.png"
    || pathname === "/.well-known/apple-app-site-association" || pathname === "/.well-known/assetlinks.json";
  if (!known) return null;
  if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405, headers: { allow: "GET, HEAD" } });
  if (pathname === "/share/icon.png") return icon();
  if (pathname === "/.well-known/apple-app-site-association") return appleSiteAssociation(env);
  if (pathname === "/.well-known/assetlinks.json") return androidAssetLinks(env);
  const config = shareConfig(env, request);
  const shared = match ? decodeSharedWindow(match[1]) : null;
  if (!match || !shared) return renderInvalidSharePage(config);
  return renderSharePage(shared, match[1], config);
}
