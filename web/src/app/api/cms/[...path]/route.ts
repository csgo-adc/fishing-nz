import { cookies } from "next/headers";

const COOKIE_NAME = "catchcheck_cms_session";
const API_BASE = (process.env.FISH_ID_API_BASE_URL || "https://fishing.fishnz.space").replace(/\/+$/, "");

function cookie(value: string, maxAge: number): string {
  return `${COOKIE_NAME}=${value}; Path=/api/cms; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${process.env.NODE_ENV === "production" ? "; Secure" : ""}`;
}

async function proxy(request: Request): Promise<Response> {
  const path = new URL(request.url).pathname.replace(/^\/api\/cms/, "");
  const cookieStore = await cookies();

  if (path === "/session" && request.method === "POST") {
    const body = await request.json().catch(() => null) as { token?: unknown } | null;
    if (typeof body?.token !== "string" || body.token.length < 32 || body.token.length > 256) return Response.json({ error: "Enter the CMS access token." }, { status: 400 });
    const check = await fetch(`${API_BASE}/v1/admin/analytics?days=30`, { headers: { "x-account-admin-token": body.token }, cache: "no-store" }).catch(() => null);
    if (!check?.ok) return Response.json({ error: check?.status === 401 ? "That CMS token is not valid." : "Could not reach the account service." }, { status: check?.status === 401 ? 401 : 503 });
    const response = Response.json({ ok: true });
    response.headers.append("set-cookie", cookie(body.token, 8 * 60 * 60));
    return response;
  }

  if (path === "/session" && request.method === "DELETE") {
    const response = Response.json({ ok: true });
    response.headers.append("set-cookie", cookie("", 0));
    return response;
  }

  const token = cookieStore.get(COOKIE_NAME)?.value;
  if (!token) return Response.json({ error: "Sign in to the CMS." }, { status: 401 });

  if (path === "/dashboard" && request.method === "GET") {
    const responses = await Promise.all([
      fetch(`${API_BASE}/v1/admin/analytics?days=30`, { headers: { "x-account-admin-token": token }, cache: "no-store" }),
      fetch(`${API_BASE}/v1/admin/users?limit=100`, { headers: { "x-account-admin-token": token }, cache: "no-store" }),
      fetch(`${API_BASE}/v1/admin/feedback?limit=50`, { headers: { "x-account-admin-token": token }, cache: "no-store" }),
    ]).catch(() => null);
    if (!responses) return Response.json({ error: "Could not reach the account service." }, { status: 503 });
    const [analytics, users, feedback] = responses;
    if ([analytics, users, feedback].some((response) => response.status === 401)) {
      const response = Response.json({ error: "CMS access expired. Sign in again." }, { status: 401 });
      response.headers.append("set-cookie", cookie("", 0));
      return response;
    }
    const [analyticsData, usersData, feedbackData] = await Promise.all([analytics.json(), users.json(), feedback.json()]);
    return Response.json({ analytics: analyticsData, users: usersData, feedback: feedbackData }, { headers: { "cache-control": "no-store" } });
  }

  const planMatch = path.match(/^\/users\/([a-f0-9-]+)\/plan$/i);
  if (planMatch && request.method === "PATCH") {
    const body = await request.text();
    const result = await fetch(`${API_BASE}/v1/admin/users/${planMatch[1]}/plan`, {
      method: "PATCH", headers: { "x-account-admin-token": token, "content-type": "application/json" }, body, cache: "no-store",
    }).catch(() => null);
    if (!result) return Response.json({ error: "Could not reach the account service." }, { status: 503 });
    return Response.json(await result.json().catch(() => ({})), { status: result.status });
  }
  return Response.json({ error: "Not found." }, { status: 404 });
}

export const GET = proxy;
export const POST = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
