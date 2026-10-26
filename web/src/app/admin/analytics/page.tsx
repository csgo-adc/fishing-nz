"use client";

import Link from "next/link";
import { ReactNode, RefObject, useCallback, useEffect, useRef, useState } from "react";
import { AdminHeader, ApiError, BarList, CopyButton, DailyColumns, ErrorCount, Pager, SearchField, Stat, When, api, formatDate, mono, number, readOffset, readParams, useNow, writeParams } from "../cms";
import styles from "./analytics.module.css";

type Usage = { route: string; calls: number; errors: number };
type Overview = {
  range_days: number;
  totals: { active_devices: number; anonymous_devices: number; active_users: number; events: number; api_calls: number; api_errors: number };
  daily: Array<{ day: string; events: number; devices: number; users: number; api_calls: number; api_errors: number }>;
  events_by_name: Array<{ event_name: string; events: number; devices: number }>;
  top_screens: Array<{ screen: string | null; views: number; devices: number }>;
  api_by_route: Array<Usage & { users: number; devices: number }>;
  devices_by_version: Array<{ platform: string; app_version: string; devices: number }>;
};
type UserRow = {
  id: string; email: string; display_name: string; plan: string; created_at: string; email_verified: number;
  api_calls: number; api_errors: number; events: number; last_event_at: string | null; devices: number;
};
type DeviceRow = {
  device_id: string; platform: string; os_version: string; device_model: string; app_version: string; locale: string; time_zone: string;
  first_seen_at: string; last_seen_at: string; user_id: string | null; user_email: string | null;
  api_calls?: number; api_errors?: number; events?: number;
};
type Page = { total: number; limit: number; offset: number };
type SubjectPage = (Page & { type: "user"; subjects: UserRow[] }) | (Page & { type: "device"; subjects: DeviceRow[] });
type TimelineEvent = { event_name: string; device_id: string; user_id: string | null; props: Record<string, unknown>; occurred_at: string };
type EventCount = { event_name: string; events: number };
type UserDetail = {
  range_days: number;
  user: { id: string; email: string; display_name: string; country_code: string; plan: string; created_at: string; email_verified: boolean };
  first_event_at: string | null; last_event_at: string | null;
  devices: DeviceRow[]; api_by_route: Usage[]; api_by_day: Array<{ day: string; calls: number; errors: number }>;
  events_by_name: EventCount[]; recent_events: TimelineEvent[];
  fish_identification: { today: { limit: number; used: number; remaining: number }; history: Array<{ day: string; used: number }> };
  feedback_count: number;
};
type DeviceDetail = {
  range_days: number; device: DeviceRow; api_by_route: Usage[]; api_by_day: Array<{ day: string; calls: number; errors: number }>;
  events_by_name: EventCount[]; recent_events: TimelineEvent[];
};
type Kind = "user" | "device";
// The panel opens straight away with data null while it loads.
type Detail = { kind: "user"; id: string; data: UserDetail | null; error?: string } | { kind: "device"; id: string; data: DeviceDetail | null; error?: string };

const RANGES = [7, 30, 90];
const PAGE_SIZE = 25;
const SORTS = ["calls", "events", "recent", "newest"];
const SCOPES = ["all", "anonymous", "linked"];
const OPEN_PATTERN = /^(user|device):([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$/i;

const shortId = (value: string) => value.slice(0, 8);
const describe = (props: Record<string, unknown>) => Object.entries(props).map(([key, value]) => `${key}: ${String(value)}`).join(" · ");

function fromUrl() {
  const params = readParams();
  const days = Number(params.get("days"));
  const sort = params.get("sort") || "";
  const scope = params.get("scope") || "";
  const open = (params.get("open") || "").match(OPEN_PATTERN);
  return {
    days: RANGES.includes(days) ? days : 30,
    kind: (params.get("type") === "device" ? "device" : "user") as Kind,
    sort: SORTS.includes(sort) ? sort : "calls",
    scope: SCOPES.includes(scope) ? scope : "all",
    offset: readOffset(params.get("offset")),
    search: (params.get("q") || "").trim().slice(0, 120),
    open: open ? { kind: open[1].toLowerCase() as Kind, id: open[2].toLowerCase() } : null,
  };
}

export default function AnalyticsPage() {
  const now = useNow();
  const [ready, setReady] = useState(false);
  const [days, setDays] = useState(30);
  const [overview, setOverview] = useState<Overview | null>(null);
  const [subjects, setSubjects] = useState<SubjectPage | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [kind, setKind] = useState<Kind>("user");
  const [sort, setSort] = useState("calls");
  const [scope, setScope] = useState("all");
  const [offset, setOffset] = useState(0);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [signedOut, setSignedOut] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [subjectsLoading, setSubjectsLoading] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const overviewRequest = useRef(0);
  const subjectsRequest = useRef(0);
  const detailRequest = useRef(0);
  const returnFocus = useRef<HTMLElement | null>(null);
  const panelTitle = useRef<HTMLHeadingElement>(null);

  const fail = useCallback((cause: unknown) => {
    if (cause instanceof ApiError && cause.status === 401) setSignedOut(true);
    else setError(cause instanceof Error ? cause.message : "Could not load analytics.");
  }, []);

  const loadOverview = useCallback(async (range: number) => {
    const request = ++overviewRequest.current;
    setLoading(true);
    try {
      const next = await api<Overview>(`/analytics/overview?days=${range}`);
      if (request === overviewRequest.current) { setOverview(next); setSignedOut(false); setError(""); setUpdatedAt(Date.now()); }
    } catch (cause) { if (request === overviewRequest.current) fail(cause); }
    finally { if (request === overviewRequest.current) setLoading(false); }
  }, [fail]);

  const loadSubjects = useCallback(async (range: number, type: Kind, order: string, filter: string, from: number, text: string) => {
    const request = ++subjectsRequest.current;
    const params = new URLSearchParams({ type, days: String(range), sort: order, limit: String(PAGE_SIZE), offset: String(from) });
    if (type === "device" && filter !== "all") params.set("scope", filter);
    if (text) params.set("search", text);
    setSubjectsLoading(true);
    try {
      const next = await api<SubjectPage>(`/analytics/subjects?${params}`);
      if (request === subjectsRequest.current) { setSubjects(next); setSignedOut(false); setError(""); }
    } catch (cause) { if (request === subjectsRequest.current) fail(cause); }
    finally { if (request === subjectsRequest.current) setSubjectsLoading(false); }
  }, [fail]);

  const loadDetail = useCallback(async (type: Kind, id: string, range: number) => {
    const request = ++detailRequest.current;
    setDetail((current) => current?.kind === type && current.id === id ? current : { kind: type, id, data: null } as Detail);
    try {
      const data = await api<UserDetail | DeviceDetail>(type === "user" ? `/analytics/users/${id}?days=${range}` : `/analytics/devices/${id}?days=${range}`);
      if (request === detailRequest.current) setDetail(type === "user" ? { kind: "user", id, data: data as UserDetail } : { kind: "device", id, data: data as DeviceDetail });
    } catch (cause) {
      if (request !== detailRequest.current) return;
      if (cause instanceof ApiError && cause.status === 401) { setSignedOut(true); setDetail(null); }
      else setDetail({ kind: type, id, data: null, error: cause instanceof Error ? cause.message : "Could not load this one." });
    }
  }, []);

  // Applies the address bar: on first load, and again when Back or Forward changes it.
  const applyUrl = useCallback(() => {
    const state = fromUrl();
    setDays(state.days); setKind(state.kind); setSort(state.sort); setScope(state.scope); setOffset(state.offset);
    setSearchInput(state.search); setSearch(state.search); setReady(true);
    if (state.open) void loadDetail(state.open.kind, state.open.id, state.days);
    else { detailRequest.current++; setDetail(null); }
  }, [loadDetail]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => { if (active) applyUrl(); });
    window.addEventListener("popstate", applyUrl);
    return () => { active = false; window.removeEventListener("popstate", applyUrl); };
  }, [applyUrl]);

  useEffect(() => {
    if (!ready) return;
    let active = true;
    queueMicrotask(() => { if (active) void loadOverview(days); });
    return () => { active = false; };
  }, [ready, days, loadOverview]);

  useEffect(() => {
    if (!ready) return;
    writeParams({ days: days === 30 ? null : days, type: kind === "user" ? null : kind, sort: sort === "calls" ? null : sort, scope: scope === "all" ? null : scope, q: search, offset });
    let active = true;
    queueMicrotask(() => { if (active) void loadSubjects(days, kind, sort, scope, offset, search); });
    return () => { active = false; };
  }, [ready, days, kind, sort, scope, offset, search, loadSubjects]);

  const closeDetail = useCallback(() => {
    detailRequest.current++;
    setDetail(null);
    writeParams({ open: null });
    if (returnFocus.current?.isConnected) returnFocus.current.focus();
  }, []);

  useEffect(() => {
    if (!detail) return;
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape" && !event.defaultPrevented) closeDetail();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [detail, closeDetail]);

  // Keeps keyboard focus on the panel heading as it opens and again when its data replaces the loading heading.
  const detailKey = detail ? `${detail.kind}:${detail.id}:${detail.data ? "ready" : "loading"}` : "";
  useEffect(() => {
    if (detailKey) panelTitle.current?.focus({ preventScroll: true });
  }, [detailKey]);

  function openDetail(type: Kind, id: string) {
    setError("");
    if (!detail && document.activeElement instanceof HTMLElement) returnFocus.current = document.activeElement;
    writeParams({ open: `${type}:${id}` }, true);
    void loadDetail(type, id, days);
  }

  function chooseDays(next: number) {
    setDays(next); setOffset(0);
    if (detail) void loadDetail(detail.kind, detail.id, next);
  }

  function chooseKind(next: Kind) {
    if (next === kind) return;
    setKind(next); setOffset(0); setSort("calls"); setScope("all"); setSearchInput(""); setSearch(""); setSubjects(null);
  }

  const runSearch = useCallback((text: string) => { setOffset(0); setSearch(text); }, []);

  function refresh() {
    void loadOverview(days);
    void loadSubjects(days, kind, sort, scope, offset, search);
    if (detail) void loadDetail(detail.kind, detail.id, days);
  }

  async function signOut() {
    try { await api("/session", "DELETE"); } catch { /* The session cookie may already be gone. */ }
    detailRequest.current++;
    setDetail(null); setSignedOut(true);
    writeParams({ open: null });
  }

  const noun = kind === "user" ? "accounts" : "devices";
  const searchStatus = !subjects ? "" : subjectsLoading ? "Searching…" : search
    ? `${number(subjects.total)} ${subjects.total === 1 ? noun.slice(0, -1) : noun} match “${search}”`
    : `${number(subjects.total)} ${noun}`;

  return (
    <main className={`cms-shell ${detail ? styles.withPanel : ""}`}>
      <AdminHeader page="usage" updatedAt={updatedAt} now={now} refreshing={loading} onRefresh={signedOut ? undefined : refresh} onSignOut={signedOut ? undefined : () => void signOut()} />
      <section className="cms-content">
        <div className="cms-heading">
          <div><p className="kicker">Fishdays - NZ operations</p><h1>Usage analysis</h1><p>Who uses the app, how much of the API they use, and what they do. Devices that are not signed in appear as anonymous.</p></div>
          {!signedOut && <label className={styles.period}>Period<select value={days} onChange={(event) => chooseDays(Number(event.target.value))}>{RANGES.map((range) => <option key={range} value={range}>Last {range} days</option>)}</select></label>}
        </div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {signedOut && <div className="cms-login" role="status"><h2>Sign in to the CMS</h2><p>Your administrator session has ended or has not started.</p><Link className="button button-primary" href="/admin">Go to administrator sign in</Link></div>}
        {!signedOut && <>
          {loading && !overview && <div className="cms-login" role="status"><h2>Loading analysis…</h2></div>}
          {overview && <>
            <div className="cms-stat-grid">
              <Stat title="Active devices" value={overview.totals.active_devices} detail={`${number(overview.totals.anonymous_devices)} not signed in`} />
              <Stat title="Active accounts" value={overview.totals.active_users} detail={`Last ${overview.range_days} days`} />
              <Stat title="Events recorded" value={overview.totals.events} detail="Screens, searches, fish photos and more" />
              <Stat title="API calls" value={overview.totals.api_calls} detail={`${number(overview.totals.api_errors)} returned an error`} />
            </div>
            <div className="cms-chart-grid">
              <DailyColumns title="Active devices by day" rows={overview.daily.map((row) => ({ day: row.day, value: row.devices }))} days={overview.range_days} now={now} />
              <DailyColumns title="API calls by day" rows={overview.daily.map((row) => ({ day: row.day, value: row.api_calls }))} days={overview.range_days} now={now} total />
              <BarList title="API calls by type" rows={overview.api_by_route.map((row) => ({ label: row.route, value: row.calls }))} />
              <BarList title="Most viewed screens" rows={overview.top_screens.map((row) => ({ label: row.screen || "unknown", value: row.views }))} />
              <BarList title="Behaviour events" rows={overview.events_by_name.map((row) => ({ label: row.event_name, value: row.events }))} />
              <BarList title="Devices by app version" rows={overview.devices_by_version.map((row) => ({ label: `${row.platform} ${row.app_version || "unknown"}`, value: row.devices }))} />
            </div>
          </>}

          <section className="cms-card" id="people">
            <div className="cms-section-heading"><div><h2>People and devices</h2><p>Open one to see exactly what it did and how much API it used.</p></div></div>
            <div className={styles.tabs} role="group" aria-label="List type">
              <button type="button" aria-pressed={kind === "user"} onClick={() => chooseKind("user")}>Accounts</button>
              <button type="button" aria-pressed={kind === "device"} onClick={() => chooseKind("device")}>Devices</button>
            </div>
            <div className={styles.controls}>
              <SearchField id="usage-search" label={kind === "user" ? "Search accounts" : "Search devices"} placeholder={kind === "user" ? "Name or email" : "Device ID, model or account email"} value={searchInput} applied={search} status={searchStatus} onChange={setSearchInput} onSearch={runSearch} />
              <label>Sort by<select value={sort} onChange={(event) => { setSort(event.target.value); setOffset(0); }}>
                <option value="calls">Most API calls</option><option value="events">Most events</option><option value="recent">Latest activity</option><option value="newest">Newest</option>
              </select></label>
              {kind === "device" && <label>Show<select value={scope} onChange={(event) => { setScope(event.target.value); setOffset(0); }}>
                <option value="all">All devices</option><option value="anonymous">Not signed in</option><option value="linked">Linked to an account</option>
              </select></label>}
            </div>
            {!subjects ? <p className="empty-state">Loading…</p> : subjects.subjects.length === 0 ? <p className="empty-state">{search ? "Nothing matches that search." : "Nothing here yet. Devices appear once an app with analytics turned on connects."}</p> :
              <div className={`cms-table-wrap ${subjectsLoading ? styles.dim : ""}`} aria-busy={subjectsLoading}>
                {subjects.type === "user" ? <table><thead><tr><th scope="col">Account</th><th scope="col">API calls</th><th scope="col">Errors</th><th scope="col">Events</th><th scope="col">Devices</th><th scope="col">Last activity</th></tr></thead><tbody>
                  {subjects.subjects.map((row) => <tr key={row.id} className={detail?.id === row.id ? styles.selected : undefined}>
                    <td><button type="button" className={styles.rowButton} aria-current={detail?.id === row.id ? "true" : undefined} onClick={() => openDetail("user", row.id)}>{row.display_name || row.email}</button>{row.display_name && <small>{row.email}</small>}</td>
                    <td data-label="API calls">{number(row.api_calls)}</td><td data-label="Errors"><ErrorCount value={row.api_errors} /></td><td data-label="Events">{number(row.events)}</td>
                    <td data-label="Devices">{number(row.devices)}</td><td data-label="Last activity"><When value={row.last_event_at} now={now} /></td></tr>)}
                </tbody></table> : <table><thead><tr><th scope="col">Device</th><th scope="col">Account</th><th scope="col">API calls</th><th scope="col">Errors</th><th scope="col">Events</th><th scope="col">App</th><th scope="col">Last seen</th></tr></thead><tbody>
                  {subjects.subjects.map((row) => <tr key={row.device_id} className={detail?.id === row.device_id ? styles.selected : undefined}>
                    <td><button type="button" className={styles.rowButton} aria-current={detail?.id === row.device_id ? "true" : undefined} onClick={() => openDetail("device", row.device_id)}>{row.device_model || row.platform}</button><small><span className={mono}>{shortId(row.device_id)}</span> · {row.platform} {row.os_version}</small></td>
                    <td data-label="Account">{row.user_email ?? <span className={styles.signedOut}>Not signed in</span>}</td>
                    <td data-label="API calls">{number(row.api_calls)}</td><td data-label="Errors"><ErrorCount value={row.api_errors} /></td><td data-label="Events">{number(row.events)}</td>
                    <td data-label="App">{row.app_version || "—"}</td><td data-label="Last seen"><When value={row.last_seen_at} now={now} /></td></tr>)}
                </tbody></table>}
              </div>}
            {subjects && <Pager total={subjects.total} limit={subjects.limit} offset={subjects.offset} disabled={subjectsLoading} onPage={(next) => { setOffset(next); document.getElementById("people")?.scrollIntoView({ behavior: "smooth", block: "start" }); }} />}
          </section>
        </>}
      </section>

      {detail && <>
        <div className={styles.backdrop} onClick={closeDetail} aria-hidden="true" />
        <aside className={styles.panel} aria-labelledby="detail-title">
          {!detail.data ? <>
            <div className={styles.panelHead}><div><h2 id="detail-title" ref={panelTitle} tabIndex={-1}>{detail.error ? "Couldn’t open this" : "Loading…"}</h2></div><button type="button" className={styles.close} onClick={closeDetail} aria-label="Close details">×</button></div>
            {detail.error ? <p className="form-error" role="alert">{detail.error}</p> : <p className="empty-state" role="status">Loading the last {days} days…</p>}
          </> : detail.kind === "user" ? <UserView detail={detail.data} now={now} title={panelTitle} onDevice={(id) => openDetail("device", id)} onClose={closeDetail} />
            : <DeviceView detail={detail.data} now={now} title={panelTitle} onUser={(id) => openDetail("user", id)} onClose={closeDetail} />}
        </aside>
      </>}
    </main>
  );
}

type Title = RefObject<HTMLHeadingElement | null>;

function PanelHead({ title, titleRef, subtitle, onClose }: { title: string; titleRef: Title; subtitle: ReactNode; onClose: () => void }) {
  return (
    <div className={styles.panelHead}>
      <div><h2 id="detail-title" ref={titleRef} tabIndex={-1}>{title}</h2><p>{subtitle}</p></div>
      <button type="button" className={styles.close} onClick={onClose} aria-label="Close details" title="Close (Esc)">×</button>
    </div>
  );
}

function UserView({ detail, now, title, onDevice, onClose }: { detail: UserDetail; now: number | null; title: Title; onDevice: (id: string) => void; onClose: () => void }) {
  const calls = detail.api_by_route.reduce((sum, row) => sum + row.calls, 0);
  const errors = detail.api_by_route.reduce((sum, row) => sum + row.errors, 0);
  return <>
    <PanelHead title={detail.user.display_name || detail.user.email} titleRef={title} onClose={onClose} subtitle={<>{detail.user.display_name ? `${detail.user.email} · ` : ""}last {detail.range_days} days</>} />
    <dl className={styles.facts}>
      <div><dt>Plan</dt><dd className={styles.capitalise}>{detail.user.plan}</dd></div>
      <div><dt>Email</dt><dd>{detail.user.email_verified ? "Confirmed" : "Not confirmed"}</dd></div>
      <div><dt>Joined</dt><dd>{formatDate(detail.user.created_at)}</dd></div>
      <div><dt>Last seen</dt><dd><When value={detail.last_event_at} now={now} /></dd></div>
      <div><dt>First seen in period</dt><dd><When value={detail.first_event_at} now={now} /></dd></div>
      <div><dt>API calls</dt><dd>{number(calls)} {errors > 0 ? <>· <ErrorCount value={errors} /> errors</> : "· no errors"}</dd></div>
      <div><dt>Fish photos today</dt><dd>{detail.fish_identification.today.used} of {detail.fish_identification.today.limit}</dd></div>
      <div><dt>Feedback sent</dt><dd>{number(detail.feedback_count)}</dd></div>
    </dl>
    <div className={styles.panelCharts}>
      <DailyColumns title="API calls by day" rows={detail.api_by_day.map((row) => ({ day: row.day, value: row.calls }))} days={detail.range_days} now={now} total />
      <BarList title="API calls by type" limit={6} rows={detail.api_by_route.map((row) => ({ label: row.route, value: row.calls }))} />
      <BarList title="What they did" limit={6} rows={detail.events_by_name.map((row) => ({ label: row.event_name, value: row.events }))} />
      <DailyColumns title="Fish photos identified" rows={detail.fish_identification.history.map((row) => ({ day: row.day, value: row.used }))} days={detail.range_days} now={now} total />
    </div>
    <h3 className={styles.subheading}>Devices used</h3>
    {detail.devices.length === 0 ? <p className="empty-state">No device has connected with analytics on.</p> : <ul className={styles.deviceList}>
      {detail.devices.map((device) => <li key={device.device_id}>
        <button type="button" className={styles.rowButton} onClick={() => onDevice(device.device_id)}>{device.device_model || device.platform}</button>
        <small>{device.platform} {device.os_version} · app {device.app_version || "—"} · {device.locale || "—"} · last seen <When value={device.last_seen_at} now={now} /></small>
        <span><span className={mono}>{shortId(device.device_id)}</span> <CopyButton value={device.device_id} /></span>
      </li>)}
    </ul>}
    <Timeline events={detail.recent_events} now={now} />
  </>;
}

function DeviceView({ detail, now, title, onUser, onClose }: { detail: DeviceDetail; now: number | null; title: Title; onUser: (id: string) => void; onClose: () => void }) {
  const device = detail.device;
  const calls = detail.api_by_route.reduce((sum, row) => sum + row.calls, 0);
  const errors = detail.api_by_route.reduce((sum, row) => sum + row.errors, 0);
  return <>
    <PanelHead title={device.device_model || device.platform} titleRef={title} onClose={onClose} subtitle={<><span className={mono}>{device.device_id}</span> <CopyButton value={device.device_id} /></>} />
    <dl className={styles.facts}>
      <div><dt>Account</dt><dd>{device.user_id && device.user_email ? <button type="button" className={styles.rowButton} onClick={() => onUser(device.user_id as string)}>{device.user_email}</button> : "Not signed in"}</dd></div>
      <div><dt>System</dt><dd>{device.platform} {device.os_version}</dd></div>
      <div><dt>App version</dt><dd>{device.app_version || "—"}</dd></div>
      <div><dt>Language · time zone</dt><dd>{device.locale || "—"} · {device.time_zone || "—"}</dd></div>
      <div><dt>First seen</dt><dd><When value={device.first_seen_at} now={now} /></dd></div>
      <div><dt>Last seen</dt><dd><When value={device.last_seen_at} now={now} /></dd></div>
      <div><dt>API calls · last {detail.range_days} days</dt><dd>{number(calls)} {errors > 0 ? <>· <ErrorCount value={errors} /> errors</> : "· no errors"}</dd></div>
    </dl>
    <div className={styles.panelCharts}>
      <DailyColumns title="API calls by day" rows={detail.api_by_day.map((row) => ({ day: row.day, value: row.calls }))} days={detail.range_days} now={now} total />
      <BarList title="API calls by type" limit={6} rows={detail.api_by_route.map((row) => ({ label: row.route, value: row.calls }))} />
      <BarList title="What it did" limit={6} rows={detail.events_by_name.map((row) => ({ label: row.event_name, value: row.events }))} />
    </div>
    <Timeline events={detail.recent_events} now={now} />
  </>;
}

function Timeline({ events, now }: { events: TimelineEvent[]; now: number | null }) {
  return <>
    <h3 className={styles.subheading}>Recent activity</h3>
    {events.length === 0 ? <p className="empty-state">No events in this period.</p> : <ol className={styles.timeline}>
      {events.map((event, index) => <li key={`${event.occurred_at}-${index}`}>
        <When value={event.occurred_at} now={now} />
        <div>
          <b>{event.event_name}</b>{!event.user_id && <span className={styles.signedOut}> · before sign-in</span>}
          {Object.keys(event.props).length > 0 && <small>{describe(event.props)}</small>}
        </div>
        <span className={mono} title={event.device_id}>{shortId(event.device_id)}</span>
      </li>)}
    </ol>}
  </>;
}
