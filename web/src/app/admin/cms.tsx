"use client";

import NextImage from "next/image";
import Link from "next/link";
import { FormEvent, useEffect, useRef, useState } from "react";
import styles from "./cms.module.css";

// Shared pieces for the Accounts and Usage pages.

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

export async function api<T>(path: string, method = "GET", data?: unknown): Promise<T> {
  const response = await fetch(`/api/cms${path}`, { method, headers: data ? { "content-type": "application/json" } : undefined, body: data ? JSON.stringify(data) : undefined, cache: "no-store" });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(typeof payload.error === "string" ? payload.error : "Could not load CMS data.", response.status);
  return payload as T;
}

/** Filters, pages and the open panel live in the query string so Refresh, Back and shared links keep your place. */
export function readParams(): URLSearchParams {
  return new URLSearchParams(window.location.search);
}

/** Sets or removes (null, undefined, "", 0) query values. Push adds a Back step; otherwise the current entry is replaced. */
export function writeParams(values: Record<string, string | number | null | undefined>, push = false) {
  const params = readParams();
  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined || value === "" || value === 0) params.delete(key);
    else params.set(key, String(value));
  }
  const query = params.toString();
  const next = `${window.location.pathname}${query ? `?${query}` : ""}`;
  if (next === `${window.location.pathname}${window.location.search}`) return;
  if (push) window.history.pushState(null, "", next);
  else window.history.replaceState(null, "", next);
}

export function readOffset(value: string | null): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 && parsed <= 1_000_000 ? parsed : 0;
}

const dateFormat = new Intl.DateTimeFormat("en-NZ", { day: "numeric", month: "short", year: "numeric" });
const dateTimeFormat = new Intl.DateTimeFormat("en-NZ", { day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
// The API groups by UTC day (YYYY-MM-DD), so those labels stay in UTC rather than shifting a day in New Zealand.
const dayFormat = new Intl.DateTimeFormat("en-NZ", { day: "numeric", month: "short", timeZone: "UTC" });
const DAY_MS = 86_400_000;

export const formatDate = (value: string) => dateFormat.format(new Date(value));
export const formatDateTime = (value: string) => dateTimeFormat.format(new Date(value));
export const formatDay = (day: string) => dayFormat.format(new Date(`${day}T00:00:00Z`));
export const number = (value: number | null | undefined) => (value ?? 0).toLocaleString("en-NZ");

export function relativeTime(value: string | number, now: number): string {
  const time = typeof value === "number" ? value : new Date(value).getTime();
  const minutes = Math.floor((now - time) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days === 1) return "yesterday";
  if (days < 7) return `${days} days ago`;
  return dateFormat.format(new Date(time));
}

/** The current time, refreshed every 30 seconds so "5 min ago" labels stay right. Null until mounted. */
export function useNow(): number | null {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    queueMicrotask(tick);
    const timer = window.setInterval(tick, 30_000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

/** A relative time ("2 h ago") with the exact date and time on hover. */
export function When({ value, now, fallback = "Never" }: { value: string | null | undefined; now: number | null; fallback?: string }) {
  if (!value) return <>{fallback}</>;
  const exact = formatDateTime(value);
  return <time dateTime={value} title={exact}>{now === null ? exact : relativeTime(value, now)}</time>;
}

type HeaderProps = {
  page: "accounts" | "usage";
  updatedAt?: number | null;
  now?: number | null;
  refreshing?: boolean;
  onRefresh?: () => void;
  onSignOut?: () => void;
};

export function AdminHeader({ page, updatedAt = null, now = null, refreshing = false, onRefresh, onSignOut }: HeaderProps) {
  return (
    <header className={styles.header}>
      <div className={styles.headerInner}>
        <Link className="brand" href="/admin"><NextImage className="brand-mark" src="/fishing-days-icon.png" width={36} height={36} alt="" unoptimized />Fishdays - NZ</Link>
        <nav className={styles.nav} aria-label="CMS pages">
          <Link href="/admin" aria-current={page === "accounts" ? "page" : undefined}>Accounts</Link>
          <Link href="/admin/analytics" aria-current={page === "usage" ? "page" : undefined}>Usage</Link>
        </nav>
        {(onRefresh || onSignOut) && <div className={styles.headerActions}>
          {onRefresh && <>
            <span className={styles.updated} role="status">{refreshing ? "Updating…" : updatedAt !== null && now !== null ? `Updated ${relativeTime(updatedAt, now)}` : ""}</span>
            <button type="button" onClick={onRefresh} disabled={refreshing}>Refresh</button>
          </>}
          {onSignOut && <button type="button" onClick={onSignOut}>Sign out</button>}
        </div>}
      </div>
    </header>
  );
}

export function Stat({ title, value, detail }: { title: string; value: number; detail: string }) {
  return <article className="cms-stat"><span>{title}</span><strong>{number(value)}</strong><small>{detail}</small></article>;
}

/** Ranked horizontal bars, biggest first, with the top rows shown and the rest one click away. */
export function BarList({ title, rows, limit = 10, className = "" }: { title: string; rows: Array<{ label: string; value: number }>; limit?: number; className?: string }) {
  const [expanded, setExpanded] = useState(false);
  const sorted = [...rows].sort((a, b) => b.value - a.value);
  const shown = expanded ? sorted : sorted.slice(0, limit);
  const max = Math.max(1, ...sorted.map((row) => row.value));
  return (
    <section className={`cms-card ${styles.chart} ${className}`}>
      <div className={styles.chartHead}>
        <h2>{title}</h2>
        {sorted.length > limit && <button type="button" className={styles.linkButton} onClick={() => setExpanded(!expanded)}>{expanded ? `Show top ${limit}` : `Show all ${sorted.length}`}</button>}
      </div>
      {sorted.length === 0 ? <p className="empty-state">No activity in this period yet.</p> : <ol className={styles.bars}>
        {shown.map((row, index) => <li key={`${row.label}-${index}`}>
          <span title={row.label}>{row.label}</span>
          <div><i style={{ width: `${row.value > 0 ? Math.max(2, row.value / max * 100) : 0}%` }} /></div>
          <b>{number(row.value)}</b>
        </li>)}
      </ol>}
    </section>
  );
}

/** One column per day across the whole period, ending today (UTC); days with no rows count as zero. */
export function DailyColumns({ title, rows, days, now, total = false, className = "" }: { title: string; rows: Array<{ day: string; value: number }>; days: number; now: number | null; total?: boolean; className?: string }) {
  const values = new Map(rows.map((row) => [row.day, row.value]));
  const end = now !== null ? new Date(now).toISOString().slice(0, 10) : rows.at(-1)?.day;
  const endTime = end ? Date.parse(`${end}T00:00:00Z`) : 0;
  const series = end ? Array.from({ length: days }, (_, index) => {
    const day = new Date(endTime - (days - 1 - index) * DAY_MS).toISOString().slice(0, 10);
    return { day, value: values.get(day) ?? 0 };
  }) : [];
  const sum = series.reduce((result, row) => result + row.value, 0);
  const peak = series.reduce((best, row) => row.value > best.value ? row : best, series[0] ?? { day: "", value: 0 });
  const last = series.at(-1);
  return (
    <section className={`cms-card ${styles.chart} ${className}`}>
      <div className={styles.chartHead}>
        <h2>{title}</h2>
        {sum > 0 && <span>{total ? `${number(sum)} total · ` : ""}Peak {number(peak.value)} on {formatDay(peak.day)}</span>}
      </div>
      {sum === 0 || !last ? <p className="empty-state">No activity in this period yet.</p> : <>
        <div className={styles.columns} role="img" aria-label={`${title}: peak ${peak.value} on ${formatDay(peak.day)}, ${last.value} on ${formatDay(last.day)}.`}>
          {series.map((row) => <span key={row.day} title={`${formatDay(row.day)}: ${number(row.value)}`}><i style={{ height: row.value > 0 ? `${Math.max(3, row.value / peak.value * 100)}%` : 0 }} /></span>)}
        </div>
        <div className={styles.columnAxis}><span>{formatDay(series[0].day)}</span><span>{formatDay(last.day)}</span></div>
      </>}
    </section>
  );
}

export function Pager({ total, limit, offset, disabled = false, onPage }: { total: number; limit: number; offset: number; disabled?: boolean; onPage: (offset: number) => void }) {
  if (total <= limit || limit <= 0) return null;
  const first = Math.min(offset + 1, total);
  const last = Math.min(offset + limit, total);
  return (
    <nav className={styles.pager} aria-label="List pages">
      <span>Showing {number(first)}–{number(last)} of {number(total)}</span>
      <div>
        <button type="button" disabled={disabled || offset === 0} onClick={() => onPage(Math.max(0, offset - limit))}>Previous</button>
        <button type="button" disabled={disabled || offset + limit >= total} onClick={() => onPage(offset + limit)}>Next</button>
      </div>
    </nav>
  );
}

type SearchProps = {
  id: string;
  label: string;
  placeholder: string;
  value: string;
  applied: string;
  status: string;
  onChange: (value: string) => void;
  onSearch: (value: string) => void;
};

/** Searches as you type (after a short pause), on Enter, or clears with × / Escape. "/" jumps here from anywhere on the page. */
export function SearchField({ id, label, placeholder, value, applied, status, onChange, onSearch }: SearchProps) {
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const text = value.trim();
    if (text === applied) return;
    const timer = window.setTimeout(() => onSearch(text), 350);
    return () => window.clearTimeout(timer);
  }, [value, applied, onSearch]);
  useEffect(() => {
    function focusSearch(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey || target?.closest("input, textarea, select, [contenteditable]")) return;
      event.preventDefault();
      input.current?.focus();
    }
    window.addEventListener("keydown", focusSearch);
    return () => window.removeEventListener("keydown", focusSearch);
  }, []);

  function clear() {
    onChange("");
    if (applied) onSearch("");
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    onSearch(value.trim());
  }
  return (
    <form className={styles.search} role="search" onSubmit={submit}>
      <label htmlFor={id}>{label}</label>
      <div>
        <input ref={input} id={id} type="search" maxLength={120} autoComplete="off" value={value} placeholder={placeholder} onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => { if (event.key === "Escape" && value) { event.preventDefault(); clear(); } }} />
        {value ? <button type="button" aria-label="Clear search" onClick={() => { clear(); input.current?.focus(); }}>×</button> : <kbd title="Press / to search">/</kbd>}
      </div>
      <p aria-live="polite">{status}</p>
    </form>
  );
}

export function CopyButton({ value, label = "Copy ID" }: { value: string; label?: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");
  useEffect(() => {
    if (state === "idle") return;
    const timer = window.setTimeout(() => setState("idle"), 1500);
    return () => window.clearTimeout(timer);
  }, [state]);
  return (
    <button type="button" className={styles.copy} title={value} onClick={() => { navigator.clipboard.writeText(value).then(() => setState("copied"), () => setState("failed")); }}>
      <span aria-live="polite">{state === "copied" ? "Copied" : state === "failed" ? "Couldn’t copy" : label}</span>
    </button>
  );
}

export function ErrorCount({ value }: { value: number | undefined }) {
  return value ? <span className={styles.errors}>{number(value)}</span> : <>0</>;
}

export const mono = styles.mono;
