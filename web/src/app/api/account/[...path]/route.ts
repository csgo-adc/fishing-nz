import { cookies } from "next/headers";

const COOKIE_NAME = "catchcheck_session";
const API_BASE = (process.env.FISH_ID_API_BASE_URL || "https://fishing.fishnz.space").replace(/\/+$/, "");
const routes: Record<string, string[]> = {
  "/auth/register": ["POST"],
  "/auth/login": ["POST"],
  "/auth/resend-verification": ["POST"],
  "/auth/logout": ["POST"],
  "/me": ["GET", "PATCH"],
  "/me/permissions": ["GET"],
  "/feedback": ["POST"],
  "/analytics/events": ["POST"],
  "/fish/identify": ["POST"],
};

async function proxy(request: Request): Promise<Response> {
  const path = new URL(request.url).pathname.replace(/^\/api\/account/, "");
  if (!routes[path]?.includes(request.method)) return Response.json({ error: "Not found." }, { status: 404 });

  const cookieStore = await cookies();
  const token = cookieStore.get(COOKIE_NAME)?.value;
  const headers = new Headers({ accept: "application/json" });
  if (token) headers.set("authorization", `Bearer ${token}`);
  if (request.method !== "GET") headers.set("content-type", path === "/fish/identify" ? request.headers.get("content-type") || "image/jpeg" : "application/json");

  let body: string | ArrayBuffer | undefined;
  if (request.method !== "GET") body = path === "/fish/identify" ? await request.arrayBuffer() : await request.text();
  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/v1${path}`, {
      method: request.method,
      headers,
      body,
      cache: "no-store",
    });
  } catch {
    return Response.json({ error: "Account service is temporarily unavailable." }, { status: 503 });
  }

  const result = await upstream.json().catch(() => ({})) as Record<string, unknown>;
  if (upstream.status === 401 && (path === "/me" || path === "/me/permissions")) {
    const response = Response.json(result, { status: upstream.status });
    response.headers.append("set-cookie", `${COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${process.env.NODE_ENV === "production" ? "; Secure" : ""}`);
    return response;
  }
  if (path === "/auth/login" && upstream.ok) {
    if (typeof result.token !== "string" || !/^[a-f0-9]{64}$/i.test(result.token)) {
      return Response.json({ error: "Could not start your account session. Please try again." }, { status: 502 });
    }
    const responseBody = { ...result };
    delete responseBody.token;
    delete responseBody.token_type;
    delete responseBody.expires_at;
    const response = Response.json(responseBody, { status: upstream.status });
    response.headers.append("set-cookie", `${COOKIE_NAME}=${result.token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000${process.env.NODE_ENV === "production" ? "; Secure" : ""}`);
    return response;
  }

  if (path === "/auth/logout" && (upstream.ok || upstream.status === 401)) {
    const response = Response.json(result, { status: upstream.status });
    response.headers.append("set-cookie", `${COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${process.env.NODE_ENV === "production" ? "; Secure" : ""}`);
    return response;
  }
  return Response.json(result, { status: upstream.status });
}

export const GET = proxy;
export const POST = proxy;
export const PATCH = proxy;
