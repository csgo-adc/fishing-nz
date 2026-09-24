"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";

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
type Dashboard = { analytics: Analytics; users: { users: User[] }; feedback: { feedback: Feedback[] } };

async function api<T>(path: string, method = "GET", data?: unknown): Promise<T> {
  const response = await fetch(`/api/cms${path}`, { method, headers: data ? { "content-type": "application/json" } : undefined, body: data ? JSON.stringify(data) : undefined, cache: "no-store" });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || "Could not load CMS data.");
  return payload as T;
}

export default function AdminPage() {
  const [token, setToken] = useState("");
  const [connected, setConnected] = useState(false);
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setDashboard(await api<Dashboard>("/dashboard")); setConnected(true); setError(""); }
    catch (cause) { setConnected(false); if (cause instanceof Error && !cause.message.startsWith("Sign in")) setError(cause.message); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function signIn(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { await api("/session", "POST", { token }); setToken(""); await load(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Could not sign in."); }
    finally { setBusy(false); }
  }

  async function signOut() {
    await api("/session", "DELETE"); setConnected(false); setDashboard(null);
  }

  async function setPlan(userId: string, plan: "free" | "paid") {
    setBusy(true); setError("");
    try {
      await api(`/users/${userId}/plan`, "PATCH", { plan });
      setDashboard((current) => current ? { ...current, users: { users: current.users.users.map((user) => user.id === userId ? { ...user, plan } : user) } } : current);
      await load();
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Could not update plan."); }
    finally { setBusy(false); }
  }

  return (
    <main className="cms-shell">
      <header className="site-header cms-header"><Link className="brand" href="/"><span className="brand-mark" aria-hidden="true">✓</span>CatchCheck NZ</Link><Link className="back-link" href="/account">Account</Link></header>
      <section className="cms-content">
        <div className="cms-heading"><div><p className="kicker">CatchCheck operations</p><h1>Account CMS</h1><p>User, plan, feedback, and feature activity for the last 30 days.</p></div>{connected && <button className="signout-button" onClick={signOut}>Sign out</button>}</div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {!connected && <form className="cms-login" onSubmit={signIn}><h2>Administrator sign in</h2><p>Use the account admin token configured on the CatchCheck Worker.</p><label>Admin token<input type="password" autoComplete="current-password" required value={token} onChange={(event) => setToken(event.target.value)} /></label><button className="button button-primary" disabled={busy}>{busy ? "Checking…" : "Open CMS"}</button></form>}
        {connected && dashboard && <>
          <div className="cms-stat-grid">
            <Stat title="Total users" value={dashboard.analytics.users.total_users} detail={`${dashboard.analytics.users.verified_users} confirmed`} />
            <Stat title="Waiting for email confirmation" value={dashboard.analytics.users.pending_users} detail="Must confirm to sign in" />
            <Stat title="Paid users" value={dashboard.analytics.plans.find((row) => row.plan === "paid")?.users || 0} detail="Fish identification access" />
            <Stat title="Active accounts · latest day" value={dashboard.analytics.events_by_day.at(-1)?.users || 0} detail={dashboard.analytics.events_by_day.at(-1)?.day || "No activity yet"} />
          </div>
          <div className="cms-chart-grid">
            <Chart title="New accounts by day" rows={dashboard.analytics.signups_by_day.map((row) => ({ label: row.day, value: row.users }))} />
            <Chart title="Feature usage · 30 days" rows={dashboard.analytics.feature_usage.map((row) => ({ label: `${row.feature} · ${row.platform}`, value: row.uses }))} />
            <Chart title="Daily active accounts" rows={dashboard.analytics.events_by_day.map((row) => ({ label: row.day, value: row.users }))} />
          </div>
          <section className="cms-card"><div className="cms-section-heading"><div><h2>Users</h2><p>Paid plan changes take effect immediately.</p></div><span>{dashboard.users.users.length} shown</span></div>
            <div className="cms-table-wrap"><table><thead><tr><th>Email</th><th>Confirmed</th><th>Joined</th><th>Plan</th></tr></thead><tbody>{dashboard.users.users.map((user) => <tr key={user.id}><td><b>{user.display_name || user.email}</b><small>{user.email}</small></td><td>{user.email_verified ? <span className="status-pill confirmed">Confirmed</span> : <span className="status-pill pending">Pending</span>}</td><td>{new Date(user.created_at).toLocaleDateString()}</td><td><select aria-label={`Plan for ${user.email}`} disabled={busy || !user.email_verified} value={user.plan} onChange={(event) => void setPlan(user.id, event.target.value as "free" | "paid")}><option value="free">Free</option><option value="paid">Paid</option></select></td></tr>)}</tbody></table></div>
          </section>
          <section className="cms-card"><div className="cms-section-heading"><div><h2>Feedback</h2><p>Recent messages from signed-in users.</p></div><span>{dashboard.feedback.feedback.length} shown</span></div>{dashboard.feedback.feedback.length === 0 ? <p className="empty-state">No feedback has arrived yet.</p> : <div className="cms-feedback-list">{dashboard.feedback.feedback.map((item) => <article key={item.id}><div><b>{item.category}</b><span>{item.email}</span><time>{new Date(item.created_at).toLocaleString()}</time></div><p>{item.message}</p>{item.rating && <small>Rating: {item.rating}/5</small>}</article>)}</div>}</section>
        </>}
      </section>
    </main>
  );
}

function Stat({ title, value, detail }: { title: string; value: number; detail: string }) {
  return <article className="cms-stat"><span>{title}</span><strong>{value.toLocaleString()}</strong><small>{detail}</small></article>;
}

function Chart({ title, rows }: { title: string; rows: Array<{ label: string; value: number }> }) {
  const max = Math.max(1, ...rows.map((row) => row.value));
  return <section className="cms-card cms-chart"><h2>{title}</h2>{rows.length === 0 ? <p className="empty-state">No activity in this period yet.</p> : rows.slice(-14).map((row) => <div className="chart-row" key={row.label}><span title={row.label}>{row.label}</span><div><i style={{ width: `${Math.max(row.value > 0 ? 4 : 0, row.value / max * 100)}%` }} /></div><b>{row.value}</b></div>)}</section>;
}
