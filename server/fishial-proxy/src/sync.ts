import type { Env } from "./index";
import { json, readJson } from "./http";
import { allowAttempt, HOUR, rateKey, tooManyRequests } from "./rate-limit";

/**
 * Account sync for trips and the catch log: GET returns everything stored for the account, POST merges the device's
 * items (newest `updated_at` wins, deletions included) and returns the merged set, and DELETE removes the synced copy.
 * The apps keep their own copy and only call this when the person has turned sync on.
 */
type Authenticate = (request: Request, env: Env) => Promise<{ account: { id: string } } | null>;
type Item = { kind: "trips" | "catch"; id: string; payload: string; updated_at: number; deleted: number };

const KINDS = { trips: 65_536, catch: 4_096 } as const;
const MAX_ITEMS_PER_REQUEST = 1_000;
const MAX_CATCHES = 2_000;
const SYNCS_PER_HOUR = 120;
const EARLIEST = 1_704_067_200; // 2024-01-01
const ID = /^[A-Za-z0-9_-]{1,64}$/;

export async function handleSyncRoute(request: Request, env: Env, authenticate: Authenticate): Promise<Response | null> {
  if (new URL(request.url).pathname !== "/v1/me/sync") return null;
  if (!["GET", "POST", "DELETE"].includes(request.method)) return json({ error: "Not found" }, 404);
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Sign in to sync.", code: "authentication_required" }, 401);
  const userId = auth.account.id;
  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("sync", userId), limit: SYNCS_PER_HOUR }, HOUR)) {
    return tooManyRequests("Sync is paused for a while. Try again later.", HOUR);
  }
  if (request.method === "DELETE") {
    await env.RULES_DB.prepare("DELETE FROM user_sync_items WHERE user_id = ?").bind(userId).run();
    return json({ deleted: true });
  }
  if (request.method === "POST") {
    const body = await readJson(request, 2_000_000);
    const items = parseItems(body?.items);
    if (!items) return json({ error: "Send items as a list of { kind, id, payload, updated_at, deleted }." }, 400);
    const catches = items.filter((item) => item.kind === "catch").length;
    const stored = await env.RULES_DB.prepare("SELECT COUNT(*) AS count FROM user_sync_items WHERE user_id = ? AND kind = 'catch'")
      .bind(userId).first<{ count: number }>();
    if ((stored?.count ?? 0) + catches > MAX_CATCHES + MAX_ITEMS_PER_REQUEST || catches > MAX_CATCHES) {
      return json({ error: `Sync keeps up to ${MAX_CATCHES} catches.`, code: "sync_limit" }, 413);
    }
    if (items.length > 0) {
      // One statement for the whole batch, so a large log stays within D1's query limits.
      await env.RULES_DB.prepare(
        `INSERT INTO user_sync_items (user_id, kind, item_id, payload, updated_at, deleted)
         SELECT ?, json_extract(value, '$.kind'), json_extract(value, '$.id'), json_extract(value, '$.payload'),
                json_extract(value, '$.updated_at'), json_extract(value, '$.deleted')
         FROM json_each(?) WHERE true
         ON CONFLICT(user_id, kind, item_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at, deleted = excluded.deleted
         WHERE excluded.updated_at > user_sync_items.updated_at`
      ).bind(userId, JSON.stringify(items)).run();
    }
  }
  const rows = await env.RULES_DB.prepare(
    "SELECT kind, item_id, payload, updated_at, deleted FROM user_sync_items WHERE user_id = ? ORDER BY kind, updated_at"
  ).bind(userId).all<{ kind: string; item_id: string; payload: string; updated_at: number; deleted: number }>();
  return json({
    items: rows.results.map((row) => ({ kind: row.kind, id: row.item_id, payload: JSON.parse(row.payload), updated_at: row.updated_at, deleted: row.deleted === 1 })),
    server_time: Math.floor(Date.now() / 1000),
  });
}

/** Valid items, or null when anything is malformed. Payloads are stored as their JSON text. */
function parseItems(value: unknown): Item[] | null {
  if (!Array.isArray(value) || value.length > MAX_ITEMS_PER_REQUEST) return null;
  const latest = Math.floor(Date.now() / 1000) + 86_400;
  const items: Item[] = [];
  const seen = new Set<string>();
  for (const raw of value) {
    if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return null;
    const { kind, id, payload, updated_at: updatedAt, deleted } = raw as Record<string, unknown>;
    if (kind !== "trips" && kind !== "catch") return null;
    if (typeof id !== "string" || !ID.test(id) || seen.has(`${kind}:${id}`)) return null;
    if (typeof updatedAt !== "number" || !Number.isInteger(updatedAt) || updatedAt < EARLIEST || updatedAt > latest) return null;
    if (typeof deleted !== "boolean" || typeof payload !== "object" || payload === null || Array.isArray(payload)) return null;
    const text = JSON.stringify(payload);
    if (new TextEncoder().encode(text).byteLength > KINDS[kind]) return null;
    seen.add(`${kind}:${id}`);
    items.push({ kind, id, payload: text, updated_at: updatedAt, deleted: deleted ? 1 : 0 });
  }
  return items;
}

/** Deleted entries are kept for 180 days so every device sees the deletion, then removed. */
export function expiredSyncTombstones(db: D1Database, now = Date.now()): D1PreparedStatement {
  return db.prepare("DELETE FROM user_sync_items WHERE deleted = 1 AND updated_at < ?").bind(Math.floor(now / 1000) - 180 * 86_400);
}
