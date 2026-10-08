import { DEVICE_ID_PATTERN } from "./analytics";

/** Group a request path into a stable name for per-account and per-device usage counts. */
export function routeGroup(pathname: string): string | null {
  if (!pathname.startsWith("/v1/")) return null;
  // Admin and telemetry calls are not product usage.
  if (pathname.startsWith("/v1/admin/") || pathname.startsWith("/v1/analytics/")) return null;
  if (pathname === "/v1/fish/identify") return "fish_identify";
  if (pathname === "/v1/fish/rules" || pathname.startsWith("/v1/rules")) return "rules";
  if (pathname.startsWith("/v1/auth/")) return "auth";
  if (pathname === "/v1/me" || pathname.startsWith("/v1/me/")) return "account";
  if (pathname === "/v1/feedback") return "feedback";
  return "other";
}

export function deviceIdFromRequest(request: Request): string | null {
  const supplied = (request.headers.get("x-device-id") || "").toLowerCase();
  return DEVICE_ID_PATTERN.test(supplied) ? supplied : null;
}

/** Add one call to today's tally for the signed-in account and/or the device that sent the x-device-id header. */
export async function recordApiUsage(db: D1Database, request: Request, userId: string | null, status: number, now = new Date()): Promise<void> {
  if (request.method === "OPTIONS") return;
  const route = routeGroup(new URL(request.url).pathname);
  const deviceId = deviceIdFromRequest(request);
  if (!route || (!userId && !deviceId)) return;
  await db.prepare(
    `INSERT INTO api_usage_daily (day, user_id, device_id, route, calls, errors) VALUES (?, ?, ?, ?, 1, ?)
     ON CONFLICT(day, user_id, device_id, route) DO UPDATE SET calls = calls + 1, errors = errors + excluded.errors`
  ).bind(now.toISOString().slice(0, 10), userId ?? "", deviceId ?? "", route, status >= 400 ? 1 : 0).run();
}
