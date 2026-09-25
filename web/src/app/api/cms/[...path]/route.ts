import { cookies } from "next/headers";

const COOKIE_NAME = "catchcheck_cms_session";
const API_BASE = (process.env.FISH_ID_API_BASE_URL || "https://fishing.fishnz.space").replace(/\/+$/, "");

function sessionCookie(value: string, maxAge: number): string {
  return `${COOKIE_NAME}=${value}; Path=/api/cms; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${process.env.NODE_ENV === "production" ? "; Secure" : ""}`;
}

function errorResponse(message: string, status: number): Response {
  return Response.json({ error: message }, { status, headers: { "cache-control": "no-store" } });
}

function expiredSession(): Response {
  const response = errorResponse("CMS access expired. Sign in again.", 401);
  response.headers.append("set-cookie", sessionCookie("", 0));
  return response;
}

function pageOffset(value: string | null): number | null {
  if (value === null) return 0;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 && parsed <= 1_000_000 ? parsed : null;
}

function validPage(value: unknown, key: "users" | "feedback"): boolean {
  if (!value || typeof value !== "object") return false;
  const page = value as Record<string, unknown>;
  return Array.isArray(page[key]) && typeof page.total === "number" &&
    typeof page.limit === "number" && page.limit > 0 && typeof page.offset === "number";
}

async function proxy(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/^\/api\/cms/, "");
  const origin = request.headers.get("origin");
  if (request.method !== "GET" && origin && origin !== url.origin) return errorResponse("Request origin is not allowed.", 403);

  if (path === "/session" && request.method === "POST") {
    const body = await request.json().catch(() => null) as { token?: unknown } | null;
    if (typeof body?.token !== "string" || body.token.length < 32 || body.token.length > 256) return errorResponse("Enter the CMS access token.", 400);
    const check = await fetch(`${API_BASE}/v1/admin/analytics?days=30`, { headers: { "x-account-admin-token": body.token }, cache: "no-store" }).catch(() => null);
    if (!check?.ok) return errorResponse(check?.status === 401 ? "That CMS token is not valid." : "Could not reach the account service.", check?.status === 401 ? 401 : 503);
    const response = Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
    response.headers.append("set-cookie", sessionCookie(body.token, 8 * 60 * 60));
    return response;
  }

  if (path === "/session" && request.method === "DELETE") {
    const response = Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
    response.headers.append("set-cookie", sessionCookie("", 0));
    return response;
  }

  const token = (await cookies()).get(COOKIE_NAME)?.value;
  if (!token) return errorResponse("Sign in to the CMS.", 401);

  if (path === "/dashboard" && request.method === "GET") {
    const usersOffset = pageOffset(url.searchParams.get("users_offset"));
    const feedbackOffset = pageOffset(url.searchParams.get("feedback_offset"));
    const search = (url.searchParams.get("search") || "").trim();
    if (usersOffset === null || feedbackOffset === null || search.length > 120) return errorResponse("Invalid dashboard filters.", 400);

    const usersParams = new URLSearchParams({ limit: "25", offset: String(usersOffset) });
    if (search) usersParams.set("search", search);
    const feedbackParams = new URLSearchParams({ limit: "20", offset: String(feedbackOffset) });
    const headers = { "x-account-admin-token": token };
    const responses = await Promise.all([
      fetch(`${API_BASE}/v1/admin/analytics?days=30`, { headers, cache: "no-store" }),
      fetch(`${API_BASE}/v1/admin/users?${usersParams}`, { headers, cache: "no-store" }),
      fetch(`${API_BASE}/v1/admin/feedback?${feedbackParams}`, { headers, cache: "no-store" }),
    ]).catch(() => null);
    if (!responses) return errorResponse("Could not reach the account service.", 503);
    if (responses.some((response) => response.status === 401)) return expiredSession();
    if (responses.some((response) => !response.ok)) return errorResponse("The account service could not load the dashboard. Try again.", 502);

    const data = await Promise.all(responses.map((response) => response.json())).catch(() => null);
    if (!data) return errorResponse("The account service returned an invalid dashboard. Try again.", 502);
    const [analytics, users, feedback] = data;
    if (!analytics?.users || typeof analytics.users.total_users !== "number" ||
      typeof analytics.users.verified_users !== "number" || typeof analytics.users.pending_users !== "number" ||
      !Array.isArray(analytics?.plans) || !Array.isArray(analytics?.signups_by_day) ||
      !Array.isArray(analytics?.feature_usage) || !Array.isArray(analytics?.events_by_day) ||
      !validPage(users, "users") || !validPage(feedback, "feedback")) {
      return errorResponse("The account service returned an invalid dashboard. Try again.", 502);
    }
    return Response.json({ analytics, users, feedback }, { headers: { "cache-control": "no-store" } });
  }

  const planMatch = path.match(/^\/users\/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\/plan$/i);
  if (planMatch && request.method === "PATCH") {
    const body = await request.json().catch(() => null) as { plan?: unknown } | null;
    if (body?.plan !== "free" && body?.plan !== "paid") return errorResponse("Plan must be free or paid.", 400);
    const result = await fetch(`${API_BASE}/v1/admin/users/${planMatch[1]}/plan`, {
      method: "PATCH", headers: { "x-account-admin-token": token, "content-type": "application/json" },
      body: JSON.stringify({ plan: body.plan }), cache: "no-store",
    }).catch(() => null);
    if (!result) return errorResponse("Could not reach the account service.", 503);
    if (result.status === 401) return expiredSession();
    const payload = await result.json().catch(() => null);
    if (!payload || typeof payload !== "object") return errorResponse("The account service returned an invalid response.", 502);
    if (result.status >= 500) return errorResponse("The account service could not update the plan. Try again.", 502);
    return Response.json(payload, { status: result.status, headers: { "cache-control": "no-store" } });
  }
  return errorResponse("Not found.", 404);
}

export const GET = proxy;
export const POST = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
