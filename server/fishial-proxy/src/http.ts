export function corsHeaders(): HeadersInit {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, POST, PATCH, DELETE, OPTIONS",
    "access-control-allow-headers": "authorization, content-type, x-account-admin-token, x-rules-ingest-token, x-location-lat-lon, x-location-source, x-fishing-rules-area, x-client-platform, x-device-id",
    "access-control-max-age": "86400",
  };
}

export function json(body: object, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders() },
  });
}

/** Parse a JSON object body, or return null when it is missing, too large or not an object. */
export async function readJson(request: Request, maxBytes = 16_384): Promise<Record<string, unknown> | null> {
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > maxBytes) return null;
  try {
    const body = await request.arrayBuffer();
    if (body.byteLength > maxBytes) return null;
    const value = JSON.parse(new TextDecoder().decode(body)) as unknown;
    return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

export function readLimit(value: string | null, fallback: number, maximum: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, maximum) : fallback;
}

export function readOffset(value: string | null): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? Math.min(parsed, 1_000_000) : 0;
}
