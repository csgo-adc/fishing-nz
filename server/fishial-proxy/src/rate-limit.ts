import { sha256 } from "./security";

export type RateRule = { key: string; limit: number };

export const MINUTE = 60_000;
export const HOUR = 60 * MINUTE;

/** The caller's network address as Cloudflare reports it; shared by every request that lacks one. */
export function clientAddress(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() || "unknown";
}

/** Counter keys are hashed so the table never holds a readable address or email. */
export async function rateKey(scope: string, ...parts: string[]): Promise<string> {
  return `${scope}:${(await sha256(parts.join("\n").toLowerCase())).slice(0, 32)}`;
}

function windowStart(now: number, windowMs: number): number {
  return Math.floor(now / windowMs) * windowMs;
}

/** Seconds until the current fixed window ends. */
export function retryAfterSeconds(windowMs: number, now = Date.now()): number {
  return Math.max(1, Math.ceil((windowStart(now, windowMs) + windowMs - now) / 1000));
}

/** True when any rule has already reached its limit in the current window. Does not count this attempt. */
export async function isRateLimited(db: D1Database, rules: RateRule[], windowMs: number, now = Date.now()): Promise<boolean> {
  const start = windowStart(now, windowMs);
  const rows = await Promise.all(rules.map((rule) =>
    db.prepare("SELECT count FROM rate_limit_counters WHERE key = ? AND window_start = ?").bind(rule.key, start).first<{ count: number }>()));
  return rows.some((row, index) => (row?.count ?? 0) >= rules[index].limit);
}

/** Count one attempt against every key, for example after a failed sign-in. */
export async function recordAttempt(db: D1Database, keys: string[], windowMs: number, now = Date.now()): Promise<void> {
  const start = windowStart(now, windowMs);
  await db.batch(keys.map((key) =>
    db.prepare("INSERT INTO rate_limit_counters (key, window_start, count) VALUES (?, ?, 1) ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1")
      .bind(key, start)));
}

/** Count this attempt and report whether it is within the limit (true) or over it (false). */
export async function allowAttempt(db: D1Database, rule: RateRule, windowMs: number, now = Date.now()): Promise<boolean> {
  const row = await db.prepare(
    "INSERT INTO rate_limit_counters (key, window_start, count) VALUES (?, ?, 1) ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1 RETURNING count"
  ).bind(rule.key, windowStart(now, windowMs)).first<{ count: number }>();
  return (row?.count ?? 1) <= rule.limit;
}

export function tooManyRequests(message: string, windowMs: number, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify({ error: message, code: "rate_limited" }), {
    status: 429,
    headers: {
      "content-type": "application/json; charset=utf-8", "cache-control": "no-store",
      "retry-after": String(retryAfterSeconds(windowMs)), ...headers,
    },
  });
}

/** Statement for the daily cleanup batch. The longest window is an hour; keep a day of counters. */
export function expiredCountersStatement(db: D1Database, now = Date.now()): D1PreparedStatement {
  return db.prepare("DELETE FROM rate_limit_counters WHERE window_start < ?").bind(now - 24 * HOUR);
}
