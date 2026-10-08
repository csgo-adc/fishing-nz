"use client";

import NextImage from "next/image";
import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
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
type Detail = { kind: "user"; data: UserDetail } | { kind: "device"; data: DeviceDetail };

const RANGES = [7, 30, 90];
const PAGE_SIZE = 25;

class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

async function api<T>(path: string): Promise<T> {
  const response = await fetch(`/api/cms${path}`, { cache: "no-store" });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(typeof payload.error === "string" ? payload.error : "Could not load analytics.", response.status);
  return payload as T;
}

const when = (value: string | null) => value ? new Date(value).toLocaleString() : "Never";
const shortId = (value: string) => value.slice(0, 8);
const number = (value: number | undefined) => (value ?? 0).toLocaleString();
const describe = (props: Record<string, unknown>) => Object.entries(props).map(([key, value]) => `${key}: ${String(value)}`).join(" · ");

export default function AnalyticsPage() {
  const [days, setDays] = useState(30);
  const [overview, setOverview] = useState<Overview | null>(null);
  const [subjects, setSubjects] = useState<SubjectPage | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [kind, setKind] = useState<"user" | "device">("user");
  const [sort, setSort] = useState("calls");
  const [scope, setScope] = useState("all");
  const [offset, setOffset] = useState(0);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [signedOut, setSignedOut] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const overviewRequest = useRef(0);
  const subjectsRequest = useRef(0);

  const fail = useCallback((cause: unknown) => {
    if (cause instanceof ApiError && cause.status === 401) setSignedOut(true);
    else setError(cause instanceof Error ? cause.message : "Could not load analytics.");
  }, []);

  const loadOverview = useCallback(async (range: number) => {
    const request = ++overviewRequest.current;
    try {
      const next = await api<Overview>(`/analytics/overview?days=${range}`);
      if (request === overviewRequest.current) { setOverview(next); setSignedOut(false); setError(""); }
    } catch (cause) { if (request === overviewRequest.current) fail(cause); }
    finally { if (request === overviewRequest.current) setLoading(false); }
  }, [fail]);

  const loadSubjects = useCallback(async (range: number, type: string, order: string, filter: string, from: number, text: string) => {
    const request = ++subjectsRequest.current;
    const params = new URLSearchParams({ type, days: String(range), sort: order, limit: String(PAGE_SIZE), offset: String(from) });
    if (type === "device" && filter !== "all") params.set("scope", filter);
    if (text) params.set("search", text);
    try {
      const next = await api<SubjectPage>(`/analytics/subjects?${params}`);
      if (request === subjectsRequest.current) { setSubjects(next); setSignedOut(false); setError(""); }
    } catch (cause) { if (request === subjectsRequest.current) fail(cause); }
  }, [fail]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => { if (active) void loadOverview(days); });
    return () => { active = false; };
  }, [days, loadOverview]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => { if (active) void loadSubjects(days, kind, sort, scope, offset, search); });
    return () => { active = false; };
  }, [days, kind, sort, scope, offset, search, loadSubjects]);

  async function openDetail(type: "user" | "device", id: string) {
    setError("");
    try {
      const path = type === "user" ? `/analytics/users/${id}?days=${days}` : `/analytics/devices/${id}?days=${days}`;
      const data = await api<UserDetail | DeviceDetail>(path);
      setDetail(type === "user" ? { kind: "user", data: data as UserDetail } : { kind: "device", data: data as DeviceDetail });
      queueMicrotask(() => document.getElementById("analytics-detail")?.scrollIntoView({ behavior: "smooth", block: "start" }));
    } catch (cause) { fail(cause); }
  }

  function chooseKind(next: "user" | "device") {
    setKind(next); setOffset(0); setSort("calls"); setScope("all"); setSubjects(null);
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setOffset(0);
    setSearch(searchInput.trim());
  }

  const refresh = () => { setLoading(true); void loadOverview(days); void loadSubjects(days, kind, sort, scope, offset, search); };

  return (
    <main className="cms-shell">
      <header className="site-header cms-header"><Link className="brand" href="/admin"><NextImage className="brand-mark" src="/fishing-days-icon.png" width={36} height={36} alt="" unoptimized />Fishdays - NZ</Link><Link className="back-link" href="/admin">← Accounts</Link></header>
      <section className="cms-content">
        <div className="cms-heading"><div><p className="kicker">Fishdays - NZ operations</p><h1>Usage analysis</h1><p>Who uses the app, how much of the API they use, and what they do. Devices that are not signed in appear as anonymous.</p></div></div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {signedOut && <div className="cms-login" role="status"><h2>Sign in to the CMS</h2><p>Your administrator session has ended or has not started.</p><Link className="button button-primary" href="/admin">Go to administrator sign in</Link></div>}
        {!signedOut && <>
          <div className={styles.controls}>
            <label>Period<select value={days} onChange={(event) => { setLoading(true); setDays(Number(event.target.value)); setOffset(0); }}>{RANGES.map((range) => <option key={range} value={range}>Last {range} days</option>)}</select></label>
            <button type="button" className="signout-button" onClick={refresh} disabled={loading}>Refresh</button>
          </div>
          {loading && !overview && <div className="cms-login" role="status"><h2>Loading analysis…</h2></div>}
          {overview && <>
            <div className="cms-stat-grid">
              <Stat title="Active devices" value={overview.totals.active_devices} detail={`${number(overview.totals.anonymous_devices)} not signed in`} />
              <Stat title="Active accounts" value={overview.totals.active_users} detail={`Last ${overview.range_days} days`} />
              <Stat title="Events recorded" value={overview.totals.events} detail="Screens, searches, fish photos and more" />
              <Stat title="API calls" value={overview.totals.api_calls} detail={`${number(overview.totals.api_errors)} returned an error`} />
            </div>
            <div className="cms-chart-grid">
              <Chart title="Active devices by day" rows={overview.daily.map((row) => ({ label: row.day, value: row.devices }))} />
              <Chart title="API calls by day" rows={overview.daily.map((row) => ({ label: row.day, value: row.api_calls }))} />
              <Chart title="API calls by type" rows={overview.api_by_route.map((row) => ({ label: row.route, value: row.calls }))} />
              <Chart title="Most viewed screens" rows={overview.top_screens.map((row) => ({ label: row.screen || "unknown", value: row.views }))} />
              <Chart title="Behaviour events" rows={overview.events_by_name.slice(0, 14).map((row) => ({ label: row.event_name, value: row.events }))} />
              <Chart title="Devices by app version" rows={overview.devices_by_version.map((row) => ({ label: `${row.platform} ${row.app_version || "unknown"}`, value: row.devices }))} />
            </div>
          </>}

          <section className="cms-card">
            <div className="cms-section-heading"><div><h2>People and devices</h2><p>Open one to see exactly what it did and how much API it used.</p></div>{subjects && <span>{subjects.total.toLocaleString()} total</span>}</div>
            <div className={styles.tabs} role="group" aria-label="List type">
              <button type="button" aria-pressed={kind === "user"} onClick={() => chooseKind("user")}>Accounts</button>
              <button type="button" aria-pressed={kind === "device"} onClick={() => chooseKind("device")}>Devices</button>
            </div>
            <form className={styles.controls} onSubmit={submitSearch}>
              <label>{kind === "user" ? "Name or email" : "Device id, model or account email"}<input type="search" maxLength={120} value={searchInput} onChange={(event) => setSearchInput(event.target.value)} /></label>
              <label>Sort by<select value={sort} onChange={(event) => { setSort(event.target.value); setOffset(0); }}>
                <option value="calls">Most API calls</option><option value="events">Most events</option><option value="recent">Latest activity</option><option value="newest">Newest</option>
              </select></label>
              {kind === "device" && <label>Show<select value={scope} onChange={(event) => { setScope(event.target.value); setOffset(0); }}>
                <option value="all">All devices</option><option value="anonymous">Not signed in</option><option value="linked">Linked to an account</option>
              </select></label>}
              <button className="button button-primary" type="submit">Search</button>
            </form>
            {!subjects ? <p className="empty-state">Loading…</p> : subjects.subjects.length === 0 ? <p className="empty-state">Nothing matches yet. Devices appear once an app with analytics turned on connects.</p> :
              <div className="cms-table-wrap">
                {subjects.type === "user" ? <table><thead><tr><th scope="col">Account</th><th scope="col">API calls</th><th scope="col">Errors</th><th scope="col">Events</th><th scope="col">Devices</th><th scope="col">Last activity</th></tr></thead><tbody>
                  {subjects.subjects.map((row) => <tr key={row.id}>
                    <td><button type="button" className={styles.rowButton} onClick={() => void openDetail("user", row.id)}>{row.display_name || row.email}</button>{row.display_name && <small>{row.email}</small>}</td>
                    <td data-label="API calls">{number(row.api_calls)}</td><td data-label="Errors">{number(row.api_errors)}</td><td data-label="Events">{number(row.events)}</td>
                    <td data-label="Devices">{number(row.devices)}</td><td data-label="Last activity">{when(row.last_event_at)}</td></tr>)}
                </tbody></table> : <table><thead><tr><th scope="col">Device</th><th scope="col">Account</th><th scope="col">API calls</th><th scope="col">Errors</th><th scope="col">Events</th><th scope="col">App</th><th scope="col">Last seen</th></tr></thead><tbody>
                  {subjects.subjects.map((row) => <tr key={row.device_id}>
                    <td><button type="button" className={styles.rowButton} onClick={() => void openDetail("device", row.device_id)}>{row.device_model || row.platform}</button><small><span className={styles.mono}>{shortId(row.device_id)}</span> · {row.platform} {row.os_version}</small></td>
                    <td data-label="Account">{row.user_email ?? <span className={styles.signedOut}>Not signed in</span>}</td>
                    <td data-label="API calls">{number(row.api_calls)}</td><td data-label="Errors">{number(row.api_errors)}</td><td data-label="Events">{number(row.events)}</td>
                    <td data-label="App">{row.app_version || "—"}</td><td data-label="Last seen">{when(row.last_seen_at)}</td></tr>)}
                </tbody></table>}
              </div>}
            {subjects && subjects.total > subjects.limit && <nav className={styles.pager} aria-label="List pages">
              <span>Showing {(subjects.offset + 1).toLocaleString()}–{Math.min(subjects.offset + subjects.limit, subjects.total).toLocaleString()} of {subjects.total.toLocaleString()}</span>
              <div><button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Previous</button><button type="button" disabled={offset + PAGE_SIZE >= subjects.total} onClick={() => setOffset(offset + PAGE_SIZE)}>Next</button></div>
            </nav>}
          </section>

          {detail && <section id="analytics-detail" className={`cms-card ${styles.detail}`} aria-label="Detail">
            {detail.kind === "user" ? <UserView detail={detail.data} onDevice={(id) => void openDetail("device", id)} onClose={() => setDetail(null)} />
              : <DeviceView detail={detail.data} onUser={(id) => void openDetail("user", id)} onClose={() => setDetail(null)} />}
          </section>}
        </>}
      </section>
    </main>
  );
}

function UserView({ detail, onDevice, onClose }: { detail: UserDetail; onDevice: (id: string) => void; onClose: () => void }) {
  const calls = detail.api_by_route.reduce((sum, row) => sum + row.calls, 0);
  const errors = detail.api_by_route.reduce((sum, row) => sum + row.errors, 0);
  return <>
    <div className={styles.detailHead}><div><h2>{detail.user.display_name || detail.user.email}</h2><p>{detail.user.display_name ? detail.user.email : "Account"} · last {detail.range_days} days</p></div><button type="button" className="signout-button" onClick={onClose}>Close</button></div>
    <dl className={styles.facts}>
      <div><dt>Plan</dt><dd>{detail.user.plan}</dd></div>
      <div><dt>Email</dt><dd>{detail.user.email_verified ? "Confirmed" : "Not confirmed"}</dd></div>
      <div><dt>Joined</dt><dd>{when(detail.user.created_at)}</dd></div>
      <div><dt>First seen in period</dt><dd>{when(detail.first_event_at)}</dd></div>
      <div><dt>Last seen</dt><dd>{when(detail.last_event_at)}</dd></div>
      <div><dt>API calls</dt><dd>{number(calls)} ({number(errors)} errors)</dd></div>
      <div><dt>Fish photos today</dt><dd>{detail.fish_identification.today.used} of {detail.fish_identification.today.limit}</dd></div>
      <div><dt>Feedback sent</dt><dd>{number(detail.feedback_count)}</dd></div>
    </dl>
    <div className="cms-chart-grid">
      <Chart title="API calls by type" rows={detail.api_by_route.map((row) => ({ label: row.route, value: row.calls }))} />
      <Chart title="What they did" rows={detail.events_by_name.map((row) => ({ label: row.event_name, value: row.events }))} />
      <Chart title="API calls by day" rows={detail.api_by_day.map((row) => ({ label: row.day, value: row.calls }))} />
      <Chart title="Fish photos identified" rows={detail.fish_identification.history.map((row) => ({ label: row.day, value: row.used }))} />
    </div>
    <h3 className={styles.subheading}>Devices used</h3>
    {detail.devices.length === 0 ? <p className="empty-state">No device has connected with analytics on.</p> : <div className="cms-table-wrap"><table><thead><tr><th scope="col">Device</th><th scope="col">System</th><th scope="col">App</th><th scope="col">Language</th><th scope="col">Last seen</th></tr></thead><tbody>
      {detail.devices.map((device) => <tr key={device.device_id}><td><button type="button" className={styles.rowButton} onClick={() => onDevice(device.device_id)}>{device.device_model || device.platform}</button><small className={styles.mono}>{shortId(device.device_id)}</small></td><td data-label="System">{device.platform} {device.os_version}</td><td data-label="App">{device.app_version || "—"}</td><td data-label="Language">{device.locale || "—"}</td><td data-label="Last seen">{when(device.last_seen_at)}</td></tr>)}
    </tbody></table></div>}
    <Timeline events={detail.recent_events} showSignedIn />
  </>;
}

function DeviceView({ detail, onUser, onClose }: { detail: DeviceDetail; onUser: (id: string) => void; onClose: () => void }) {
  const device = detail.device;
  const calls = detail.api_by_route.reduce((sum, row) => sum + row.calls, 0);
  const errors = detail.api_by_route.reduce((sum, row) => sum + row.errors, 0);
  return <>
    <div className={styles.detailHead}><div><h2>{device.device_model || device.platform}</h2><p><span className={styles.mono}>{device.device_id}</span> · last {detail.range_days} days</p></div><button type="button" className="signout-button" onClick={onClose}>Close</button></div>
    <dl className={styles.facts}>
      <div><dt>Account</dt><dd>{device.user_id && device.user_email ? <button type="button" className={styles.rowButton} onClick={() => onUser(device.user_id as string)}>{device.user_email}</button> : "Not signed in"}</dd></div>
      <div><dt>System</dt><dd>{device.platform} {device.os_version}</dd></div>
      <div><dt>App version</dt><dd>{device.app_version || "—"}</dd></div>
      <div><dt>Language · time zone</dt><dd>{device.locale || "—"} · {device.time_zone || "—"}</dd></div>
      <div><dt>First seen</dt><dd>{when(device.first_seen_at)}</dd></div>
      <div><dt>Last seen</dt><dd>{when(device.last_seen_at)}</dd></div>
      <div><dt>API calls</dt><dd>{number(calls)} ({number(errors)} errors)</dd></div>
    </dl>
    <div className="cms-chart-grid">
      <Chart title="API calls by type" rows={detail.api_by_route.map((row) => ({ label: row.route, value: row.calls }))} />
      <Chart title="What it did" rows={detail.events_by_name.map((row) => ({ label: row.event_name, value: row.events }))} />
    </div>
    <Timeline events={detail.recent_events} showSignedIn />
  </>;
}

function Timeline({ events, showSignedIn }: { events: TimelineEvent[]; showSignedIn: boolean }) {
  return <>
    <h3 className={styles.subheading}>Recent activity</h3>
    {events.length === 0 ? <p className="empty-state">No events in this period.</p> : <div className="cms-table-wrap"><table><thead><tr><th scope="col">When</th><th scope="col">Event</th><th scope="col">Details</th><th scope="col">Device</th></tr></thead><tbody>
      {events.map((event, index) => <tr key={`${event.occurred_at}-${index}`}>
        <td data-label="When">{when(event.occurred_at)}</td>
        <td data-label="Event"><b>{event.event_name}</b>{showSignedIn && !event.user_id && <small className={styles.signedOut}>before sign-in</small>}</td>
        <td data-label="Details">{describe(event.props) || "—"}</td>
        <td data-label="Device"><span className={styles.mono}>{shortId(event.device_id)}</span></td>
      </tr>)}
    </tbody></table></div>}
  </>;
}

function Stat({ title, value, detail }: { title: string; value: number; detail: string }) {
  return <article className="cms-stat"><span>{title}</span><strong>{value.toLocaleString()}</strong><small>{detail}</small></article>;
}

function Chart({ title, rows }: { title: string; rows: Array<{ label: string; value: number }> }) {
  const max = Math.max(1, ...rows.map((row) => row.value));
  return <section className="cms-card cms-chart"><h2>{title}</h2>{rows.length === 0 ? <p className="empty-state">No activity in this period yet.</p> : rows.slice(-14).map((row) => <div className="chart-row" key={row.label}><span title={row.label}>{row.label}</span><div><i style={{ width: `${Math.max(3, (row.value / max) * 100)}%` }} /></div><b>{row.value.toLocaleString()}</b></div>)}</section>;
}
