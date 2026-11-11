"use client";

import Link from "next/link";
import { ChangeEvent, useCallback, useEffect, useRef, useState } from "react";
import { AdminHeader, ApiError, When, api, number, useNow } from "../cms";
import styles from "./rules.module.css";

type DatedChange = { date: string; text: string };
type Area = {
  area_id: string; area_name: string; official_url: string; reviewed_at: string | null; fetched_at: string | null;
  tables: number; rows: number; reasons: string[]; started_changes: DatedChange[]; upcoming_changes: DatedChange[];
  check: { status: string | null; last_attempt_at: string | null; http_status: number | null; error: string | null } | null;
};
type Reminders = { enabled: boolean; email: string; stale_days: number; last_sent_at: string | null };
type Overview = {
  areas: Area[]; reminders: Reminders; email_ready: boolean;
  history: Array<{ area_id: string; at: string; reviewed_at: string | null; changes: number }>;
};
type Change = { kind: "changed" | "added" | "removed"; table: string; species: string; before: string | null; after: string | null };
type Preview = {
  area_id: string; area_name: string; page_title: string; reviewed_at: string | null; sections: number; tables: number; rows: number;
  changes: Change[]; change_count: number; warnings: string[]; same_as_saved: boolean; upcoming_changes: DatedChange[];
};

// MPI's dates are calendar days (YYYY-MM-DD), so they are formatted in UTC and never shift a day in New Zealand.
const dayFormat = new Intl.DateTimeFormat("en-NZ", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const changeDay = (isoDay: string) => dayFormat.format(new Date(`${isoDay}T00:00:00Z`));

/** "23 Dec 2026 – The ban ends…", with dates that share one sentence listed together and long sentences cut short. */
function comingChanges(changes: DatedChange[]): string[] {
  const byText = new Map<string, string[]>();
  for (const change of changes) byText.set(change.text, [...(byText.get(change.text) ?? []), changeDay(change.date)]);
  return [...byText].map(([text, days]) => `${days.join(", ")} – ${text.length > 110 ? `${text.slice(0, 107).trimEnd()}…` : text}`);
}

export default function RulesPage() {
  const now = useNow();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [loading, setLoading] = useState(true);
  const [signedOut, setSignedOut] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [areaId, setAreaId] = useState<string | null>(null);
  const [html, setHtml] = useState("");
  const [fileName, setFileName] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState<"" | "preview" | "publish" | "reminders">("");
  const [pageError, setPageError] = useState("");
  const [reminders, setReminders] = useState<Reminders | null>(null);
  const panel = useRef<HTMLElement>(null);

  const fail = useCallback((cause: unknown, setter: (message: string) => void = setError) => {
    if (cause instanceof ApiError && cause.status === 401) setSignedOut(true);
    else setter(cause instanceof Error ? cause.message : "Something went wrong. Try again.");
  }, []);

  const apply = useCallback((next: Overview) => {
    setOverview(next); setReminders(next.reminders); setSignedOut(false); setUpdatedAt(Date.now());
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try { apply(await api<Overview>("/rules")); setError(""); }
    catch (cause) { fail(cause); }
    finally { setLoading(false); }
  }, [apply, fail]);

  useEffect(() => { queueMicrotask(() => void load()); }, [load]);

  function openUpdate(id: string) {
    setAreaId(id); setHtml(""); setFileName(""); setPreview(null); setPageError(""); setNotice("");
    window.setTimeout(() => panel.current?.scrollIntoView({ behavior: "smooth", block: "start" }), 50);
  }

  async function readFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (file.size > 4 * 1024 * 1024) { setPageError("That file is too large. Save the page as \"Webpage, HTML only\"."); return; }
    setHtml(await file.text()); setFileName(file.name); setPreview(null); setPageError("");
  }

  async function runPreview() {
    if (!areaId || !html.trim()) return;
    setBusy("preview"); setPageError(""); setPreview(null);
    try { setPreview(await api<Preview>("/rules/preview", "POST", { area_id: areaId, html })); }
    catch (cause) { fail(cause, setPageError); }
    finally { setBusy(""); }
  }

  async function publish() {
    if (!areaId || !preview) return;
    if (!window.confirm(`Publish these ${preview.area_name} rules to the apps? They replace the saved rules for this area.`)) return;
    setBusy("publish"); setPageError("");
    try {
      apply(await api<Overview>("/rules/publish", "POST", { area_id: areaId, html }));
      setNotice(`${preview.area_name} rules published. The apps download them on their next sync.`);
      setAreaId(null); setHtml(""); setPreview(null);
    } catch (cause) { fail(cause, setPageError); }
    finally { setBusy(""); }
  }

  async function saveReminders() {
    if (!reminders) return;
    setBusy("reminders"); setError(""); setNotice("");
    try { apply(await api<Overview>("/rules/reminders", "PUT", { enabled: reminders.enabled, email: reminders.email, stale_days: reminders.stale_days })); setNotice("Reminder settings saved."); }
    catch (cause) { fail(cause); }
    finally { setBusy(""); }
  }

  async function signOut() {
    try { await api("/session", "DELETE"); } catch { /* The session cookie may already be gone. */ }
    setSignedOut(true);
  }

  const area = overview?.areas.find((row) => row.area_id === areaId) ?? null;
  const needChecking = overview?.areas.filter((row) => row.reasons.length > 0).length ?? 0;
  const lastCheck = overview?.areas.map((row) => row.check).filter((check) => check?.last_attempt_at).sort((a, b) => (b!.last_attempt_at! > a!.last_attempt_at! ? 1 : -1))[0];
  const remindersChanged = overview && reminders && JSON.stringify([reminders.enabled, reminders.email, reminders.stale_days]) !== JSON.stringify([overview.reminders.enabled, overview.reminders.email, overview.reminders.stale_days]);

  return (
    <main className="cms-shell">
      <AdminHeader page="rules" updatedAt={updatedAt} now={now} refreshing={loading} onRefresh={signedOut ? undefined : () => void load()} onSignOut={signedOut ? undefined : () => void signOut()} />
      <section className="cms-content">
        <div className="cms-heading">
          <div><p className="kicker">Fishdays - NZ operations</p><h1>Fishing rules</h1><p>Keep each area&apos;s MPI rules current. MPI&apos;s website blocks automatic downloads, so you save an area&apos;s page in your browser and upload it here. The apps pick up published rules on their next sync, with no app update.</p></div>
        </div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {notice && <p className={styles.notice} role="status">{notice}</p>}
        {signedOut && <div className="cms-login" role="status"><h2>Sign in to the CMS</h2><p>Your administrator session has ended or has not started.</p><Link className="button button-primary" href="/admin">Go to administrator sign in</Link></div>}
        {!signedOut && loading && !overview && <div className="cms-login" role="status"><h2>Loading…</h2></div>}
        {!signedOut && overview && <>
          <section className="cms-card">
            <div className="cms-section-heading">
              <div><h2>Areas</h2><p>{needChecking === 0 ? "Every area looks up to date." : `${needChecking} of ${overview.areas.length} areas may need updating.`}</p></div>
            </div>
            <div className="cms-table-wrap">
              <table>
                <thead><tr><th>Area</th><th>MPI reviewed</th><th>Saved</th><th>Status</th><th /></tr></thead>
                <tbody>{overview.areas.map((row) => <tr key={row.area_id} className={row.area_id === areaId ? styles.selected : undefined}>
                  <td><b>{row.area_name}</b><small><a href={row.official_url} target="_blank" rel="noreferrer">MPI page ↗</a> · {number(row.rows)} rule rows</small></td>
                  <td>{row.reviewed_at ?? "—"}</td>
                  <td><When value={row.fetched_at} now={now} fallback="Never" /></td>
                  <td>
                    {row.reasons.length === 0 ? <span className={styles.ok}>Up to date</span> : <>
                      <span className={styles.check}>Check</span>
                      <ul className={styles.reasons}>{row.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
                    </>}
                    {row.upcoming_changes.length > 0 && <ul className={styles.upcoming} aria-label="Dates still to come on MPI's page">{comingChanges(row.upcoming_changes).map((line) => <li key={line}>Coming: {line}</li>)}</ul>}
                  </td>
                  <td><button type="button" className={styles.rowButton} onClick={() => openUpdate(row.area_id)}>Update</button></td>
                </tr>)}</tbody>
              </table>
            </div>
            {lastCheck && <p className={styles.footnote}>The daily automatic check of MPI&apos;s pages last ran <When value={lastCheck.last_attempt_at} now={now} />{lastCheck.error ? `: ${lastCheck.error}` : "."} It can only notice changes; new rules always come from a page you upload.</p>}
          </section>

          {area && <section className="cms-card" ref={panel} aria-labelledby="update-title">
            <div className="cms-section-heading">
              <div><h2 id="update-title">Update {area.area_name}</h2><p>Nothing changes for the apps until you publish.</p></div>
              <button type="button" className={styles.linkButton} onClick={() => { setAreaId(null); setPreview(null); }}>Close</button>
            </div>
            <ol className={styles.steps}>
              <li>
                <b>Open the MPI page</b>
                <p><a className={styles.stepButton} href={area.official_url} target="_blank" rel="noreferrer">Open {area.area_name} rules on mpi.govt.nz ↗</a></p>
              </li>
              <li>
                <b>Save the page</b>
                <p>Chrome or Edge: press Ctrl+S (⌘S on a Mac) and choose <i>Webpage, HTML only</i>. Safari: File → Save As, format <i>Page Source</i>. Or open the page source (Ctrl+U or ⌥⌘U), select all and copy.</p>
              </li>
              <li>
                <b>Upload it here</b>
                <div className={styles.upload}>
                  <label className={styles.stepButton}>Choose saved page<input type="file" accept=".html,.htm,text/html" onChange={(event) => void readFile(event)} /></label>
                  {fileName && <span>{fileName} · {number(Math.round(html.length / 1024))} KB</span>}
                </div>
                <textarea className={styles.paste} placeholder="…or paste the page source here" value={fileName ? "" : html} disabled={Boolean(fileName)} spellCheck={false}
                  onChange={(event) => { setHtml(event.target.value); setPreview(null); setPageError(""); }} rows={4} />
                {fileName && <button type="button" className={styles.linkButton} onClick={() => { setFileName(""); setHtml(""); setPreview(null); }}>Paste instead</button>}
              </li>
              <li>
                <b>Check what changed</b>
                <p><button type="button" className={styles.stepButton} disabled={!html.trim() || busy !== ""} onClick={() => void runPreview()}>{busy === "preview" ? "Reading the page…" : "Preview changes"}</button></p>
                {pageError && <p className={styles.bad} role="alert">{pageError}</p>}
                {preview && <PreviewResult preview={preview} savedReviewed={area.reviewed_at} />}
              </li>
            </ol>
            {preview && <div className={styles.publishRow}>
              <button type="button" className={styles.primaryButton} disabled={busy !== ""} onClick={() => void publish()}>{busy === "publish" ? "Publishing…" : `Publish ${area.area_name} rules`}</button>
              <span>{preview.same_as_saved ? "Same rules as saved; publishing just marks them checked today." : `${number(preview.change_count)} rule row${preview.change_count === 1 ? "" : "s"} will change.`}</span>
            </div>}
          </section>}

          {reminders && <section className="cms-card">
            <div className="cms-section-heading"><div><h2>Reminder emails</h2><p>Early each morning the API checks every area and emails you when one may be out of date: saved more than the days below ago, or a change in MPI&apos;s own &ldquo;recent rule changes&rdquo; list has started since it was saved. The same reminder is repeated at most once a week.</p></div></div>
            <form className={styles.reminders} onSubmit={(event) => { event.preventDefault(); void saveReminders(); }}>
              <label className={styles.toggle}><input type="checkbox" checked={reminders.enabled} onChange={(event) => setReminders({ ...reminders, enabled: event.target.checked })} />Send reminders</label>
              <label>Email<input type="email" value={reminders.email} disabled={!reminders.enabled} onChange={(event) => setReminders({ ...reminders, email: event.target.value })} /></label>
              <label>Remind after (days)<input type="number" min={7} max={365} value={reminders.stale_days} onChange={(event) => setReminders({ ...reminders, stale_days: Number(event.target.value) })} /></label>
              <button type="submit" className={styles.primaryButton} disabled={!remindersChanged || busy !== ""}>{busy === "reminders" ? "Saving…" : "Save"}</button>
            </form>
            <p className={styles.footnote}>Last reminder: <When value={overview.reminders.last_sent_at} now={now} fallback="none sent yet" />.{!overview.email_ready && " Email isn't set up on the API (RESEND_API_KEY and ACCOUNT_EMAIL_FROM), so reminders can't be sent."}</p>
          </section>}

          <section className="cms-card">
            <div className="cms-section-heading"><div><h2>Recent updates</h2></div></div>
            {overview.history.length === 0 ? <p className="empty-state">No rules have been published from the CMS yet.</p> : <div className="cms-table-wrap"><table>
              <thead><tr><th>Area</th><th>Published</th><th>MPI reviewed</th><th>Rows changed</th></tr></thead>
              <tbody>{overview.history.map((entry) => <tr key={`${entry.area_id}-${entry.at}`}>
                <td>{overview.areas.find((row) => row.area_id === entry.area_id)?.area_name ?? entry.area_id}</td>
                <td><When value={entry.at} now={now} /></td><td>{entry.reviewed_at ?? "—"}</td><td>{number(entry.changes)}</td>
              </tr>)}</tbody>
            </table></div>}
          </section>
        </>}
      </section>
    </main>
  );
}

function PreviewResult({ preview, savedReviewed }: { preview: Preview; savedReviewed: string | null }) {
  const label = { changed: "Changed", added: "New", removed: "Removed" };
  return (
    <div className={styles.preview}>
      <p><b>{preview.page_title}</b></p>
      <p>MPI reviewed {preview.reviewed_at ?? "(date not found)"}{savedReviewed ? ` · saved copy reviewed ${savedReviewed}` : ""} · {number(preview.tables)} tables, {number(preview.rows)} rule rows</p>
      {preview.warnings.map((warning) => <p key={warning} className={styles.warn}>{warning}</p>)}
      {preview.same_as_saved ? <p className={styles.ok}>No rule changes: this page matches the saved rules.</p> : preview.changes.length === 0
        ? <p>The rule rows are the same; only the review date or wording changed.</p>
        : <div className="cms-table-wrap"><table className={styles.changes}>
          <thead><tr><th>Species</th><th>Change</th><th>Before</th><th>After</th></tr></thead>
          <tbody>{preview.changes.map((change, index) => <tr key={`${change.table}-${change.species}-${index}`}>
            <td><b>{change.species}</b><small>{change.table}</small></td>
            <td><span className={styles[change.kind]}>{label[change.kind]}</span></td>
            <td>{change.before ?? "—"}</td><td>{change.after ?? "—"}</td>
          </tr>)}</tbody>
        </table>{preview.change_count > preview.changes.length && <p className={styles.footnote}>Showing {number(preview.changes.length)} of {number(preview.change_count)} changes.</p>}</div>}
      {preview.upcoming_changes.length > 0 && <><p className={styles.upcomingTitle}>Dates still to come on this page (you&apos;ll get a reminder once they pass):</p>
        <ul className={styles.upcoming}>{comingChanges(preview.upcoming_changes).map((line) => <li key={line}>{line}</li>)}</ul></>}
    </div>
  );
}
