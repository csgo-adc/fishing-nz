"use client";

import Link from "next/link";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import { AdminHeader, ApiError, BarList, DailyColumns, Pager, SearchField, Stat, When, api, formatDate, formatDay, number, readOffset, readParams, useNow, writeParams } from "./cms";
import styles from "./admin.module.css";

type Analytics = {
  range_days: number;
  users: { total_users: number; verified_users: number; pending_users: number };
  plans: Array<{ plan: string; users: number }>;
  signups_by_day: Array<{ day: string; users: number }>;
  feature_usage: Array<{ feature: string; platform: string; uses: number; users: number }>;
  events_by_day: Array<{ day: string; events: number; users: number }>;
};
type User = { id: string; email: string; display_name: string; country_code: string; plan: "free" | "paid"; email_verified: boolean; has_password?: boolean; created_at: string };
type Feedback = { id: string; user_id?: string; email: string; category: string; message: string; rating: number | null; created_at: string };
type Page<T> = { total: number; limit: number; offset: number } & T;
type UsersPage = Page<{ users: User[] }>;
type FeedbackPage = Page<{ feedback: Feedback[] }>;
type Dashboard = { analytics: Analytics; users: UsersPage; feedback: FeedbackPage };
type Tab = "users" | "feedback" | "charts";
type LoadOptions = { usersOffset: number; feedbackOffset: number; search: string; tab: Tab };

const TABS: Tab[] = ["users", "feedback", "charts"];
const CATEGORY_LABELS: Record<string, string> = { general: "General", bug: "Bug", idea: "Idea" };
// Newest feedback time this browser has shown, so the Feedback tab can say what arrived since the last visit.
const SEEN_KEY = "fishdays-cms-feedback-seen";
const regions = new Intl.DisplayNames(["en-NZ"], { type: "region" });

function regionName(code: string): string {
  try { return regions.of(code) || code; } catch { return code; }
}

function readSeen(): string | null {
  try { return window.localStorage.getItem(SEEN_KEY); } catch { return null; }
}

function writeSeen(value: string) {
  try { window.localStorage.setItem(SEEN_KEY, value); } catch { /* Storage blocked: the "new" badge just stays off. */ }
}

export default function AdminPage() {
  const now = useNow();
  const [token, setToken] = useState("");
  const [connected, setConnected] = useState(false);
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [listLoading, setListLoading] = useState(false);
  const [tab, setTab] = useState<Tab>("users");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [pendingPlan, setPendingPlan] = useState<{ userId: string; plan: "free" | "paid" } | null>(null);
  const [seenBefore, setSeenBefore] = useState<string | null>(null);
  const [seenAt, setSeenAt] = useState<string | null>(null);
  const usersRequest = useRef(0);

  const fail = useCallback((cause: unknown, fallback: string) => {
    if (cause instanceof ApiError && cause.status === 401) {
      setConnected(false); setDashboard(null); setPendingPlan(null);
      if (cause.message !== "Sign in to the CMS.") setError(cause.message);
    } else setError(cause instanceof Error ? cause.message : fallback);
  }, []);

  const load = useCallback(async ({ usersOffset, feedbackOffset, search: text, tab: openTab }: LoadOptions) => {
    const params = new URLSearchParams({ users_offset: String(usersOffset), feedback_offset: String(feedbackOffset) });
    if (text) params.set("search", text);
    setLoading(true);
    try {
      const next = await api<Dashboard>(`/dashboard?${params}`);
      setDashboard(next); setConnected(true); setError(""); setUpdatedAt(Date.now());
      const newest = next.feedback.offset === 0 ? next.feedback.feedback[0]?.created_at : undefined;
      if (newest) {
        // A first visit starts with nothing marked new.
        const baseline = readSeen() ?? newest;
        setSeenBefore((current) => current ?? baseline);
        if (openTab === "feedback" || baseline === newest) { writeSeen(newest); setSeenAt(newest); } else setSeenAt(baseline);
      }
    } catch (cause) { fail(cause, "Could not load CMS data."); }
    finally { setLoading(false); }
  }, [fail]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      const params = readParams();
      const openTab = TABS.find((name) => name === params.get("tab")) ?? "users";
      const text = (params.get("q") || "").trim().slice(0, 120);
      setTab(openTab); setSearchInput(text); setSearch(text);
      void load({ usersOffset: readOffset(params.get("users")), feedbackOffset: readOffset(params.get("feedback")), search: text, tab: openTab });
    });
    return () => { active = false; };
  }, [load]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 6000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const refresh = () => void load({ usersOffset: dashboard?.users.offset ?? 0, feedbackOffset: dashboard?.feedback.offset ?? 0, search, tab });

  const loadUsers = useCallback(async (offset: number, text: string) => {
    const request = ++usersRequest.current;
    const params = new URLSearchParams({ offset: String(offset) });
    if (text) params.set("search", text);
    setListLoading(true);
    try {
      const users = await api<UsersPage>(`/users?${params}`);
      if (request !== usersRequest.current) return;
      setDashboard((current) => current ? { ...current, users } : current); setError("");
      writeParams({ q: text, users: offset });
    } catch (cause) { if (request === usersRequest.current) fail(cause, "Could not load users."); }
    finally { if (request === usersRequest.current) setListLoading(false); }
  }, [fail]);

  const runSearch = useCallback((text: string) => {
    setSearch(text); setPendingPlan(null);
    void loadUsers(0, text);
  }, [loadUsers]);

  async function loadFeedback(offset: number) {
    setListLoading(true);
    try {
      const feedback = await api<FeedbackPage>(`/feedback?offset=${offset}`);
      setDashboard((current) => current ? { ...current, feedback } : current); setError("");
      writeParams({ feedback: offset });
      scrollToTabs();
    } catch (cause) { fail(cause, "Could not load feedback."); }
    finally { setListLoading(false); }
  }

  function chooseTab(next: Tab) {
    setTab(next); setPendingPlan(null);
    writeParams({ tab: next === "users" ? null : next });
    const newest = dashboard?.feedback.offset === 0 ? dashboard.feedback.feedback[0]?.created_at : undefined;
    if (next === "feedback" && newest) { writeSeen(newest); setSeenAt(newest); }
  }

  function moveTab(event: KeyboardEvent<HTMLDivElement>) {
    const step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = TABS[(TABS.indexOf(tab) + step + TABS.length) % TABS.length];
    chooseTab(next);
    document.getElementById(`cms-tab-${next}`)?.focus();
  }

  async function signIn(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { await api("/session", "POST", { token }); setToken(""); setConnected(true); await load({ usersOffset: 0, feedbackOffset: 0, search, tab }); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Could not sign in."); }
    finally { setBusy(false); }
  }

  async function signOut() {
    setBusy(true); setError("");
    try {
      await api("/session", "DELETE");
      setConnected(false); setDashboard(null); setSearchInput(""); setSearch(""); setPendingPlan(null);
      writeParams({ q: null, users: null, feedback: null });
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Could not sign out."); }
    finally { setBusy(false); }
  }

  async function confirmPlan() {
    if (!pendingPlan || !dashboard) return;
    const { userId, plan } = pendingPlan;
    const user = dashboard.users.users.find((row) => row.id === userId);
    setBusy(true); setError("");
    try {
      await api(`/users/${userId}/plan`, "PATCH", { plan });
      setPendingPlan(null);
      setDashboard((current) => current ? {
        ...current,
        users: { ...current.users, users: current.users.users.map((row) => row.id === userId ? { ...row, plan } : row) },
      } : current);
      setNotice(`${user?.display_name || user?.email || "The account"} ${plan === "paid" ? "now has paid access" : "is now on the free plan"}.`);
      void load({ usersOffset: dashboard.users.offset, feedbackOffset: dashboard.feedback.offset, search, tab });
    } catch (cause) { fail(cause, "Could not update plan."); }
    finally { setBusy(false); }
  }

  function scrollToTabs() {
    document.getElementById("cms-tabs")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  const users = dashboard?.users;
  const feedback = dashboard?.feedback;
  const newCount = feedback && feedback.offset === 0 && seenAt ? feedback.feedback.filter((item) => item.created_at > seenAt).length : 0;
  const latestDay = dashboard?.analytics.events_by_day.at(-1);
  const usersStatus = !users ? "" : listLoading && tab === "users" ? "Searching…" : search
    ? `${number(users.total)} ${users.total === 1 ? "user matches" : "users match"} “${search}”`
    : `${number(users.total)} users, newest first`;

  return (
    <main className="cms-shell">
      <AdminHeader page="accounts" updatedAt={updatedAt} now={now} refreshing={loading} onRefresh={connected ? refresh : undefined} onSignOut={connected ? () => void signOut() : undefined} />
      <section className="cms-content">
        <div className="cms-heading"><div><p className="kicker">Fishdays - NZ operations</p><h1>Account CMS</h1><p>User, plan, feedback, and feature activity for the last 30 days.</p></div></div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {loading && !connected && <div className="cms-login" role="status"><h2>Checking your session…</h2></div>}
        {!loading && !connected && <form className="cms-login" onSubmit={signIn}><h2>Administrator sign in</h2><p>Use the account admin token configured on the Fishdays - NZ Worker.</p><label>Admin token<input type="password" autoComplete="off" required value={token} onChange={(event) => setToken(event.target.value)} /></label><button className="button button-primary" disabled={busy}>{busy ? "Checking…" : "Open CMS"}</button></form>}
        {connected && !dashboard && <div className="cms-login" role="status"><h2>{loading ? "Loading dashboard…" : "Dashboard unavailable"}</h2>{!loading && <button className="button button-primary" onClick={refresh}>Try again</button>}</div>}
        {connected && dashboard && users && feedback && <>
          <div className="cms-stat-grid">
            <Stat title="Total users" value={dashboard.analytics.users.total_users} detail={`${number(dashboard.analytics.users.verified_users)} confirmed`} />
            <Stat title="Waiting for email confirmation" value={dashboard.analytics.users.pending_users} detail="Must confirm to sign in" />
            <Stat title="Paid users" value={dashboard.analytics.plans.find((row) => row.plan === "paid")?.users || 0} detail="Current account plans" />
            <Stat title="Active accounts · latest day" value={latestDay?.users || 0} detail={latestDay ? formatDay(latestDay.day) : "No activity yet"} />
          </div>

          <div className={styles.tabs} role="tablist" aria-label="Account sections" id="cms-tabs" onKeyDown={moveTab}>
            {TABS.map((name) => <button key={name} type="button" role="tab" id={`cms-tab-${name}`} aria-selected={tab === name} aria-controls="cms-tab-panel" tabIndex={tab === name ? 0 : -1} onClick={() => chooseTab(name)}>
              {name === "users" ? "Users" : name === "feedback" ? "Feedback" : "Charts"}
              {name === "users" && <span className={styles.count}>{number(dashboard.analytics.users.total_users)}</span>}
              {name === "feedback" && <span className={styles.count}>{number(feedback.total)}</span>}
              {name === "feedback" && newCount > 0 && <span className={styles.newBadge}>{newCount === feedback.feedback.length && feedback.total > newCount ? `${newCount}+` : newCount} new</span>}
            </button>)}
          </div>

          <div role="tabpanel" id="cms-tab-panel" aria-labelledby={`cms-tab-${tab}`}>
            {notice && <p className={`form-notice ${styles.notice}`} role="status">{notice}</p>}

            {tab === "users" && <section className="cms-card">
              <SearchField id="cms-user-search" label="Search by name or email" placeholder="Name or email" value={searchInput} applied={search} status={usersStatus} onChange={setSearchInput} onSearch={runSearch} />
              {users.users.length === 0 ? <p className="empty-state">{search ? "No users match that search. Check the spelling or search part of the email." : "No accounts have registered yet."}</p> :
                <div className={`cms-table-wrap ${listLoading ? styles.dim : ""}`} aria-busy={listLoading}><table><thead><tr><th scope="col">User</th><th scope="col">Country</th><th scope="col">Sign-in</th><th scope="col">Joined</th><th scope="col">Plan</th><th scope="col"><span className="sr-only">Activity</span></th></tr></thead><tbody>
                  {users.users.map((user) => <tr key={user.id}>
                    <td><b>{user.display_name || user.email}</b>{user.display_name && <small>{user.email}</small>}</td>
                    <td data-label="Country">{user.country_code ? <abbr title={regionName(user.country_code)}>{user.country_code}</abbr> : "—"}</td>
                    <td data-label="Sign-in">{user.has_password === undefined ? "—" : user.has_password ? "Email" : "Google / Apple"}</td>
                    <td data-label="Joined"><time dateTime={user.created_at}>{formatDate(user.created_at)}</time></td>
                    <td data-label="Plan">{pendingPlan?.userId === user.id ?
                      <div className={styles.confirm} role="group" aria-label={`Confirm plan for ${user.email}`}>
                        <span>{pendingPlan.plan === "paid" ? "Give paid access?" : "Move to the free plan?"}</span>
                        <button type="button" className={styles.confirmYes} autoFocus disabled={busy} onClick={() => void confirmPlan()}>{busy ? "Saving…" : "Yes"}</button>
                        <button type="button" disabled={busy} onClick={() => setPendingPlan(null)}>Cancel</button>
                      </div>
                      : !user.email_verified && user.plan === "free" ? <span className="status-pill pending" title="They must confirm their email before they can sign in or get paid access.">Confirm email first</span>
                      : <select className={user.plan === "paid" ? styles.paid : undefined} aria-label={`Plan for ${user.email}`} disabled={busy} value={user.plan} onChange={(event) => setPendingPlan({ userId: user.id, plan: event.target.value as "free" | "paid" })}><option value="free">Free</option><option value="paid">Paid</option></select>}
                    </td>
                    <td data-label="Usage"><Link className={styles.activity} href={`/admin/analytics?open=user:${user.id}`}>Activity →</Link></td>
                  </tr>)}
                </tbody></table></div>}
              <Pager total={users.total} limit={users.limit} offset={users.offset} disabled={busy || listLoading} onPage={(offset) => { setPendingPlan(null); void loadUsers(offset, search).then(scrollToTabs); }} />
            </section>}

            {tab === "feedback" && <section className="cms-card">
              {feedback.feedback.length === 0 ? <p className="empty-state">No feedback yet. Messages people send from the app’s feedback page show up here.</p> :
                <div className={`${styles.feedbackList} ${listLoading ? styles.dim : ""}`} aria-busy={listLoading}>
                  {feedback.feedback.map((item) => <article key={item.id}>
                    <div className={styles.feedbackMeta}>
                      <span className={styles.category} data-category={item.category}>{CATEGORY_LABELS[item.category] ?? item.category.charAt(0).toUpperCase() + item.category.slice(1)}</span>
                      {item.rating ? <span className={styles.stars} role="img" aria-label={`Rated ${item.rating} out of 5`}>{"★".repeat(item.rating)}<span>{"★".repeat(Math.max(0, 5 - item.rating))}</span></span> : null}
                      {seenBefore && item.created_at > seenBefore && <span className={styles.newBadge}>New</span>}
                      <When value={item.created_at} now={now} />
                    </div>
                    <p>{item.message}</p>
                    <div className={styles.feedbackActions}>
                      <span>{item.email}</span>
                      <a href={`mailto:${encodeURIComponent(item.email)}?subject=${encodeURIComponent("Your Fishdays - NZ feedback")}`}>Reply</a>
                      {item.user_id && <Link href={`/admin/analytics?open=user:${item.user_id}`}>Activity →</Link>}
                    </div>
                  </article>)}
                </div>}
              <Pager total={feedback.total} limit={feedback.limit} offset={feedback.offset} disabled={listLoading} onPage={(offset) => void loadFeedback(offset)} />
            </section>}

            {tab === "charts" && <div className="cms-chart-grid">
              <DailyColumns title="New accounts by day" rows={dashboard.analytics.signups_by_day.map((row) => ({ day: row.day, value: row.users }))} days={dashboard.analytics.range_days} now={now} total />
              <DailyColumns title="Daily active accounts" rows={dashboard.analytics.events_by_day.map((row) => ({ day: row.day, value: row.users }))} days={dashboard.analytics.range_days} now={now} />
              <BarList className={styles.wide} title={`Feature usage · ${dashboard.analytics.range_days} days`} rows={dashboard.analytics.feature_usage.map((row) => ({ label: `${row.feature} · ${row.platform}`, value: row.uses }))} />
            </div>}
          </div>
        </>}
      </section>
    </main>
  );
}
