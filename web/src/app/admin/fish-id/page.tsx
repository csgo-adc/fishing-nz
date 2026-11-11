"use client";

import Link from "next/link";
import { ChangeEvent, useCallback, useEffect, useState } from "react";
import { AdminHeader, ApiError, DailyColumns, When, api, number, useNow } from "../cms";
import styles from "./fish-id.module.css";

type ProviderId = "openai" | "anthropic" | "gemini" | "openai_compatible";
type Slot = { provider: ProviderId; model: string; base_url: string };
type FailureKind = "auth" | "quota" | "model" | "request" | "server" | "network" | "response" | "not_configured";
type Status = {
  model: string; last_success_at: string | null; last_latency_ms: number | null; last_error_at: string | null;
  last_error: string | null; last_error_kind: FailureKind | null; successes: number; failures: number;
};
type Provider = {
  id: ProviderId; label: string; default_model: string; needs_base_url: boolean; secret_name: string | null;
  key: { source: "cms" | "secret" | null; last4: string | null; updated_at: string | null; problem: string | null };
  status: Status | null;
};
type Overview = {
  config: { primary: Slot; backup: Slot | null };
  providers: Provider[];
  usage: Array<{ day: string; calls: number; errors: number }>;
  daily_limit: number;
  key_encryption: string | null;
};
type TestResult = { ok: true; provider: ProviderId; model: string; latency_ms: number; result: { is_fish: boolean; common_name_nz: string; subject_name?: string; confidence: number; other_possibilities: string[] } }
  | { ok: false; provider: ProviderId; model: string; error: { kind: FailureKind; status: number | null; message: string } };

/** What each failure means, in words the person fixing it can act on. */
const FIXES: Record<FailureKind, string> = {
  auth: "The provider refused the API key. It may be wrong or revoked, or the account may not have access to this model. Create a new key in the provider's dashboard and save it below.",
  quota: "The provider's rate or billing limit was reached. Add credit or raise the limit in the provider's dashboard, or set a backup provider.",
  model: "The provider doesn't know this model name, or the key can't use it. Check the name in the provider's model list.",
  request: "The provider rejected the request. The model may not accept photos or JSON answers; try another model.",
  server: "The provider had an error on its side. It usually passes; a backup provider keeps identification working meanwhile.",
  network: "The provider didn't answer in time or couldn't be reached.",
  response: "The model answered, but not with a usable identification. Try another model.",
  not_configured: "This provider has no API key or model yet.",
};

const healthOf = (status: Status | null): "working" | "failing" | "unknown" => {
  if (!status || (!status.last_success_at && !status.last_error_at)) return "unknown";
  if (!status.last_error_at) return "working";
  if (!status.last_success_at) return "failing";
  return status.last_success_at > status.last_error_at ? "working" : "failing";
};

export default function FishIdPage() {
  const now = useNow();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [primary, setPrimary] = useState<Slot | null>(null);
  const [backup, setBackup] = useState<Slot | null>(null);
  const [keyDrafts, setKeyDrafts] = useState<Partial<Record<ProviderId, string>>>({});
  const [tests, setTests] = useState<Partial<Record<"primary" | "backup", TestResult | { running: true } | { failed: string }>>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [signedOut, setSignedOut] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);

  const apply = useCallback((next: Overview, resetForm: boolean) => {
    setOverview(next);
    if (resetForm) { setPrimary(next.config.primary); setBackup(next.config.backup); }
    setSignedOut(false); setUpdatedAt(Date.now());
  }, []);

  const fail = useCallback((cause: unknown) => {
    if (cause instanceof ApiError && cause.status === 401) setSignedOut(true);
    else setError(cause instanceof Error ? cause.message : "Could not load the fish identification settings.");
  }, []);

  const load = useCallback(async (resetForm = true) => {
    setLoading(true);
    try { apply(await api<Overview>("/fish-ai"), resetForm); setError(""); }
    catch (cause) { fail(cause); }
    finally { setLoading(false); }
  }, [apply, fail]);

  useEffect(() => { queueMicrotask(() => void load()); }, [load]);

  const providerById = (id: ProviderId) => overview?.providers.find((row) => row.id === id);
  const changed = overview !== null && JSON.stringify({ primary, backup }) !== JSON.stringify(overview.config);

  async function save() {
    if (!primary) return;
    setSaving(true); setNotice(""); setError("");
    try { apply(await api<Overview>("/fish-ai", "PUT", { primary, backup }), true); setNotice("Saved. New identifications use this setup now."); }
    catch (cause) { fail(cause); }
    finally { setSaving(false); }
  }

  async function saveKey(id: ProviderId) {
    const key = keyDrafts[id]?.trim();
    if (!key) return;
    setSaving(true); setNotice(""); setError("");
    try {
      apply(await api<Overview>(`/fish-ai/keys/${id}`, "PUT", { key }), false);
      setKeyDrafts((drafts) => ({ ...drafts, [id]: "" }));
      setNotice(`${providerById(id)?.label ?? "Provider"} key saved. Run a test to check it.`);
    } catch (cause) { fail(cause); }
    finally { setSaving(false); }
  }

  async function removeKey(id: ProviderId) {
    const label = providerById(id)?.label ?? "this provider";
    if (!window.confirm(`Remove the saved ${label} key? Identification with ${label} stops unless a Worker secret holds a key.`)) return;
    setSaving(true); setNotice(""); setError("");
    try { apply(await api<Overview>(`/fish-ai/keys/${id}`, "DELETE"), false); setNotice(`${label} key removed.`); }
    catch (cause) { fail(cause); }
    finally { setSaving(false); }
  }

  /** Tests the slot as it is in the form (saved or not) with the sample picture or a chosen photo. */
  async function runTest(which: "primary" | "backup", file?: File) {
    const slot = which === "primary" ? primary : backup;
    if (!slot) return;
    setTests((current) => ({ ...current, [which]: { running: true } }));
    const params = new URLSearchParams({ provider: slot.provider, model: slot.model, base_url: slot.base_url });
    if (!file) params.set("sample", "1");
    try {
      const response = await fetch(`/api/cms/fish-ai/test?${params}`, { method: "POST", body: file, headers: file ? { "content-type": file.type } : undefined, cache: "no-store" });
      const payload = await response.json().catch(() => ({}));
      if (response.status === 401) { setSignedOut(true); return; }
      if (!response.ok) throw new Error(typeof payload.error === "string" ? payload.error : "The test could not run.");
      setTests((current) => ({ ...current, [which]: payload as TestResult }));
      void load(false);
    } catch (cause) {
      setTests((current) => ({ ...current, [which]: { failed: cause instanceof Error ? cause.message : "The test could not run." } }));
    }
  }

  async function signOut() {
    try { await api("/session", "DELETE"); } catch { /* The session cookie may already be gone. */ }
    setSignedOut(true);
  }

  const main = overview ? providerById(overview.config.primary.provider) : undefined;
  const mainHealth = healthOf(main?.status ?? null);
  const usageCalls = overview?.usage.map((row) => ({ day: row.day, value: row.calls })) ?? [];
  const usageErrors = overview?.usage.reduce((sum, row) => sum + row.errors, 0) ?? 0;

  return (
    <main className="cms-shell">
      <AdminHeader page="fish-id" updatedAt={updatedAt} now={now} refreshing={loading} onRefresh={signedOut ? undefined : () => void load()} onSignOut={signedOut ? undefined : () => void signOut()} />
      <section className="cms-content">
        <div className="cms-heading">
          <div><p className="kicker">Fishdays - NZ operations</p><h1>Fish identification</h1><p>Choose the AI that names fish in photos, keep its API keys and see whether it is working. Changes apply to the apps straight away, with no app update.</p></div>
        </div>
        {error && <p className="form-error" role="alert">{error}</p>}
        {notice && <p className={styles.notice} role="status">{notice}</p>}
        {signedOut && <div className="cms-login" role="status"><h2>Sign in to the CMS</h2><p>Your administrator session has ended or has not started.</p><Link className="button button-primary" href="/admin">Go to administrator sign in</Link></div>}
        {!signedOut && loading && !overview && <div className="cms-login" role="status"><h2>Loading…</h2></div>}
        {!signedOut && overview && primary && <>
          <section className={`cms-card ${styles.health} ${styles[mainHealth]}`} aria-live="polite">
            <div>
              <span className={styles.dot} aria-hidden="true" />
              <h2>{mainHealth === "working" ? "Working" : mainHealth === "failing" ? "Not working" : "Not tried yet"}</h2>
              <p>{main?.label} · {overview.config.primary.model}{overview.config.backup ? ` · backup ${providerById(overview.config.backup.provider)?.label} (${overview.config.backup.model})` : " · no backup"}</p>
            </div>
            {main?.status && <dl>
              <div><dt>Last worked</dt><dd><When value={main.status.last_success_at} now={now} /></dd></div>
              <div><dt>Last failed</dt><dd><When value={main.status.last_error_at} now={now} /></dd></div>
              <div><dt>Results</dt><dd>{number(main.status.successes)} ok · {number(main.status.failures)} failed</dd></div>
            </dl>}
            {mainHealth === "failing" && main?.status?.last_error && <div className={styles.why}>
              <p><b>Why:</b> {main.status.last_error}</p>
              {main.status.last_error_kind && <p><b>What to do:</b> {FIXES[main.status.last_error_kind]}</p>}
            </div>}
          </section>

          <section className="cms-card">
            <div className="cms-section-heading"><div><h2>Providers</h2><p>The main provider answers every photo. If it fails, the backup is tried before the app shows an error.</p></div></div>
            <div className={styles.slots}>
              <SlotEditor title="Main provider" slot={primary} providers={overview.providers} onChange={setPrimary}
                test={tests.primary} onTest={(file) => void runTest("primary", file)} />
              {backup
                ? <SlotEditor title="Backup provider" slot={backup} providers={overview.providers} onChange={setBackup} onRemove={() => setBackup(null)}
                  test={tests.backup} onTest={(file) => void runTest("backup", file)} />
                : <div className={styles.addBackup}>
                  <h3>Backup provider</h3>
                  <p>None. Add one so identification keeps working when the main provider is down or out of credit.</p>
                  <button type="button" onClick={() => {
                    const other = overview.providers.find((row) => row.id !== primary.provider && row.default_model && row.key.source) ?? overview.providers.find((row) => row.id !== primary.provider && row.default_model);
                    if (other) setBackup({ provider: other.id, model: other.default_model, base_url: "" });
                  }}>Add a backup</button>
                </div>}
            </div>
            {[primary, backup].some((slot) => slot && slot.provider !== "openai") && <p className={styles.privacy} role="note">
              <b>Check the privacy texts first.</b> The photo consent screens in the apps and the privacy policy say photos go to OpenAI.
              Before {primary.provider !== "openai" ? "making" : "adding"} {[primary, backup].filter((slot) => slot && slot.provider !== "openai").map((slot) => providerById(slot!.provider)?.label).join(" or ")} {primary.provider !== "openai" ? "the main provider" : "as a backup"}, update those texts and the App Store and Google Play privacy answers to name it.
            </p>}
            <div className={styles.actions}>
              <button type="button" className={styles.primaryButton} disabled={!changed || saving} onClick={() => void save()}>{saving ? "Saving…" : "Save providers"}</button>
              {changed && <button type="button" onClick={() => { setPrimary(overview.config.primary); setBackup(overview.config.backup); }}>Undo changes</button>}
              <span>Test runs the form as it is, before you save. Tests don&apos;t count toward anyone&apos;s daily {overview.daily_limit}.</span>
            </div>
          </section>

          <section className="cms-card">
            <div className="cms-section-heading"><div><h2>API keys</h2><p>A saved key is encrypted and never shown again, only its last four characters. Without one, the Worker secret is used.</p></div></div>
            <div className="cms-table-wrap">
              <table>
                <thead><tr><th>Provider</th><th>Key in use</th><th>Last result</th><th>New key</th></tr></thead>
                <tbody>{overview.providers.map((row) => <tr key={row.id}>
                  <td><b>{row.label}</b><small>{row.secret_name ? `Worker secret: ${row.secret_name}` : "Key saved here only"}</small></td>
                  <td>
                    {row.key.source === "cms" && <><b>Saved here ••••{row.key.last4}</b><small>Added <When value={row.key.updated_at} now={now} /></small></>}
                    {row.key.source === "secret" && <><b>Worker secret ••••{row.key.last4}</b><small>{row.secret_name}</small></>}
                    {!row.key.source && <span className={styles.muted}>None</span>}
                    {row.key.problem && <small className={styles.problem}>{row.key.problem}</small>}
                  </td>
                  <td>{row.status ? <>
                    <b>{healthOf(row.status) === "working" ? "Worked" : "Failed"} <When value={healthOf(row.status) === "working" ? row.status.last_success_at : row.status.last_error_at} now={now} /></b>
                    <small>{row.status.model}{healthOf(row.status) === "failing" && row.status.last_error ? ` · ${row.status.last_error}` : row.status.last_latency_ms ? ` · ${(row.status.last_latency_ms / 1000).toFixed(1)} s` : ""}</small>
                  </> : <span className={styles.muted}>Not used yet</span>}</td>
                  <td>
                    <form className={styles.keyForm} onSubmit={(event) => { event.preventDefault(); void saveKey(row.id); }}>
                      <input type="password" autoComplete="off" spellCheck={false} placeholder="Paste API key" aria-label={`New ${row.label} API key`}
                        value={keyDrafts[row.id] ?? ""} onChange={(event) => setKeyDrafts((drafts) => ({ ...drafts, [row.id]: event.target.value }))} />
                      <button type="submit" disabled={saving || !(keyDrafts[row.id] ?? "").trim()}>Save</button>
                      {row.key.source === "cms" && <button type="button" disabled={saving} onClick={() => void removeKey(row.id)}>Remove</button>}
                    </form>
                  </td>
                </tr>)}</tbody>
              </table>
            </div>
            {overview.key_encryption === "ACCOUNT_ADMIN_TOKEN" && <p className={styles.footnote}>Keys are encrypted with a key made from ACCOUNT_ADMIN_TOKEN. If you ever change that token, enter the keys again, or set a SETTINGS_ENCRYPTION_KEY Worker secret before saving keys.</p>}
            {!overview.key_encryption && <p className={styles.problem}>Keys can&apos;t be saved until the Worker has a SETTINGS_ENCRYPTION_KEY or ACCOUNT_ADMIN_TOKEN secret.</p>}
          </section>

          <DailyColumns title={`Identifications, last 14 days${usageErrors ? ` (${number(usageErrors)} failed)` : ""}`} rows={usageCalls} days={14} now={now} total />
        </>}
      </section>
    </main>
  );
}

type SlotProps = {
  title: string; slot: Slot; providers: Provider[]; onChange: (slot: Slot) => void; onRemove?: () => void;
  test?: TestResult | { running: true } | { failed: string }; onTest: (file?: File) => void;
};

function SlotEditor({ title, slot, providers, onChange, onRemove, test, onTest }: SlotProps) {
  const info = providers.find((row) => row.id === slot.provider);
  const id = title.toLowerCase().replace(/\W+/g, "-");
  function choosePhoto(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (file) onTest(file);
  }
  return (
    <div className={styles.slot}>
      <div className={styles.slotHead}><h3>{title}</h3>{onRemove && <button type="button" className={styles.linkButton} onClick={onRemove}>Remove backup</button>}</div>
      <label htmlFor={`${id}-provider`}>Provider</label>
      <select id={`${id}-provider`} value={slot.provider} onChange={(event) => {
        const next = providers.find((row) => row.id === event.target.value);
        if (next) onChange({ provider: next.id, model: next.default_model, base_url: "" });
      }}>
        {providers.map((row) => <option key={row.id} value={row.id}>{row.label}{row.key.source ? "" : " (no key)"}</option>)}
      </select>
      <label htmlFor={`${id}-model`}>Model</label>
      <input id={`${id}-model`} value={slot.model} spellCheck={false} autoComplete="off" placeholder={info?.default_model || "vendor/model-name"}
        onChange={(event) => onChange({ ...slot, model: event.target.value.trim() })} />
      {info?.default_model && slot.model !== info.default_model && <button type="button" className={styles.linkButton} onClick={() => onChange({ ...slot, model: info.default_model })}>Use {info.default_model}</button>}
      {info?.needs_base_url && <>
        <label htmlFor={`${id}-base`}>Base URL</label>
        <input id={`${id}-base`} value={slot.base_url} spellCheck={false} autoComplete="off" placeholder="https://openrouter.ai/api/v1"
          onChange={(event) => onChange({ ...slot, base_url: event.target.value.trim() })} />
        <small>Any service with OpenAI&apos;s chat completions format, such as OpenRouter.</small>
      </>}
      {!info?.key.source && <p className={styles.problem}>No API key for {info?.label}. Add one under API keys.</p>}
      <div className={styles.testRow}>
        <button type="button" disabled={test !== undefined && "running" in test} onClick={() => onTest()}>Test with sample picture</button>
        <label className={styles.fileButton}>Test with a photo<input type="file" accept="image/jpeg,image/png,image/webp" onChange={choosePhoto} /></label>
      </div>
      {test && <TestOutcome test={test} />}
    </div>
  );
}

function TestOutcome({ test }: { test: TestResult | { running: true } | { failed: string } }) {
  if ("running" in test) return <p className={styles.testResult} role="status">Testing…</p>;
  if ("failed" in test) return <p className={`${styles.testResult} ${styles.bad}`} role="alert">{test.failed}</p>;
  if (!test.ok) return <div className={`${styles.testResult} ${styles.bad}`} role="alert">
    <b>Failed{test.error.status ? ` (HTTP ${test.error.status})` : ""}</b><p>{test.error.message}</p><p>{FIXES[test.error.kind]}</p>
  </div>;
  const name = test.result.is_fish ? test.result.common_name_nz : `${test.result.subject_name || "Not a fish"} (not a fish)`;
  return <div className={`${styles.testResult} ${styles.good}`} role="status">
    <b>Worked in {(test.latency_ms / 1000).toFixed(1)} s</b>
    <p>{name} · {Math.round(test.result.confidence * 100)}% sure{test.result.other_possibilities.length ? ` · or ${test.result.other_possibilities.join(", ")}` : ""}</p>
  </div>;
}
