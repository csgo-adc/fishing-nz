import type { Env } from "./index";
import { DEVICE_ID_PATTERN } from "./analytics";
import { json, readLimit, readOffset } from "./http";
import { identificationQuota } from "./identification-quota";

type Row = Record<string, unknown>;

const DAY_MS = 86_400_000;
const PREFIX = "/v1/admin/analytics/";

// ORDER BY fragments come from these tables only; query-string values never reach the SQL text.
const USER_SORTS: Record<string, string> = {
  calls: "api_calls DESC, u.created_at DESC",
  events: "events DESC, u.created_at DESC",
  recent: "last_event_at DESC, u.created_at DESC",
  newest: "u.created_at DESC",
};
const DEVICE_SORTS: Record<string, string> = {
  calls: "api_calls DESC, d.last_seen_at DESC",
  events: "events DESC, d.last_seen_at DESC",
  recent: "d.last_seen_at DESC",
  newest: "d.first_seen_at DESC",
};

function windowFrom(url: URL) {
  const days = readLimit(url.searchParams.get("days"), 30, 90);
  const since = new Date(Date.now() - days * DAY_MS);
  return { days, sinceIso: since.toISOString(), sinceDay: since.toISOString().slice(0, 10) };
}

function parseProps(value: unknown): Record<string, unknown> {
  try { return JSON.parse(String(value || "{}")) as Record<string, unknown>; } catch { return {}; }
}

function eventRows(rows: Row[]) {
  return rows.map(({ props_json, ...event }) => ({ ...event, props: parseProps(props_json) }));
}

/** Handles GET /v1/admin/analytics/{overview,subjects,users/:id,devices/:id}. The caller has already checked the admin token. */
export async function handleAdminAnalytics(request: Request, env: Env, pathname: string): Promise<Response | null> {
  if (!pathname.startsWith(PREFIX)) return null;
  if (request.method !== "GET") return json({ error: "Not found" }, 404);
  const url = new URL(request.url);
  const rest = pathname.slice(PREFIX.length);
  if (rest === "overview") return overview(env, url);
  if (rest === "subjects") return subjects(env, url);
  const detail = rest.match(/^(users|devices)\/([^/]+)$/);
  if (detail) {
    const id = decodeURIComponent(detail[2]).toLowerCase();
    if (!DEVICE_ID_PATTERN.test(id)) return json({ error: "Not found" }, 404);
    return detail[1] === "users" ? userDetail(env, url, id) : deviceDetail(env, url, id);
  }
  return null;
}

async function overview(env: Env, url: URL): Promise<Response> {
  const { days, sinceIso, sinceDay } = windowFrom(url);
  const db = env.RULES_DB;
  const [activity, usage, eventDays, usageDays, byEvent, screens, byRoute, devices] = await Promise.all([
    db.prepare(
      `SELECT COUNT(DISTINCT e.device_id) AS devices, COUNT(DISTINCT e.user_id) AS users, COUNT(*) AS events,
              COUNT(DISTINCT CASE WHEN d.user_id IS NULL THEN e.device_id END) AS anonymous_devices
       FROM analytics_events e JOIN analytics_devices d ON d.device_id = e.device_id WHERE e.occurred_at >= ?`
    ).bind(sinceIso).first<Row>(),
    db.prepare("SELECT COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(errors), 0) AS errors FROM api_usage_daily WHERE day >= ?").bind(sinceDay).first<Row>(),
    db.prepare(
      `SELECT substr(occurred_at, 1, 10) AS day, COUNT(*) AS events, COUNT(DISTINCT device_id) AS devices, COUNT(DISTINCT user_id) AS users
       FROM analytics_events WHERE occurred_at >= ? GROUP BY day ORDER BY day`
    ).bind(sinceIso).all<Row>(),
    db.prepare("SELECT day, SUM(calls) AS api_calls, SUM(errors) AS api_errors FROM api_usage_daily WHERE day >= ? GROUP BY day ORDER BY day").bind(sinceDay).all<Row>(),
    db.prepare(
      `SELECT event_name, COUNT(*) AS events, COUNT(DISTINCT device_id) AS devices FROM analytics_events
       WHERE occurred_at >= ? GROUP BY event_name ORDER BY events DESC`
    ).bind(sinceIso).all<Row>(),
    db.prepare(
      `SELECT json_extract(props_json, '$.screen') AS screen, COUNT(*) AS views, COUNT(DISTINCT device_id) AS devices
       FROM analytics_events WHERE event_name = 'screen_view' AND occurred_at >= ? GROUP BY screen ORDER BY views DESC LIMIT 20`
    ).bind(sinceIso).all<Row>(),
    db.prepare(
      `SELECT route, SUM(calls) AS calls, SUM(errors) AS errors,
              COUNT(DISTINCT CASE WHEN user_id != '' THEN user_id END) AS users,
              COUNT(DISTINCT CASE WHEN device_id != '' THEN device_id END) AS devices
       FROM api_usage_daily WHERE day >= ? GROUP BY route ORDER BY calls DESC`
    ).bind(sinceDay).all<Row>(),
    db.prepare(
      `SELECT platform, app_version, COUNT(*) AS devices FROM analytics_devices WHERE last_seen_at >= ?
       GROUP BY platform, app_version ORDER BY devices DESC LIMIT 30`
    ).bind(sinceIso).all<Row>(),
  ]);
  const daily = new Map<string, Row>();
  for (const row of eventDays.results) daily.set(String(row.day), { ...row, api_calls: 0, api_errors: 0 });
  for (const row of usageDays.results) daily.set(String(row.day), { events: 0, devices: 0, users: 0, ...daily.get(String(row.day)), ...row });
  return json({
    range_days: days,
    totals: {
      active_devices: activity?.devices ?? 0, anonymous_devices: activity?.anonymous_devices ?? 0, active_users: activity?.users ?? 0,
      events: activity?.events ?? 0, api_calls: usage?.calls ?? 0, api_errors: usage?.errors ?? 0,
    },
    daily: [...daily.values()].sort((a, b) => String(a.day).localeCompare(String(b.day))),
    events_by_name: byEvent.results,
    top_screens: screens.results,
    api_by_route: byRoute.results,
    devices_by_version: devices.results,
  });
}

async function subjects(env: Env, url: URL): Promise<Response> {
  const { days, sinceIso, sinceDay } = windowFrom(url);
  const params = url.searchParams;
  const type = params.get("type") === "device" ? "device" : "user";
  const limit = readLimit(params.get("limit"), 25, 100);
  const offset = readOffset(params.get("offset"));
  const search = (params.get("search") || "").trim();
  if (search.length > 120) return json({ error: "Search must be 120 characters or fewer." }, 400);
  const like = `%${search.replace(/[!%_]/g, (character) => `!${character}`)}%`;
  const db = env.RULES_DB;

  if (type === "user") {
    const order = USER_SORTS[params.get("sort") || ""] || USER_SORTS.calls;
    const where = search ? " WHERE u.email LIKE ? ESCAPE '!' OR u.display_name LIKE ? ESCAPE '!'" : "";
    const filters = search ? [like, like] : [];
    const [count, result] = await Promise.all([
      db.prepare(`SELECT COUNT(*) AS total FROM account_users u${where}`).bind(...filters).first<{ total: number }>(),
      db.prepare(
        `SELECT u.id, u.email, u.display_name, u.plan, u.created_at, u.email_verified,
                COALESCE(a.calls, 0) AS api_calls, COALESCE(a.errors, 0) AS api_errors,
                COALESCE(e.events, 0) AS events, e.last_event_at, COALESCE(d.devices, 0) AS devices
         FROM account_users u
         LEFT JOIN (SELECT user_id, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily
                    WHERE day >= ? AND user_id != '' GROUP BY user_id) a ON a.user_id = u.id
         LEFT JOIN (SELECT user_id, COUNT(*) AS events, MAX(occurred_at) AS last_event_at FROM analytics_events
                    WHERE occurred_at >= ? AND user_id IS NOT NULL GROUP BY user_id) e ON e.user_id = u.id
         LEFT JOIN (SELECT user_id, COUNT(*) AS devices FROM analytics_devices WHERE user_id IS NOT NULL GROUP BY user_id) d ON d.user_id = u.id
         ${where} ORDER BY ${order} LIMIT ? OFFSET ?`
      ).bind(sinceDay, sinceIso, ...filters, limit, offset).all<Row>(),
    ]);
    return json({ type, range_days: days, subjects: result.results, total: count?.total ?? 0, limit, offset });
  }

  const order = DEVICE_SORTS[params.get("sort") || ""] || DEVICE_SORTS.calls;
  const clauses: string[] = [];
  const filters: string[] = [];
  const scope = params.get("scope");
  if (scope === "anonymous") clauses.push("d.user_id IS NULL");
  else if (scope === "linked") clauses.push("d.user_id IS NOT NULL");
  if (search) {
    clauses.push("(d.device_id LIKE ? ESCAPE '!' OR d.device_model LIKE ? ESCAPE '!' OR u.email LIKE ? ESCAPE '!')");
    filters.push(like, like, like);
  }
  const where = clauses.length ? ` WHERE ${clauses.join(" AND ")}` : "";
  const from = "FROM analytics_devices d LEFT JOIN account_users u ON u.id = d.user_id";
  const [count, result] = await Promise.all([
    db.prepare(`SELECT COUNT(*) AS total ${from}${where}`).bind(...filters).first<{ total: number }>(),
    db.prepare(
      `SELECT d.device_id, d.platform, d.os_version, d.device_model, d.app_version, d.locale, d.time_zone, d.first_seen_at, d.last_seen_at,
              d.user_id, u.email AS user_email, COALESCE(a.calls, 0) AS api_calls, COALESCE(a.errors, 0) AS api_errors, COALESCE(e.events, 0) AS events
       ${from}
       LEFT JOIN (SELECT device_id, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily
                  WHERE day >= ? AND device_id != '' GROUP BY device_id) a ON a.device_id = d.device_id
       LEFT JOIN (SELECT device_id, COUNT(*) AS events FROM analytics_events WHERE occurred_at >= ? GROUP BY device_id) e ON e.device_id = d.device_id
       ${where} ORDER BY ${order} LIMIT ? OFFSET ?`
    ).bind(sinceDay, sinceIso, ...filters, limit, offset).all<Row>(),
  ]);
  return json({ type, range_days: days, subjects: result.results, total: count?.total ?? 0, limit, offset });
}

async function userDetail(env: Env, url: URL, userId: string): Promise<Response> {
  const { days, sinceIso, sinceDay } = windowFrom(url);
  const db = env.RULES_DB;
  const user = await db.prepare(
    "SELECT id, email, display_name, country_code, plan, created_at, email_verified FROM account_users WHERE id = ?"
  ).bind(userId).first<Row>();
  if (!user) return json({ error: "User not found." }, 404);
  // Events count for the account itself and for every device it has used, which includes activity from before it signed in.
  const mine = "(user_id = ? OR device_id IN (SELECT device_id FROM analytics_devices WHERE user_id = ?))";
  const [devices, byRoute, byDay, byEvent, recent, span, fishDays, feedback, quota] = await Promise.all([
    db.prepare(
      `SELECT device_id, platform, os_version, device_model, app_version, locale, time_zone, first_seen_at, last_seen_at
       FROM analytics_devices WHERE user_id = ? ORDER BY last_seen_at DESC`
    ).bind(userId).all<Row>(),
    db.prepare("SELECT route, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily WHERE user_id = ? AND day >= ? GROUP BY route ORDER BY calls DESC")
      .bind(userId, sinceDay).all<Row>(),
    db.prepare("SELECT day, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily WHERE user_id = ? AND day >= ? GROUP BY day ORDER BY day")
      .bind(userId, sinceDay).all<Row>(),
    db.prepare(`SELECT event_name, COUNT(*) AS events FROM analytics_events WHERE ${mine} AND occurred_at >= ? GROUP BY event_name ORDER BY events DESC`)
      .bind(userId, userId, sinceIso).all<Row>(),
    db.prepare(
      `SELECT event_name, device_id, user_id, props_json, occurred_at FROM analytics_events WHERE ${mine} AND occurred_at >= ?
       ORDER BY occurred_at DESC LIMIT 100`
    ).bind(userId, userId, sinceIso).all<Row>(),
    db.prepare(`SELECT MIN(occurred_at) AS first_event_at, MAX(occurred_at) AS last_event_at FROM analytics_events WHERE ${mine}`).bind(userId, userId).first<Row>(),
    db.prepare("SELECT day, used FROM account_identification_usage WHERE user_id = ? AND day >= ? ORDER BY day").bind(userId, sinceDay).all<Row>(),
    db.prepare("SELECT COUNT(*) AS total FROM account_feedback WHERE user_id = ?").bind(userId).first<{ total: number }>(),
    identificationQuota(db, userId),
  ]);
  return json({
    range_days: days,
    user: { ...user, email_verified: user.email_verified === 1 },
    first_event_at: span?.first_event_at ?? null,
    last_event_at: span?.last_event_at ?? null,
    devices: devices.results,
    api_by_route: byRoute.results,
    api_by_day: byDay.results,
    events_by_name: byEvent.results,
    recent_events: eventRows(recent.results),
    fish_identification: { today: quota, history: fishDays.results },
    feedback_count: feedback?.total ?? 0,
  });
}

async function deviceDetail(env: Env, url: URL, deviceId: string): Promise<Response> {
  const { days, sinceIso, sinceDay } = windowFrom(url);
  const db = env.RULES_DB;
  const device = await db.prepare(
    `SELECT d.device_id, d.platform, d.os_version, d.device_model, d.app_version, d.locale, d.time_zone, d.first_seen_at, d.last_seen_at,
            d.user_id, u.email AS user_email
     FROM analytics_devices d LEFT JOIN account_users u ON u.id = d.user_id WHERE d.device_id = ?`
  ).bind(deviceId).first<Row>();
  if (!device) return json({ error: "Device not found." }, 404);
  const [byRoute, byDay, byEvent, recent] = await Promise.all([
    db.prepare("SELECT route, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily WHERE device_id = ? AND day >= ? GROUP BY route ORDER BY calls DESC")
      .bind(deviceId, sinceDay).all<Row>(),
    db.prepare("SELECT day, SUM(calls) AS calls, SUM(errors) AS errors FROM api_usage_daily WHERE device_id = ? AND day >= ? GROUP BY day ORDER BY day")
      .bind(deviceId, sinceDay).all<Row>(),
    db.prepare("SELECT event_name, COUNT(*) AS events FROM analytics_events WHERE device_id = ? AND occurred_at >= ? GROUP BY event_name ORDER BY events DESC")
      .bind(deviceId, sinceIso).all<Row>(),
    db.prepare(
      "SELECT event_name, device_id, user_id, props_json, occurred_at FROM analytics_events WHERE device_id = ? AND occurred_at >= ? ORDER BY occurred_at DESC LIMIT 100"
    ).bind(deviceId, sinceIso).all<Row>(),
  ]);
  return json({
    range_days: days, device, api_by_route: byRoute.results, api_by_day: byDay.results,
    events_by_name: byEvent.results, recent_events: eventRows(recent.results),
  });
}
