"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";
import styles from "./admin.module.css";

type Analytics = {
  range_days: number;
  users: { total_users: number; verified_users: number; pending_users: number };
  plans: Array<{ plan: string; users: number }>;
  signups_by_day: Array<{ day: string; users: number }>;
  feature_usage: Array<{ feature: string; platform: string; uses: number; users: number }>;
  events_by_day: Array<{ day: string; events: number; users: number }>;
};
type User = { id: string; email: string; display_name: string; country_code: string; plan: "free" | "paid"; email_verified: boolean; created_at: string };
type Feedback = { id: string; email: string; category: string; message: string; rating: number | null; created_at: string };
type Page<T> = { total: number; limit: number; offset: number } & T;
type Dashboard = { analytics: Analytics; users: Page<{ users: User[] }>; feedback: Page<{ feedback: Feedback[] }> };

class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

async function api<T>(path: string, method = "GET", data?: unknown): Promise<T> {
  const response = await fetch(`/api/cms${path}`, { method, headers: data ? { "content-type": "application/json" } : undefined, body: data ? JSON.stringify(data) : undefined, cache: "no-store" });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(typeof payload.error === "string" ? payload.error : "Could not load CMS data.", response.status);
  return payload as T;
}

export default function AdminPage() {
  const [token, setToken] = useState("");
  const [connected, setConnected] = useState(false);
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [usersOffset, setUsersOffset] = useState(0);
  const [feedbackOffset, setFeedbackOffset] = useState(0);
  const [searchInput, setSearchInput] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");

  const load = useCallback(async (nextUsersOffset = 0, nextFeedbackOffset = 0, search = "") => {
    const params = new URLSearchParams({ users_offset: String(nextUsersOffset), feedback_offset: String(nextFeedbackOffset) });
    if (search) params.set("search", search);
    try {
      const nextDashboard = await api<Dashboard>(`/dashboard?${params}`);
      setDashboard(nextDashboard); setConnected(true); setError("");
      setUsersOffset(nextUsersOffset); setFeedbackOffset(nextFeedbackOffset); setAppliedSearch(search);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) {
        setConnected(false); setDashboard(null);
        if (cause.message !== "Sign in to the CMS.") setError(cause.message);
      } else setError(cause instanceof Error ? cause.message : "Could not load CMS data.");
    } finally { setLoading(false); }
  }, []);
  useEffect(() => {
    let active = true;
    queueMicrotask(() => { if (active) void load(); });
    return () => { active = false; };
  }, [load]);
  const refresh = useCallback(async (nextUsersOffset = 0, nextFeedbackOffset = 0, search = "") => {
    setLoading(true);
    await load(nextUsersOffset, nextFeedbackOffset, search);
  }, [load]);

  async function signIn(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { await api("/session", "POST", { token }); setToken(""); setConnected(true); await refresh(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Could not sign in."); }
    finally { setBusy(false); }
  }

  async function signOut() {
    setBusy(true); setError("");
    try { await api("/session", "DELETE"); setConnected(false); setDashboard(null); setSearchInput(""); setAppliedSearch(""); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Could not sign out."); }
    finally { setBusy(false); }
  }

  async function setPlan(userId: string, plan: "free" | "paid") {
    setBusy(true); setError("");
    try {
      await api(`/users/${userId}/plan`, "PATCH", { plan });
      setDashboard((current) => current ? {
        ...current,
        users: { ...current.users, users: current.users.users.map((user) => user.id === userId ? { ...user, plan } : user) },
      } : current);
      await refresh(usersOffset, feedbackOffset, appliedSearch);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) { setConnected(false); setDashboard(null); }
      setError(cause instanceof Error ? cause.message : "Could not update plan.");
    }
    finally { setBusy(false); }
  }

  function searchUsers(event: FormEvent) {
    event.preventDefault();
    void refresh(0, feedbackOffset, searchInput.trim());
  }

  return (
    <main className="cms-shell">
      <header className="site-header cms-header"><Link className="brand" href="/"><span className="brand-mark" aria-hidden="true">✓</span>CatchCheck NZ</Link><Link className="back-link" href="/account">Account</Link></header>
      <section className="cms-content">
        <div className="cms-heading"><div><p className="kicker">CatchCheck operations</p><h1>Account CMS</h1><p>User, plan, feedback, and feature activity for the last 30 days.</p></div>{connected && <div className={styles.actions}><button className="signout-button" disabled={busy || loading} onClick={() => void refresh(usersOffset, feedbackOffset, appliedSearch)}>Refresh</button><button className="signout-button" disabled={busy} onClick={() => void signOut()}>Sign out</button></div>}</div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {loading && !connected && <div className="cms-login" role="status"><h2>Checking your session…</h2></div>}
        {!loading && !connected && <form className="cms-login" onSubmit={signIn}><h2>Administrator sign in</h2><p>Use the account admin token configured on the CatchCheck Worker.</p><label>Admin token<input type="password" autoComplete="off" required value={token} onChange={(event) => setToken(event.target.value)} /></label><button className="button button-primary" disabled={busy}>{busy ? "Checking…" : "Open CMS"}</button></form>}
        {connected && !dashboard && <div className="cms-login" role="status"><h2>{loading ? "Loading dashboard…" : "Dashboard unavailable"}</h2>{!loading && <button className="button button-primary" onClick={() => void refresh()}>Try again</button>}</div>}
        {connected && dashboard && <>
          {loading && <p className={styles.refreshing} role="status">Updating dashboard…</p>}
          <div className="cms-stat-grid">
            <Stat title="Total users" value={dashboard.analytics.users.total_users} detail={`${dashboard.analytics.users.verified_users} confirmed`} />
            <Stat title="Waiting for email confirmation" value={dashboard.analytics.users.pending_users} detail="Must confirm to sign in" />
            <Stat title="Paid users" value={dashboard.analytics.plans.find((row) => row.plan === "paid")?.users || 0} detail="Current account plans" />
            <Stat title="Active accounts · latest day" value={dashboard.analytics.events_by_day.at(-1)?.users || 0} detail={dashboard.analytics.events_by_day.at(-1)?.day || "No activity yet"} />
          </div>
          <div className={`cms-chart-grid ${styles.charts}`}>
            <Chart title="New accounts by day" rows={dashboard.analytics.signups_by_day.map((row) => ({ label: row.day, value: row.users }))} />
            <Chart title="Feature usage · 30 days" rows={dashboard.analytics.feature_usage.map((row) => ({ label: `${row.feature} · ${row.platform}`, value: row.uses }))} />
            <Chart title="Daily active accounts" rows={dashboard.analytics.events_by_day.map((row) => ({ label: row.day, value: row.users }))} />
          </div>
          <section className="cms-card"><div className="cms-section-heading"><div><h2>Users</h2><p>Find an account and update its plan.</p></div><span>{dashboard.users.total.toLocaleString()} total</span></div>
            <form className={styles.search} onSubmit={searchUsers}><label htmlFor="cms-user-search">Search by name or email</label><div><input id="cms-user-search" type="search" maxLength={120} value={searchInput} onChange={(event) => setSearchInput(event.target.value)} placeholder="Name or email" /><button className="button button-primary" disabled={busy || loading}>Search</button></div></form>
            {dashboard.users.users.length === 0 ? <p className="empty-state">{appliedSearch ? "No users match that search." : "No accounts have registered yet."}</p> : <div className="cms-table-wrap"><table><thead><tr><th scope="col">User</th><th scope="col">Confirmed</th><th scope="col">Joined</th><th scope="col">Plan</th></tr></thead><tbody>{dashboard.users.users.map((user) => <tr key={user.id}><td><b>{user.display_name || user.email}</b>{user.display_name && <small>{user.email}</small>}</td><td data-label="Confirmed">{user.email_verified ? <span className="status-pill confirmed">Confirmed</span> : <span className="status-pill pending">Pending</span>}</td><td data-label="Joined">{new Date(user.created_at).toLocaleDateString()}</td><td data-label="Plan"><select aria-label={`Plan for ${user.email}`} disabled={busy || loading || !user.email_verified} value={user.plan} onChange={(event) => void setPlan(user.id, event.target.value as "free" | "paid")}><option value="free">Free</option><option value="paid">Paid</option></select></td></tr>)}</tbody></table></div>}
            <Pager total={dashboard.users.total} limit={dashboard.users.limit} offset={dashboard.users.offset} disabled={busy || loading} onPage={(offset) => void refresh(offset, feedbackOffset, appliedSearch)} />
          </section>
          <section className="cms-card"><div className="cms-section-heading"><div><h2>Feedback</h2><p>Messages from signed-in users.</p></div><span>{dashboard.feedback.total.toLocaleString()} total</span></div>{dashboard.feedback.feedback.length === 0 ? <p className="empty-state">No feedback has arrived yet.</p> : <div className="cms-feedback-list">{dashboard.feedback.feedback.map((item) => <article key={item.id}><div><b>{item.category}</b><span>{item.email}</span><time>{new Date(item.created_at).toLocaleString()}</time></div><p>{item.message}</p>{item.rating && <small>Rating: {item.rating}/5</small>}</article>)}</div>}<Pager total={dashboard.feedback.total} limit={dashboard.feedback.limit} offset={dashboard.feedback.offset} disabled={busy || loading} onPage={(offset) => void refresh(usersOffset, offset, appliedSearch)} /></section>
        </>}
      </section>
    </main>
  );
}

function Pager({ total, limit, offset, disabled, onPage }: { total: number; limit: number; offset: number; disabled: boolean; onPage: (offset: number) => void }) {
  if (total <= limit || limit <= 0) return null;
  const first = Math.min(offset + 1, total);
  const last = Math.min(offset + limit, total);
  return <nav className={styles.pager} aria-label="List pages"><span>Showing {first.toLocaleString()}–{last.toLocaleString()} of {total.toLocaleString()}</span><div><button type="button" disabled={disabled || offset === 0} onClick={() => onPage(Math.max(0, offset - limit))}>Previous</button><button type="button" disabled={disabled || offset + limit >= total} onClick={() => onPage(offset + limit)}>Next</button></div></nav>;
}

function Stat({ title, value, detail }: { title: string; value: number; detail: string }) {
  return <article className="cms-stat"><span>{title}</span><strong>{value.toLocaleString()}</strong><small>{detail}</small></article>;
}

function Chart({ title, rows }: { title: string; rows: Array<{ label: string; value: number }> }) {
  const max = Math.max(1, ...rows.map((row) => row.value));
  return <section className="cms-card cms-chart"><h2>{title}</h2>{rows.length === 0 ? <p className="empty-state">No activity in this period yet.</p> : rows.slice(-14).map((row) => <div className="chart-row" key={row.label}><span title={row.label}>{row.label}</span><div><i style={{ width: `${Math.max(row.value > 0 ? 4 : 0, row.value / max * 100)}%` }} /></div><b>{row.value}</b></div>)}</section>;
}
