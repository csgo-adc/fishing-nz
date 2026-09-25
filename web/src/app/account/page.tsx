"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

type User = { id: string; email: string; display_name: string; country_code: string; plan: "free" | "paid"; created_at: string };
type FeedbackCategory = "general" | "bug" | "idea";
type FishResult = { commonName: string; scientificName: string; confidence: number; areaName: string; fishRules: Array<{ species: string; dailyLimit: string | null; minimumSize: string | null }> };

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
      const response = await fetch("/api/account/fish/identify", { method: "POST", headers: { "content-type": fishPhoto.type || "image/jpeg" }, body: fishPhoto });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.error || "Fish identification failed.");
      setFishResult(payload as FishResult);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Fish identification failed."); }
    finally { setFishBusy(false); }
  }

  return (
    <main className="account-shell">
      <header className="site-header account-header"><Link className="brand" href="/"><span className="brand-mark" aria-hidden="true">✓</span>CatchCheck NZ</Link><Link className="back-link" href="/">Back to home</Link></header>
      <section className="account-panel">
        <p className="kicker">Your CatchCheck account</p>
        <h1>{user ? `Kia ora${user.display_name ? `, ${user.display_name}` : ""}` : "Sign in or create an account"}</h1>
        <p className="account-lead">Save your basic details, send feedback, and see which features your plan includes.</p>
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
            <div className="plan-card"><div><span>Your plan</span><strong>{user.plan === "paid" ? "Paid" : "Free"}</strong></div><p>{user.plan === "paid" ? "Fish identification is included." : "Basic fishing tools are available. Paid access is currently enabled by the CatchCheck team."}</p></div>
            {user.plan === "paid" && <form className="account-form fish-form" onSubmit={identifyFish}>
              <h2>Identify a fish</h2><p>Choose a clear photo. The result is an AI suggestion; confirm the species and local rules before keeping a fish.</p>
              <label>Fish photo<input type="file" accept="image/*" onChange={(event) => { const file = event.target.files?.[0] || null; const problem = file && file.size > 20 * 1024 * 1024 ? "Choose an image smaller than 20 MB." : file && file.type && !file.type.startsWith("image/") ? "Choose an image file." : ""; setFishPhoto(problem ? null : file); setFishResult(null); setError(problem); }} /></label>
              <button className="button button-primary" disabled={fishBusy || !fishPhoto}>{fishBusy ? "Checking photo…" : "Identify fish"}</button>
              {fishResult && <div className="plan-card"><strong>{fishResult.commonName}</strong><p>{fishResult.scientificName} · {Math.round(fishResult.confidence * 100)}% confidence · {fishResult.areaName}</p>{fishResult.fishRules.map((rule, index) => <p key={`${rule.species}-${index}`}><b>{rule.species}</b>{rule.minimumSize ? ` · Minimum size: ${rule.minimumSize}` : ""}{rule.dailyLimit ? ` · Daily limit: ${rule.dailyLimit}` : ""}</p>)}</div>}
            </form>}
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
