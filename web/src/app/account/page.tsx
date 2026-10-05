"use client";

import NextImage from "next/image";
import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

type User = { id: string; email: string; display_name: string; country_code: string; plan: "free" | "paid"; created_at: string };
type FeedbackCategory = "general" | "bug" | "idea";
type FishRule = {
  species: string; dailyLimit: string | null; minimumSize: string | null;
  minimumSizeLabel?: string | null; details?: Array<{ label: string; value: string }>;
};
type FishResult = {
  isFish?: boolean; commonName: string; scientificName: string; confidence: number;
  otherPossibilities?: string[]; visibleClues?: string; identificationNote?: string;
  areaName: string; rulesSourceUrl?: string | null; fishRules: FishRule[];
};
const supportedFishPhotoTypes = new Set(["image/jpeg", "image/png", "image/webp"]);

function formatAiText(value: string) {
  const text = value.replace(/\r\n?/g, "\n")
    .replace(/(^|\n)\s*#{1,6}\s+/g, "$1")
    .replace(/(^|\n)\s*[-*]\s+/g, "$1• ");
  return text.split(/(\*\*[^*]+?\*\*)/g).map((part, index) =>
    part.startsWith("**") && part.endsWith("**")
      ? <strong key={index}>{part.slice(2, -2)}</strong>
      : part.replace(/\*/g, "")
  );
}

// MPI uses suffix symbols for footnotes; keep separators and arithmetic signs intact.
function ruleField(value: string): { text: string; hasFootnote: boolean } {
  let hasFootnote = false;
  let text = value.replace(/([\p{L})])(?:\*+|†|‡|\+|\^|#)+(?=$|[\s,.;:!?()–—-])/gu, (match) => {
    hasFootnote = true; return match[0];
  }).replace(/(\d)(?:\*+|†|‡|\^|#)+(?=$|[\s,.;:!?()–—-])/g, (match) => {
    hasFootnote = true; return match[0];
  }).replace(/\s+•(?=\s*$)/g, () => {
    hasFootnote = true; return "";
  });
  if (text.includes("*")) { hasFootnote = true; text = text.replace(/\*+/g, ""); }
  return { text: text.replace(/\s+/g, " ").trim(), hasFootnote };
}

function ruleText(value: string): string {
  return ruleField(value).text;
}

function officialMpiUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && (url.hostname === "mpi.govt.nz" || url.hostname.endsWith(".mpi.govt.nz")) ? url.href : null;
  } catch { return null; }
}

function isHeicPhoto(file: File): boolean {
  return ["image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence"].includes(file.type.toLowerCase()) || /\.hei(?:c|f)$/i.test(file.name);
}

async function prepareFishPhoto(file: File): Promise<Blob> {
  if (supportedFishPhotoTypes.has(file.type.toLowerCase())) return file;
  if (!isHeicPhoto(file)) throw new Error("Choose a JPEG, PNG, WebP, or HEIC photo.");
  return new Promise<Blob>((resolve, reject) => {
    const image = new Image();
    const objectUrl = URL.createObjectURL(file);
    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      const scale = Math.min(1, 2048 / Math.max(image.naturalWidth, image.naturalHeight));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
      canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
      const context = canvas.getContext("2d");
      if (!context) { reject(new Error("Could not prepare this photo. Export it as JPEG and try again.")); return; }
      context.drawImage(image, 0, 0, canvas.width, canvas.height);
      canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error("Could not prepare this photo. Export it as JPEG and try again.")), "image/jpeg", 0.88);
    };
    image.onerror = () => { URL.revokeObjectURL(objectUrl); reject(new Error("Could not open this HEIC photo. Export it as JPEG and try again.")); };
    image.src = objectUrl;
  });
}

class AccountApiError extends Error {
  constructor(message: string, readonly status: number, readonly code?: string) { super(message); }
}

async function api<T>(path: string, method = "GET", data?: unknown): Promise<T> {
  const response = await fetch(`/api/account${path}`, {
    method,
    headers: data ? { "content-type": "application/json" } : undefined,
    body: data ? JSON.stringify(data) : undefined,
    cache: "no-store",
  });
  const payload = await response.json().catch(() => ({})) as { error?: string; code?: string };
  if (!response.ok) throw new AccountApiError(payload.error || "Something went wrong. Please try again.", response.status, payload.code);
  return payload as T;
}

export default function AccountPage() {
  const [user, setUser] = useState<User | null>(null);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [countryCode, setCountryCode] = useState("NZ");
  const [category, setCategory] = useState<FeedbackCategory>("general");
  const [message, setMessage] = useState("");
  const [rating, setRating] = useState(5);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [fishPhoto, setFishPhoto] = useState<File | null>(null);
  const [fishResult, setFishResult] = useState<FishResult | null>(null);
  const [fishBusy, setFishBusy] = useState(false);
  const [verificationPending, setVerificationPending] = useState(false);
  const [accountLoading, setAccountLoading] = useState(true);
  const authVersion = useRef(0);
  const initialRefreshStarted = useRef(false);

  const refresh = useCallback(async () => {
    const version = authVersion.current;
    try {
      const payload = await api<{ user: User }>("/me");
      if (version !== authVersion.current) return;
      setUser(payload.user);
      setDisplayName(payload.user.display_name);
      setCountryCode(payload.user.country_code);
      void api("/analytics/events", "POST", { event_name: "app_opened", platform: "web" }).catch(() => undefined);
      void api("/analytics/events", "POST", { event_name: "feature_used", feature: "account", platform: "web" }).catch(() => undefined);
    } catch (cause) {
      if (version !== authVersion.current) return;
      setUser(null);
      if (!(cause instanceof AccountApiError && cause.status === 401)) setError("Could not check your account. Check your connection and try again.");
    } finally {
      if (version === authVersion.current) setAccountLoading(false);
    }
  }, []);

  useEffect(() => {
    if (initialRefreshStarted.current) return;
    initialRefreshStarted.current = true;
    void Promise.resolve().then(refresh);
  }, [refresh]);

  async function submitAuth(event: FormEvent) {
    event.preventDefault(); authVersion.current += 1; setAccountLoading(false); setBusy(true); setError(""); setNotice("");
    try {
      const payload = await api<{ user?: User; message?: string }>(`/auth/${mode}`, "POST", {
        email: email.trim(), password, ...(mode === "register" ? { display_name: displayName.trim() } : {}),
      });
      setPassword("");
      if (mode === "login") {
        if (!payload.user) throw new Error("Could not load your account. Please try again.");
        setUser(payload.user);
        setDisplayName(payload.user.display_name);
        setCountryCode(payload.user.country_code);
        setEmail(payload.user.email);
        setVerificationPending(false);
        setNotice("You’re signed in.");
      } else {
        setVerificationPending(true);
        setMode("login");
        setNotice(payload.message || "Check your email to confirm your account before signing in.");
      }
    } catch (cause) {
      if (cause instanceof AccountApiError && (cause.code === "email_not_verified" || cause.code === "email_delivery_failed")) setVerificationPending(true);
      setError(cause instanceof Error ? cause.message : "Could not sign in.");
    }
    finally { setBusy(false); }
  }

  async function resendVerification() {
    setBusy(true); setError(""); setNotice("");
    try { const payload = await api<{ message: string }>("/auth/resend-verification", "POST", { email: email.trim() }); setVerificationPending(true); setNotice(payload.message); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Could not send the confirmation email."); }
    finally { setBusy(false); }
  }

  async function saveProfile(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try {
      const payload = await api<{ user: User }>("/me", "PATCH", { display_name: displayName, country_code: countryCode });
      setUser(payload.user); setNotice("Profile saved.");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Could not save profile."); }
    finally { setBusy(false); }
  }

  async function submitFeedback(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try {
      await api("/feedback", "POST", { category, message, rating });
      setMessage(""); setNotice("Thanks for your feedback.");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Could not send feedback."); }
    finally { setBusy(false); }
  }

  async function signOut() {
    authVersion.current += 1; setBusy(true); setError(""); setNotice("");
    try {
      await api("/auth/logout", "POST", {});
      setUser(null); setVerificationPending(false); setFishResult(null); setFishPhoto(null); setNotice("You’re signed out.");
    } catch (cause) {
      if (cause instanceof AccountApiError && cause.status === 401) {
        setUser(null); setVerificationPending(false); setFishResult(null); setFishPhoto(null); setNotice("You’re signed out.");
      } else setError(cause instanceof Error ? cause.message : "Could not sign out.");
    }
    finally { setBusy(false); }
  }

  async function identifyFish(event: FormEvent) {
    event.preventDefault();
    if (!fishPhoto) return;
    setFishBusy(true); setError(""); setFishResult(null);
    try {
      const prepared = await prepareFishPhoto(fishPhoto);
      const response = await fetch("/api/account/fish/identify", { method: "POST", headers: { "content-type": prepared.type }, body: prepared });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.error || "Fish identification failed.");
      setFishResult(payload as FishResult);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Fish identification failed."); }
    finally { setFishBusy(false); }
  }

  return (
    <main className="account-shell">
      <header className="site-header account-header"><Link className="brand" href="/"><NextImage className="brand-mark" src="/fishing-days-icon.png" width={36} height={36} alt="" unoptimized />Fishing Days NZ</Link><Link className="back-link" href="/">Back to home</Link></header>
      <section className="account-panel">
        <p className="kicker">Your Fishing Days account</p>
        <h1>{user ? `Kia ora${user.display_name ? `, ${user.display_name}` : ""}` : "Sign in or create an account"}</h1>
        <p className="account-lead">Save your details, send feedback, and identify fish from a photo.</p>
        {notice && <p className="form-notice" role="status">{notice}</p>}
        {error && <p className="form-error" role="alert">{error}</p>}
        {!accountLoading && !user && error.startsWith("Could not check your account") && <button type="button" className="signout-button" onClick={() => { setError(""); setAccountLoading(true); void refresh(); }}>Retry account check</button>}
        {accountLoading ? <p role="status">Checking your account…</p> : !user ? (
          <>
            <div className="auth-switch" role="group" aria-label="Account action"><button type="button" aria-pressed={mode === "register"} className={mode === "register" ? "selected" : ""} onClick={() => { setMode("register"); setError(""); }}>Create account</button><button type="button" aria-pressed={mode === "login"} className={mode === "login" ? "selected" : ""} onClick={() => { setMode("login"); setError(""); }}>Sign in</button></div>
            {verificationPending && <p className="form-notice">Confirm your email using the link in your inbox, then sign in here. The link expires after 24 hours.</p>}
            <form className="account-form" onSubmit={submitAuth}>
              {mode === "register" && <label>Name (optional)<input autoComplete="name" maxLength={80} value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label>}
              <label>Email<input type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} /></label>
              <label>Password<input type="password" autoComplete={mode === "register" ? "new-password" : "current-password"} minLength={mode === "register" ? 10 : undefined} maxLength={128} required value={password} onChange={(event) => setPassword(event.target.value)} />{mode === "register" && <small>Use at least 10 characters.</small>}</label>
              <button className="button button-primary" disabled={busy}>{busy ? "Please wait…" : mode === "register" ? "Create account" : "Sign in"}</button>
              {(verificationPending || mode === "login") && <button type="button" className="signout-button" onClick={resendVerification} disabled={busy || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())}>Resend confirmation email</button>}
            </form>
          </>
        ) : (
          <>
            <div className="plan-card"><div><span>Account</span><strong>Signed in</strong></div><p>Fish identification and your fishing tools are ready.</p></div>
            <form className="account-form fish-form" onSubmit={identifyFish}>
              <h2>Identify a fish</h2><p>Choose a clear photo. The result is an AI suggestion; confirm the species and local rules before keeping a fish.</p>
              <label>Fish photo<input type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif,.heic,.heif" onChange={(event) => { const file = event.target.files?.[0] || null; const problem = file && file.size > 20 * 1024 * 1024 ? "Choose an image smaller than 20 MB." : file && !supportedFishPhotoTypes.has(file.type.toLowerCase()) && !isHeicPhoto(file) ? "Choose a JPEG, PNG, WebP, or HEIC photo." : ""; setFishPhoto(problem ? null : file); setFishResult(null); setError(problem); }} /></label>
              <button className="button button-primary" disabled={fishBusy || !fishPhoto}>{fishBusy ? "Checking photo…" : "Identify fish"}</button>
              {fishResult && <FishResultDisplay result={fishResult} />}
            </form>
            <form className="account-form" onSubmit={saveProfile}>
              <h2>Profile</h2><label>Email<input value={user.email} readOnly /></label><label>Name<input autoComplete="name" maxLength={80} value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label><label>Country code<input maxLength={2} value={countryCode} onChange={(event) => setCountryCode(event.target.value.toUpperCase().slice(0, 2))} /></label><button className="button button-primary" disabled={busy}>Save profile</button>
            </form>
            <form className="account-form feedback-form" onSubmit={submitFeedback}>
              <h2>Send feedback</h2><label>Type<select value={category} onChange={(event) => setCategory(event.target.value as FeedbackCategory)}><option value="general">General</option><option value="idea">Idea</option><option value="bug">Report a problem</option></select></label><label>Message<textarea required minLength={3} maxLength={4000} rows={5} value={message} onChange={(event) => setMessage(event.target.value)} /></label><label>Rating<select value={rating} onChange={(event) => setRating(Number(event.target.value))}>{[5,4,3,2,1].map((value) => <option key={value} value={value}>{value} out of 5</option>)}</select></label><button className="button button-primary" disabled={busy}>Send feedback</button>
            </form>
            <button className="signout-button" onClick={signOut} disabled={busy}>Sign out</button>
          </>
        )}
      </section>
    </main>
  );
}

function FishResultDisplay({ result }: { result: FishResult }) {
  const sourceUrl = officialMpiUrl(result.rulesSourceUrl);
  return <section className="plan-card fish-result" role="status" aria-label="Fish identification result">
    <h3>{formatAiText(result.commonName)}</h3>
    <p>{result.isFish !== false ? <>{Math.round(result.confidence * 100)}% AI confidence · {ruleText(result.areaName)}</> : "This is not a fish."}</p>
    {result.scientificName && <p>{formatAiText(result.scientificName)}</p>}
    {result.visibleClues && <p><b>Visible clues:</b> {formatAiText(result.visibleClues)}</p>}
    {(result.otherPossibilities?.length ?? 0) > 0 && <p><b>Could also be:</b> {formatAiText(result.otherPossibilities?.join(", ") || "")}</p>}
    {result.identificationNote && <p>{formatAiText(result.identificationNote)}</p>}
    {result.isFish !== false && result.fishRules.length > 0 && <div className="fish-rules">
      <h4>Saved MPI rules</h4>
      {result.fishRules.map((rule, index) => {
        const fields = [rule.species, rule.minimumSizeLabel, rule.minimumSize, rule.dailyLimit,
          ...(rule.details || []).flatMap((detail) => [detail.label, detail.value])];
        const hasFootnote = fields.some((field) => field && ruleField(field).hasFootnote);
        return <div className="fish-rule" key={`${rule.species}-${index}`}>
          <h5>{ruleText(rule.species)}</h5>
          <dl>
            {rule.minimumSize && <div><dt>{ruleText(rule.minimumSizeLabel || "Minimum size")}</dt><dd>{ruleText(rule.minimumSize)}</dd></div>}
            {rule.dailyLimit && <div><dt>Daily limit</dt><dd>{ruleText(rule.dailyLimit)}</dd></div>}
            {rule.details?.map((detail, detailIndex) => <div key={`${detail.label}-${detailIndex}`}><dt>{ruleText(detail.label)}</dt><dd>{ruleText(detail.value)}</dd></div>)}
          </dl>
          {hasFootnote && <p className="fish-rule-footnote">MPI footnote applies. {sourceUrl ? <a href={sourceUrl} target="_blank" rel="noopener noreferrer">Read the official rule and footnote ↗</a> : "Check the official MPI rules for details."}</p>}
        </div>;
      })}
      {sourceUrl && <a className="fish-rule-source" href={sourceUrl} target="_blank" rel="noopener noreferrer">Check current MPI rules ↗</a>}
    </div>}
  </section>;
}
