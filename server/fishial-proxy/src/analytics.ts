import type { Env } from "./index";
import { corsHeaders, json, readJson } from "./http";
import { HOUR, allowAttempt, clientAddress, rateKey, tooManyRequests } from "./rate-limit";

export const DEVICE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const PLATFORMS = new Set(["ios", "android", "web"]);
const MAX_EVENTS = 50;
const MAX_BODY_BYTES = 32_768;
const MAX_OFFSET_MS = 24 * 60 * 60 * 1000;
const MAX_PROPS = 8;

/** Behaviour events the apps may report. Anything else is dropped, so a client cannot invent new data. */
export const EVENT_NAMES = new Set([
  "app_open", "screen_view", "search_run", "spot_opened",
  "fish_identify_started", "fish_identify_succeeded", "fish_identify_failed",
  "rules_area_selected", "tide_station_selected", "weather_viewed", "place_conditions_opened",
  "sign_in_succeeded", "sign_in_failed", "sign_up_succeeded", "social_sign_in_started", "sign_out",
  "password_reset_opened", "feedback_sent", "permission_result",
]);

/** The only properties an event may carry. Free text, coordinates and addresses have no slot. */
const PROP_KEYS = new Set([
  "screen", "mode", "radius_km", "date_preset", "result_count", "origin", "spot_id", "area_id", "station_id",
  "outcome", "error_code", "method", "is_fish", "confidence_level", "permission", "granted", "history_days", "location_kind",
]);

export type Props = Record<string, string | number | boolean>;
export type DeviceInfo = {
  id: string; platform: string; os_version: string; device_model: string; app_version: string; locale: string; time_zone: string;
};
export type AnalyticsEvent = { name: string; props: Props; offsetMs: number };

function text(value: unknown, max: number): string {
  return typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, max) : "";
}

export function parseDevice(value: unknown): DeviceInfo | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const device = value as Record<string, unknown>;
  const id = text(device.id, 36).toLowerCase();
  const platform = text(device.platform, 16).toLowerCase();
  if (!DEVICE_ID_PATTERN.test(id) || !PLATFORMS.has(platform)) return null;
  return {
    id, platform,
    os_version: text(device.os_version, 32), device_model: text(device.device_model, 64), app_version: text(device.app_version, 32),
    locale: text(device.locale, 35), time_zone: text(device.time_zone, 64),
  };
}

export function cleanProps(value: unknown): Props {
  const props: Props = {};
  if (typeof value !== "object" || value === null || Array.isArray(value)) return props;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (Object.keys(props).length >= MAX_PROPS) break;
    if (!PROP_KEYS.has(key)) continue;
    if (typeof raw === "string") {
      const cleaned = text(raw, 64);
      if (cleaned) props[key] = cleaned;
    } else if (typeof raw === "number" && Number.isFinite(raw)) {
      props[key] = Math.max(-1e9, Math.min(1e9, Math.round(raw * 100) / 100));
    } else if (typeof raw === "boolean") {
      props[key] = raw;
    }
  }
  return props;
}

/** Null when the list is missing or the wrong size; unknown event names are counted and dropped. */
export function parseEvents(value: unknown): { events: AnalyticsEvent[]; dropped: number } | null {
  if (!Array.isArray(value) || value.length < 1 || value.length > MAX_EVENTS) return null;
  const events: AnalyticsEvent[] = [];
  let dropped = 0;
  for (const item of value) {
    const entry = (typeof item === "object" && item !== null ? item : {}) as Record<string, unknown>;
    const name = text(entry.name, 48);
    if (!EVENT_NAMES.has(name)) { dropped++; continue; }
    const offset = typeof entry.offset_ms === "number" && Number.isFinite(entry.offset_ms) ? entry.offset_ms : 0;
    events.push({ name, props: cleanProps(entry.props), offsetMs: Math.max(0, Math.min(MAX_OFFSET_MS, Math.round(offset))) });
  }
  return { events, dropped };
}

/**
 * POST /v1/analytics/batch: register or refresh a device and store its behaviour events.
 * Works without an account. A valid Bearer token additionally links the device and events to that account.
 */
export async function ingestAnalytics(request: Request, env: Env, userId: string | null): Promise<Response> {
  const payload = await readJson(request, MAX_BODY_BYTES);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  const device = parseDevice(payload.device);
  if (!device) return json({ error: "Send a device with a UUID id and a platform of ios, android or web." }, 400);
  const parsed = parseEvents(payload.events);
  if (!parsed) return json({ error: `Send between 1 and ${MAX_EVENTS} events.` }, 400);
  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("analytics-device", device.id), limit: 60 }, HOUR)
    || !await allowAttempt(env.RULES_DB, { key: await rateKey("analytics-ip", clientAddress(request)), limit: 1000 }, HOUR)) {
    return tooManyRequests("Too many analytics uploads. Try again later.", HOUR, corsHeaders());
  }
  const nowMs = Date.now();
  const now = new Date(nowMs).toISOString();
  await env.RULES_DB.batch([
    env.RULES_DB.prepare(
      `INSERT INTO analytics_devices (device_id, user_id, platform, os_version, device_model, app_version, locale, time_zone, first_seen_at, last_seen_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET user_id = COALESCE(excluded.user_id, analytics_devices.user_id), platform = excluded.platform,
         os_version = excluded.os_version, device_model = excluded.device_model, app_version = excluded.app_version,
         locale = excluded.locale, time_zone = excluded.time_zone, last_seen_at = excluded.last_seen_at`
    ).bind(device.id, userId, device.platform, device.os_version, device.device_model, device.app_version, device.locale, device.time_zone, now, now),
    ...parsed.events.map((event) =>
      env.RULES_DB.prepare("INSERT INTO analytics_events (id, device_id, user_id, event_name, props_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(crypto.randomUUID(), device.id, userId, event.name, JSON.stringify(event.props), new Date(nowMs - event.offsetMs).toISOString())),
  ]);
  return json({ accepted: parsed.events.length, dropped: parsed.dropped }, 202);
}

/** DELETE /v1/analytics/device: erase everything stored for the x-device-id (used when a person turns analytics off). */
export async function deleteDeviceAnalytics(request: Request, env: Env): Promise<Response> {
  const id = (request.headers.get("x-device-id") || "").toLowerCase();
  if (!DEVICE_ID_PATTERN.test(id)) return json({ error: "Send the device id in the x-device-id header." }, 400);
  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("analytics-delete-ip", clientAddress(request)), limit: 60 }, HOUR)) {
    return tooManyRequests("Too many requests. Try again later.", HOUR, corsHeaders());
  }
  await env.RULES_DB.batch([
    env.RULES_DB.prepare("DELETE FROM analytics_events WHERE device_id = ?").bind(id),
    env.RULES_DB.prepare("DELETE FROM api_usage_daily WHERE device_id = ?").bind(id),
    env.RULES_DB.prepare("DELETE FROM analytics_devices WHERE device_id = ?").bind(id),
  ]);
  return json({ deleted: true });
}
