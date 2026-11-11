import { sendEmail, type EmailEnv } from "./email";
import { parseRulesPage, RulesPageError } from "./rules-parse";

/**
 * Fishing rules tools for the admin CMS (docs/fishing-rules-updates.md). MPI's website blocks automated downloads, so
 * the rules are refreshed from a page saved in a normal browser and uploaded in the CMS: preview shows what changed,
 * publish saves it and the apps pick it up on their next sync. The daily cron emails a reminder when an area looks out
 * of date: saved too long ago, or MPI's own "recent rule changes" list has a change that has started since.
 */
export interface RulesAdminEnv extends EmailEnv {
  RULES_DB: D1Database;
  PUBLIC_SUPPORT_EMAIL?: string;
  CMS_BASE_URL?: string;
}

export type RulesAreas = { names: Map<string, string>; officialUrl: (areaId: string) => string };

type Reminders = { enabled: boolean; email: string; stale_days: number; last_sent_at: string | null; last_reasons: string | null };
type HistoryEntry = { area_id: string; at: string; reviewed_at: string | null; changes: number };
type StoredArea = { area_id: string; reviewed_at: string | null; fetched_at: string; tables_json: string; sections_json: string; source_url: string;
  status: string | null; last_attempt_at: string | null; http_status: number | null; error: string | null };
type DatedChange = { date: string; text: string };

const REMINDERS_KEY = "rules.reminders";
const HISTORY_KEY = "rules.history";
const DAY = 86_400_000;
const MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"];
const DATE_PATTERN = /\b(\d{1,2})(?:st|nd|rd|th)?\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+(20\d{2})\b/gi;

const json = (body: unknown, status = 200) => Response.json(body, { status, headers: { "cache-control": "no-store" } });

async function readSetting<T>(db: D1Database, key: string): Promise<T | null> {
  try {
    const row = await db.prepare("SELECT value FROM app_settings WHERE key = ?").bind(key).first<{ value: string }>();
    return row ? JSON.parse(row.value) as T : null;
  } catch { return null; }
}

async function writeSetting(db: D1Database, key: string, value: unknown): Promise<void> {
  await db.prepare(`INSERT INTO app_settings (key, value, updated_at) VALUES (?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`)
    .bind(key, JSON.stringify(value), new Date().toISOString()).run();
}

async function reminderSettings(env: RulesAdminEnv): Promise<Reminders> {
  const saved = await readSetting<Partial<Reminders>>(env.RULES_DB, REMINDERS_KEY);
  return {
    enabled: saved?.enabled ?? true,
    email: saved?.email ?? env.PUBLIC_SUPPORT_EMAIL ?? "",
    stale_days: saved?.stale_days ?? 30,
    last_sent_at: saved?.last_sent_at ?? null,
    last_reasons: saved?.last_reasons ?? null,
  };
}

/** Dated changes in MPI's own wording ("From 15 October 2026: …"), each with the line it appears in. */
export function datedChanges(sections: Array<{ heading: string; text: string }>): DatedChange[] {
  const found = new Map<string, DatedChange>();
  for (const section of sections) {
    for (const line of section.text.split("\n")) {
      for (const match of line.matchAll(DATE_PATTERN)) {
        const month = MONTHS.indexOf(match[2].toLowerCase());
        const date = `${match[3]}-${String(month + 1).padStart(2, "0")}-${match[1].padStart(2, "0")}`;
        if (!found.has(date)) found.set(date, { date, text: line.trim().slice(0, 220) });
      }
    }
  }
  return [...found.values()].sort((a, b) => a.date.localeCompare(b.date));
}

const nzDay = (time: number) => new Intl.DateTimeFormat("en-CA", { timeZone: "Pacific/Auckland" }).format(new Date(time));
const niceDate = (isoDay: string) => new Intl.DateTimeFormat("en-NZ", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(`${isoDay}T00:00:00Z`));

/** Why an area may be out of date, in words for the CMS and the reminder email. */
function reasonsFor(area: StoredArea, staleDays: number, now: number): { reasons: string[]; started: DatedChange[]; upcoming: DatedChange[] } {
  const savedDay = nzDay(Date.parse(area.fetched_at));
  const today = nzDay(now);
  let sections: Array<{ heading: string; text: string }> = [];
  try { sections = JSON.parse(area.sections_json); } catch { /* unreadable sections give no dates */ }
  const changes = datedChanges(sections);
  const started = changes.filter((change) => change.date > savedDay && change.date <= today);
  const upcoming = changes.filter((change) => change.date > today);
  const reasons: string[] = [];
  for (const change of started) reasons.push(`MPI's page mentions ${niceDate(change.date)}, which has passed since these rules were saved: "${change.text}"`);
  const age = Math.floor((now - Date.parse(area.fetched_at)) / DAY);
  if (age >= staleDays) reasons.push(`Saved ${age} days ago; MPI may have updated the page since.`);
  if (area.status === "source_changed") reasons.push("The daily check saw MPI's page change.");
  return { reasons, started, upcoming };
}

async function storedAreas(env: RulesAdminEnv): Promise<StoredArea[]> {
  const result = await env.RULES_DB.prepare(`SELECT r.area_id, r.reviewed_at, r.fetched_at, r.tables_json, r.sections_json, r.source_url,
      s.status, s.last_attempt_at, s.http_status, s.error
    FROM mpi_fishing_rules r LEFT JOIN mpi_rules_crawl_status s ON s.area_id = r.area_id`).all<StoredArea>();
  return result.results;
}

function tableRows(tablesJson: string): number {
  try { return (JSON.parse(tablesJson) as string[][][]).reduce((sum, table) => sum + Math.max(0, table.length - 1), 0); }
  catch { return 0; }
}

async function overview(env: RulesAdminEnv, areas: RulesAreas): Promise<Response> {
  const now = Date.now();
  const settings = await reminderSettings(env);
  const stored = new Map((await storedAreas(env)).map((row) => [row.area_id, row]));
  const rows = [...areas.names].map(([areaId, name]) => {
    const area = stored.get(areaId);
    const check = area ? reasonsFor(area, settings.stale_days, now) : { reasons: ["No rules saved for this area yet."], started: [], upcoming: [] };
    return {
      area_id: areaId, area_name: name, official_url: areas.officialUrl(areaId),
      reviewed_at: area?.reviewed_at ?? null, fetched_at: area?.fetched_at ?? null,
      tables: area ? (JSON.parse(area.tables_json) as unknown[]).length : 0, rows: area ? tableRows(area.tables_json) : 0,
      check: area ? { status: area.status, last_attempt_at: area.last_attempt_at, http_status: area.http_status, error: area.error } : null,
      reasons: check.reasons, started_changes: check.started, upcoming_changes: check.upcoming,
    };
  });
  return json({
    areas: rows,
    reminders: settings,
    email_ready: Boolean(env.RESEND_API_KEY && env.ACCOUNT_EMAIL_FROM),
    history: (await readSetting<HistoryEntry[]>(env.RULES_DB, HISTORY_KEY)) ?? [],
  });
}

type RowChange = { kind: "changed" | "added" | "removed"; table: string; species: string; before: string | null; after: string | null };

/** Rule rows that differ, matched by each table's header and the row's first cell (the species). */
export function compareTables(before: string[][][], after: string[][][]): RowChange[] {
  const index = (tables: string[][][]) => {
    const rows = new Map<string, { table: string; species: string; value: string }>();
    for (const table of tables) {
      const header = (table[0] ?? []).join(" | ");
      for (const row of table.slice(1)) {
        if (row.length < 2) continue;
        const species = row[0].trim();
        let key = `${header}::${species.toLowerCase()}`;
        for (let copy = 2; rows.has(key); copy++) key = `${header}::${species.toLowerCase()}#${copy}`;
        rows.set(key, { table: header, species, value: row.slice(1).join(" | ") });
      }
    }
    return rows;
  };
  const old = index(before);
  const next = index(after);
  const changes: RowChange[] = [];
  for (const [key, row] of next) {
    const previous = old.get(key);
    if (!previous) changes.push({ kind: "added", table: row.table, species: row.species, before: null, after: row.value });
    else if (previous.value !== row.value) changes.push({ kind: "changed", table: row.table, species: row.species, before: previous.value, after: row.value });
  }
  for (const [key, row] of old) if (!next.has(key)) changes.push({ kind: "removed", table: row.table, species: row.species, before: row.value, after: null });
  return changes;
}

async function readPage(request: Request, areas: RulesAreas): Promise<{ areaId: string; html: string } | Response> {
  if (Number(request.headers.get("content-length") || 0) > 4 * 1024 * 1024) return json({ error: "That page is too large. Save it as \"Webpage, HTML only\"." }, 413);
  const body = await request.json().catch(() => null) as { area_id?: unknown; html?: unknown } | null;
  if (typeof body?.area_id !== "string" || !areas.names.has(body.area_id)) return json({ error: "Choose the fishing area this page is for." }, 400);
  if (typeof body.html !== "string" || body.html.length < 500) return json({ error: "Upload or paste the saved MPI page." }, 400);
  if (body.html.length > 1_800_000) return json({ error: "That page is too large. Save it as \"Webpage, HTML only\"." }, 413);
  return { areaId: body.area_id, html: body.html };
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Parses an uploaded page and checks it is the right area's MPI page. */
async function preparePage(env: RulesAdminEnv, areas: RulesAreas, areaId: string, html: string) {
  const name = areas.names.get(areaId)!;
  const parsed = parseRulesPage(html, name);
  const expected = areas.officialUrl(areaId).replace(/\/+$/, "").toLowerCase();
  if (parsed.sourceUrl && parsed.sourceUrl.replace(/\/+$/, "") !== expected) {
    const other = [...areas.names.keys()].find((id) => areas.officialUrl(id).replace(/\/+$/, "").toLowerCase() === parsed.sourceUrl!.replace(/\/+$/, ""));
    throw new RulesPageError(other ? `This page is for ${areas.names.get(other)}, not ${name}.` : "This page isn't one of MPI's area rules pages.");
  }
  if (parsed.tables.length === 0) throw new RulesPageError("No rule tables were found on this page. Save the whole page and try again.");
  const current = await env.RULES_DB.prepare("SELECT reviewed_at, tables_json, content_sha256 FROM mpi_fishing_rules WHERE area_id = ?")
    .bind(areaId).first<{ reviewed_at: string | null; tables_json: string; content_sha256: string }>();
  const order = { changed: 0, added: 1, removed: 2 };
  const savedTables: string[][][] = current ? JSON.parse(current.tables_json) : [];
  const changes = compareTables(savedTables, parsed.tables).sort((a, b) => order[a.kind] - order[b.kind]);
  const warnings: string[] = [];
  const savedRows = savedTables.reduce((sum, table) => sum + Math.max(0, table.length - 1), 0);
  const removed = changes.filter((change) => change.kind === "removed").length;
  if (savedRows >= 10 && removed > savedRows / 2) {
    warnings.push(`This page would remove ${removed} of the ${savedRows} saved rule rows. Check the whole page was saved before publishing.`);
  }
  if (!parsed.sourceUrl) warnings.push("The page doesn't say which address it came from, so check it is the right area.");
  if (!parsed.reviewedAt) warnings.push("MPI's \"Last reviewed\" date wasn't found on this page.");
  const parseDay = (value: string | null) => {
    const match = value?.match(/^(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})$/);
    return match ? `${match[3].length === 2 ? `20${match[3]}` : match[3]}-${match[2].padStart(2, "0")}-${match[1].padStart(2, "0")}` : null;
  };
  const newDay = parseDay(parsed.reviewedAt);
  const oldDay = parseDay(current?.reviewed_at ?? null);
  if (newDay && oldDay && newDay < oldDay) warnings.push(`This page was reviewed on ${parsed.reviewedAt}, before the saved rules (${current?.reviewed_at}). It may be an old copy.`);
  return { name, parsed, changes, warnings, contentHash: await sha256Hex(html), sameAsSaved: Boolean(current) && changes.length === 0 && (parsed.reviewedAt ?? null) === (current?.reviewed_at ?? null) };
}

async function preview(request: Request, env: RulesAdminEnv, areas: RulesAreas): Promise<Response> {
  const page = await readPage(request, areas);
  if (page instanceof Response) return page;
  try {
    const { name, parsed, changes, warnings, sameAsSaved } = await preparePage(env, areas, page.areaId, page.html);
    return json({
      area_id: page.areaId, area_name: name, page_title: parsed.title, reviewed_at: parsed.reviewedAt,
      sections: parsed.sections.length, tables: parsed.tables.length,
      rows: parsed.tables.reduce((sum, table) => sum + Math.max(0, table.length - 1), 0),
      changes: changes.slice(0, 200), change_count: changes.length, warnings, same_as_saved: sameAsSaved,
      upcoming_changes: datedChanges(parsed.sections).filter((change) => change.date > nzDay(Date.now())),
    });
  } catch (error) {
    if (error instanceof RulesPageError) return json({ error: error.message }, 422);
    throw error;
  }
}

async function publish(request: Request, env: RulesAdminEnv, areas: RulesAreas): Promise<Response> {
  const page = await readPage(request, areas);
  if (page instanceof Response) return page;
  let prepared: Awaited<ReturnType<typeof preparePage>>;
  try { prepared = await preparePage(env, areas, page.areaId, page.html); }
  catch (error) {
    if (error instanceof RulesPageError) return json({ error: error.message }, 422);
    throw error;
  }
  const { name, parsed, changes, contentHash } = prepared;
  const at = new Date().toISOString();
  await env.RULES_DB.batch([
    env.RULES_DB.prepare(`INSERT INTO mpi_fishing_rules
        (area_id, area_name, source_url, page_title, reviewed_at, fetched_at, content_sha256, page_text, sections_json, tables_json, page_html)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, source_url=excluded.source_url, page_title=excluded.page_title,
          reviewed_at=excluded.reviewed_at, fetched_at=excluded.fetched_at, content_sha256=excluded.content_sha256,
          page_text=excluded.page_text, sections_json=excluded.sections_json, tables_json=excluded.tables_json, page_html=excluded.page_html`)
      .bind(page.areaId, name, areas.officialUrl(page.areaId), parsed.title, parsed.reviewedAt, at, contentHash, parsed.pageText,
        JSON.stringify(parsed.sections), JSON.stringify(parsed.tables), page.html),
    env.RULES_DB.prepare(`INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
        VALUES (?, ?, ?, ?, 'success', 200, NULL)
        ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, last_attempt_at=excluded.last_attempt_at,
          last_success_at=excluded.last_success_at, status='success', http_status=200, error=NULL`)
      .bind(page.areaId, name, at, at),
  ]);
  const history = (await readSetting<HistoryEntry[]>(env.RULES_DB, HISTORY_KEY)) ?? [];
  await writeSetting(env.RULES_DB, HISTORY_KEY, [{ area_id: page.areaId, at, reviewed_at: parsed.reviewedAt, changes: changes.length }, ...history].slice(0, 30));
  return overview(env, areas);
}

async function saveReminders(request: Request, env: RulesAdminEnv, areas: RulesAreas): Promise<Response> {
  const body = await request.json().catch(() => null) as { enabled?: unknown; email?: unknown; stale_days?: unknown } | null;
  const email = typeof body?.email === "string" ? body.email.trim() : "";
  const staleDays = Number(body?.stale_days);
  if (typeof body?.enabled !== "boolean" || (body.enabled && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) || email.length > 254 ||
    !Number.isInteger(staleDays) || staleDays < 7 || staleDays > 365) {
    return json({ error: "Enter an email address and a number of days from 7 to 365." }, 400);
  }
  const current = await reminderSettings(env);
  await writeSetting(env.RULES_DB, REMINDERS_KEY, { ...current, enabled: body.enabled, email, stale_days: staleDays });
  return overview(env, areas);
}

/** `/v1/admin/rules` routes (the caller checks the admin token), or null for other paths. */
export async function handleRulesAdmin(request: Request, env: RulesAdminEnv, pathname: string, areas: RulesAreas): Promise<Response | null> {
  if (pathname === "/v1/admin/rules" && request.method === "GET") return overview(env, areas);
  if (pathname === "/v1/admin/rules/preview" && request.method === "POST") return preview(request, env, areas);
  if (pathname === "/v1/admin/rules/publish" && request.method === "POST") return publish(request, env, areas);
  if (pathname === "/v1/admin/rules/reminders" && request.method === "PUT") return saveReminders(request, env, areas);
  return null;
}

/**
 * Run by the daily cron: one email listing the areas that look out of date. It is sent again only when the list
 * changes or a week has passed, and never when reminders are off or there is nothing to say.
 */
export async function sendRulesReminders(env: RulesAdminEnv, areas: RulesAreas, now = Date.now()): Promise<"sent" | "nothing" | "skipped" | "failed"> {
  const settings = await reminderSettings(env);
  if (!settings.enabled || !settings.email) return "skipped";
  const due = (await storedAreas(env))
    .filter((area) => areas.names.has(area.area_id))
    .map((area) => ({ area, name: areas.names.get(area.area_id)!, ...reasonsFor(area, settings.stale_days, now) }))
    .filter((item) => item.reasons.length > 0)
    .sort((a, b) => a.name.localeCompare(b.name));
  if (due.length === 0) return "nothing";
  const fingerprint = await sha256Hex(JSON.stringify(due.map((item) => [item.area.area_id, item.started.map((change) => change.date), item.area.status])));
  const lastSent = settings.last_sent_at ? Date.parse(settings.last_sent_at) : 0;
  if (fingerprint === settings.last_reasons && now - lastSent < 7 * DAY) return "skipped";
  const cms = `${(env.CMS_BASE_URL || "https://cms.fishnz.space").replace(/\/+$/, "")}/admin/rules`;
  const lines = [
    due.length === 1 ? "One area's fishing rules in Fishdays - NZ may be out of date." : `${due.length} areas' fishing rules in Fishdays - NZ may be out of date.`,
    "",
    ...due.flatMap((item) => [
      `${item.name} (saved ${niceDate(nzDay(Date.parse(item.area.fetched_at)))}${item.area.reviewed_at ? `, MPI page reviewed ${item.area.reviewed_at}` : ""})`,
      ...item.reasons.map((reason) => `- ${reason}`),
      "",
    ]),
    "To update an area: open its MPI page in your browser, save the page, and upload it on the CMS Rules page:",
    cms,
    "",
    "MPI's website blocks automatic downloads, so updates use a page saved from a browser. Turn these reminders off or change them on the same page.",
  ];
  const first = due[0].name;
  const sent = await sendEmail(env, {
    to: settings.email,
    subject: due.length === 1 ? `Fishing rules to check: ${first}` : `Fishing rules to check: ${first} and ${due.length - 1} more`,
    text: lines.join("\n"),
  });
  if (!sent) return "failed";
  await writeSetting(env.RULES_DB, REMINDERS_KEY, { ...settings, last_sent_at: new Date(now).toISOString(), last_reasons: fingerprint });
  return "sent";
}
