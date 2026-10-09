import { handleSocialAuth, type SocialAuthEnv } from "./social-auth";
import { handlePrivacyRoute, deleteAccountData, cleanupAccountData } from "./privacy";
import { handleShareRoute } from "./share";
import { identificationDay, identificationQuota, reserveIdentification, releaseIdentification } from "./identification-quota";
import { changePassword, handlePasswordRoute } from "./password-reset";
import { HOUR, MINUTE, allowAttempt, clientAddress, isRateLimited, rateKey, recordAttempt, tooManyRequests } from "./rate-limit";
import { constantTimeEqual, hashPassword, passwordMatches, randomHex, sha256 } from "./security";
import packageInfo from "../package.json";
import { corsHeaders, json, readJson, readLimit, readOffset } from "./http";
import { deleteDeviceAnalytics, ingestAnalytics } from "./analytics";
import { handleAdminAnalytics } from "./admin-analytics";
import { recordApiUsage } from "./usage";

export interface Env extends SocialAuthEnv {
  OPENAI_API_KEY: string;
  RULES_DB: D1Database;
  RULES_INGEST_TOKEN: string;
  ACCOUNT_ADMIN_TOKEN: string;
  RESEND_API_KEY?: string;
  ACCOUNT_EMAIL_FROM?: string;
  PUBLIC_DEVELOPER_NAME?: string;
  PUBLIC_SUPPORT_EMAIL?: string;
  // Shared-window page and app links. Store pages stay "#" until the apps are published; see docs/share-fishing-window.md.
  IOS_DOWNLOAD_URL?: string;
  ANDROID_DOWNLOAD_URL?: string;
  APPLE_TEAM_ID?: string;
  ANDROID_CERT_SHA256?: string;
}

export type Account = {
  id: string;
  email: string;
  display_name: string;
  country_code: string;
  plan: "free" | "paid";
  created_at: string;
  email_verified: number;
  // 1 when the account has a password it can sign in with; 0 for a Google or Apple account that never set one.
  has_password: number;
};

type AccountWithCredentials = Account & { password_hash: string; password_salt: string };
type AuthContext = { account: Account; sessionToken: string };
type PublicAccount = Omit<Account, "email_verified" | "has_password"> & { email_verified: boolean; has_password: boolean };
const SESSION_LIFETIME_DAYS = 30;
const LOGIN_WINDOW = 15 * MINUTE;
// Filled by authenticate(), read after the response to attribute the call to an account.
const authenticatedUsers = new WeakMap<Request, string>();
// Requests whose usage must not be recorded, such as the one that just erased an account and its analytics.
const usageNotRecorded = new WeakSet<Request>();

const rulesAreas = new Map([
  ["auckland-kermadec", "Auckland / Kermadec"], ["central", "Central"],
  ["challenger", "Challenger"], ["south-east", "South-East"],
  ["southland", "Southland"], ["kaikoura", "Kaikōura"],
  ["chatham-rise", "Chatham Rise"], ["fiordland", "Fiordland"],
]);
const rulesSlugs: Record<string, string> = {
  "auckland-kermadec": "auckland-kermadec-fishing-rules",
  central: "central-fishing-rules",
  challenger: "challenger-fishing-rules",
  "south-east": "south-east-fishing-rules",
  southland: "southland-fishing-rules",
  kaikoura: "kaikoura-fishing-rules",
  "chatham-rise": "chatham-rise-area-recreational-fishing-rules",
  fiordland: "fiordland-marine-area-fishing-rules",
};

async function register(request: Request, env: Env): Promise<Response> {
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  const email = typeof payload.email === "string" ? payload.email.trim().toLowerCase() : "";
  const password = typeof payload.password === "string" ? payload.password : "";
  const displayName = typeof payload.display_name === "string" ? payload.display_name.trim() : "";
  if (!isValidEmail(email)) return json({ error: "Enter a valid email address." }, 400);
  if (password.length < 8 || password.length > 128) return json({ error: "Password must be between 8 and 128 characters." }, 400);
  if (displayName.length > 80) return json({ error: "Display name must be 80 characters or fewer." }, 400);

  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("register-ip", clientAddress(request)), limit: 10 }, HOUR)) {
    return tooManyRequests("Too many sign-up attempts from this network. Try again later.", HOUR, corsHeaders());
  }

  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  const salt = randomHex(16);
  const passwordHash = await hashPassword(password, salt);
  try {
    await env.RULES_DB.prepare(
      `INSERT INTO account_users (id, email, password_hash, password_salt, display_name, country_code, plan, created_at, updated_at, email_verified)
       VALUES (?, ?, ?, ?, ?, 'NZ', 'free', ?, ?, 0)`
    ).bind(id, email, passwordHash, salt, displayName, now, now).run();
  } catch {
    const existing = await env.RULES_DB.prepare("SELECT id, email, email_verified FROM account_users WHERE email = ? COLLATE NOCASE")
      .bind(email).first<{ id: string; email: string; email_verified: number }>().catch(() => null);
    if (!existing) return json({ error: "Account registration is temporarily unavailable." }, 503);
    if (existing.email_verified === 1) return json({ message: "If this email can be registered, a confirmation link will be sent." }, 202);
    if (!await issueVerificationEmail(request, env, existing.id, existing.email)) {
      return json({ error: "We could not send the confirmation email. Please try again shortly.", code: "email_delivery_failed" }, 503);
    }
    return json({ message: "Check your email for a link to confirm your account." }, 202);
  }
  if (!await issueVerificationEmail(request, env, id, email)) {
    return json({ error: "Your account is saved, but we could not send the confirmation email. Please try sending it again shortly.", code: "email_delivery_failed" }, 503);
  }
  return json({ message: "Check your email for a link to confirm your account." }, 202);
}

async function login(request: Request, env: Env): Promise<Response> {
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  const email = typeof payload.email === "string" ? payload.email.trim().toLowerCase() : "";
  const password = typeof payload.password === "string" ? payload.password : "";
  // Failed sign-ins count against the network, the network-and-email pair, and the email alone.
  // Only failures count, so a signed-in user is never slowed down by their own successful logins.
  const address = clientAddress(request);
  const attempts = [
    { key: await rateKey("login-ip", address), limit: 30 },
    { key: await rateKey("login-account", address, email), limit: 5 },
    { key: await rateKey("login-email", email), limit: 20 },
  ];
  if (await isRateLimited(env.RULES_DB, attempts, LOGIN_WINDOW)) {
    return tooManyRequests("Too many sign-in attempts. Try again in a few minutes.", LOGIN_WINDOW, corsHeaders());
  }
  const row = await env.RULES_DB.prepare(
    `SELECT id, email, display_name, country_code, plan, created_at, password_hash, password_salt, email_verified
     FROM account_users WHERE email = ? COLLATE NOCASE`
  ).bind(email).first<AccountWithCredentials>();
  // Always hash, even when the account is missing, so response time does not reveal which emails exist.
  const passwordCorrect = await passwordMatches(password, row);
  if (!row || !passwordCorrect) {
    await recordAttempt(env.RULES_DB, attempts.map((rule) => rule.key), LOGIN_WINDOW);
    return json({ error: "Email or password is incorrect." }, 401);
  }
  if (row.email_verified !== 1) return json({ error: "Confirm your email before signing in.", code: "email_not_verified" }, 403);
  const { password_hash: passwordHash, password_salt: _passwordSalt, ...account } = row;
  return await createSessionResponse(env, { ...account, has_password: passwordHash ? 1 : 0 }, 200);
}

async function resendVerification(request: Request, env: Env): Promise<Response> {
  const payload = await readJson(request);
  if (!payload || typeof payload.email !== "string" || !isValidEmail(payload.email.trim())) return json({ error: "Enter a valid email address." }, 400);
  const email = payload.email.trim().toLowerCase();
  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("resend-ip", clientAddress(request)), limit: 10 }, HOUR)) {
    return tooManyRequests("Too many requests from this network. Try again later.", HOUR, corsHeaders());
  }
  const row = await env.RULES_DB.prepare("SELECT id, email, email_verified FROM account_users WHERE email = ? COLLATE NOCASE")
    .bind(email).first<{ id: string; email: string; email_verified: number }>();
  if (row && row.email_verified !== 1 && !await issueVerificationEmail(request, env, row.id, row.email)) {
    return json({ error: "We could not send the confirmation email. Please try again shortly." }, 503);
  }
  return json({ message: "If an unconfirmed account uses that email, a confirmation link will be sent." }, 202);
}

async function verificationPage(request: Request): Promise<Response> {
  const token = new URL(request.url).searchParams.get("token") || "";
  if (!/^[a-f0-9]{64}$/i.test(token)) return new Response(verificationHtml("This confirmation link is invalid or expired.", false), { status: 400, headers: htmlHeaders() });
  return new Response(verificationFormHtml(token), { headers: htmlHeaders() });
}

async function verifyEmail(request: Request, env: Env): Promise<Response> {
  let token = "";
  const contentType = request.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const payload = await readJson(request);
    token = typeof payload?.token === "string" ? payload.token : "";
  } else {
    const form = await request.formData().catch(() => null);
    token = String(form?.get("token") || "");
  }
  if (!/^[a-f0-9]{64}$/i.test(token)) return json({ error: "This confirmation link is invalid or expired." }, 400);
  const now = new Date().toISOString();
  const row = await env.RULES_DB.prepare(
    `SELECT u.id, u.email, u.display_name, u.country_code, u.plan, u.created_at, u.email_verified
     FROM account_email_verifications v JOIN account_users u ON u.id = v.user_id
     WHERE v.token_hash = ? AND v.expires_at > ?`
  ).bind(await sha256(token.toLowerCase()), now).first<Account>();
  if (!row) return json({ error: "This confirmation link is invalid or expired. Request a new one from the app." }, 400);
  await env.RULES_DB.batch([
    env.RULES_DB.prepare("UPDATE account_users SET email_verified = 1, updated_at = ? WHERE id = ?").bind(now, row.id),
    env.RULES_DB.prepare("DELETE FROM account_email_verifications WHERE user_id = ?").bind(row.id),
  ]);
  await recordAccountEvent(env, row.id, "email_verified", null, "web");
  if (contentType.includes("application/json")) return json({ verified: true, message: "Your email is confirmed. Sign in to continue." });
  return new Response(verificationHtml("Your email is confirmed. Return to Fishdays - NZ and sign in to continue.", true), { headers: htmlHeaders() });
}

async function issueVerificationEmail(request: Request, env: Env, userId: string, email: string): Promise<boolean> {
  if (!env.RESEND_API_KEY || !env.ACCOUNT_EMAIL_FROM) return false;
  const recent = await env.RULES_DB.prepare(
    "SELECT created_at FROM account_email_verifications WHERE user_id = ? AND created_at > ? LIMIT 1"
  ).bind(userId, new Date(Date.now() - 60_000).toISOString()).first<{ created_at: string }>();
  if (recent) return true;
  const token = randomHex(32);
  const now = new Date();
  const expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
  const tokenHash = await sha256(token);
  await env.RULES_DB.prepare("INSERT INTO account_email_verifications (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
    .bind(tokenHash, userId, now.toISOString(), expiresAt).run();
  const verifyUrl = `${new URL(request.url).origin}/v1/auth/verify-email?token=${token}`;
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      from: env.ACCOUNT_EMAIL_FROM,
      to: [email],
      subject: "Confirm your Fishdays - NZ account",
      html: `<div style="font-family:Arial,sans-serif;max-width:560px;margin:auto;color:#15323d"><h1 style="color:#082c3c">Confirm your email</h1><p>Tap the button below to activate your Fishdays - NZ account. This link expires in 24 hours.</p><p><a href="${verifyUrl}" style="background:#087f8c;color:white;padding:13px 18px;border-radius:8px;text-decoration:none;display:inline-block">Confirm email</a></p><p>If you didn’t create a Fishdays - NZ account, you can ignore this email.</p></div>`,
      text: `Confirm your Fishdays - NZ account: ${verifyUrl}\nThis link expires in 24 hours.`,
    }),
  }).catch(() => null);
  if (!response?.ok) {
    await env.RULES_DB.prepare("DELETE FROM account_email_verifications WHERE token_hash = ?").bind(tokenHash).run();
    return false;
  }
  await env.RULES_DB.prepare("DELETE FROM account_email_verifications WHERE user_id = ? AND token_hash != ?")
    .bind(userId, tokenHash).run().catch(() => undefined);
  return true;
}

function verificationFormHtml(token: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Confirm Fishdays - NZ account</title></head><body style="font-family:Arial,sans-serif;background:#f4f7f5;color:#15323d;margin:0;padding:48px 18px"><main style="background:white;border-radius:14px;margin:auto;max-width:520px;padding:32px"><h1 style="color:#082c3c">Confirm your Fishdays - NZ email</h1><p>Press the button to activate your account. The link expires after 24 hours.</p><form method="post" action="/v1/auth/verify-email"><input type="hidden" name="token" value="${token}"><button style="background:#087f8c;border:0;border-radius:8px;color:white;font-size:16px;padding:13px 18px">Confirm email</button></form></main></body></html>`;
}

function verificationHtml(message: string, success: boolean): string {
  const color = success ? "#087f8c" : "#a43d25";
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fishdays - NZ email confirmation</title></head><body style="font-family:Arial,sans-serif;background:#f4f7f5;color:#15323d;margin:0;padding:48px 18px"><main style="background:white;border-radius:14px;margin:auto;max-width:520px;padding:32px"><h1 style="color:${color}">${success ? "Email confirmed" : "Confirmation needed"}</h1><p>${message}</p></main></body></html>`;
}

function htmlHeaders(): HeadersInit { return { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer", "x-content-type-options": "nosniff" }; }

async function logout(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  await env.RULES_DB.prepare("DELETE FROM account_sessions WHERE token_hash = ?").bind(await sha256(auth.sessionToken)).run();
  return json({ ok: true });
}

async function logoutEverywhere(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  await env.RULES_DB.prepare("DELETE FROM account_sessions WHERE user_id = ?").bind(auth.account.id).run();
  return json({ ok: true });
}

async function updatePassword(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  const result = await changePassword(request, env, auth.account, auth.sessionToken, payload.current_password, payload.new_password);
  if (result.ok) return json({ changed: true });
  return result.status === 429
    ? tooManyRequests(result.error, 15 * MINUTE, corsHeaders())
    : json({ error: result.error, code: result.code }, result.status);
}

async function me(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  if (request.method === "GET") return json({ user: publicAccount(auth.account) });
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  if (request.method === "DELETE") {
    if (payload.confirm !== true) return json({ error: "Confirm permanent account deletion." }, 400);
    await deleteAccountData(env, auth.account.id);
    usageNotRecorded.add(request);
    return json({ deleted: true });
  }
  const displayName = payload.display_name === undefined ? auth.account.display_name : payload.display_name;
  const countryCode = payload.country_code === undefined ? auth.account.country_code : payload.country_code;
  if (typeof displayName !== "string" || displayName.trim().length > 80) return json({ error: "Display name must be 80 characters or fewer." }, 400);
  if (typeof countryCode !== "string" || !/^[A-Za-z]{2}$/.test(countryCode)) return json({ error: "Country code must be a two-letter code." }, 400);
  const now = new Date().toISOString();
  await env.RULES_DB.prepare("UPDATE account_users SET display_name = ?, country_code = ?, updated_at = ? WHERE id = ?")
    .bind(displayName.trim(), countryCode.toUpperCase(), now, auth.account.id).run();
  return json({ user: publicAccount({ ...auth.account, display_name: displayName.trim(), country_code: countryCode.toUpperCase() }) });
}

async function permissions(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  return json({
    plan: auth.account.plan,
    features: { fishing_rules: true, trip_planning: true, fish_identity: true },
    fish_identity_quota: await identificationQuota(env.RULES_DB, auth.account.id),
  });
}

async function submitFeedback(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  const category = payload.category ?? "general";
  const message = typeof payload.message === "string" ? payload.message.trim() : "";
  const rating = payload.rating === undefined || payload.rating === null ? null : payload.rating;
  if (category !== "general" && category !== "bug" && category !== "idea") return json({ error: "Category must be general, bug, or idea." }, 400);
  if (message.length < 3 || message.length > 4000) return json({ error: "Feedback must be between 3 and 4000 characters." }, 400);
  if (rating !== null && (!Number.isInteger(rating) || Number(rating) < 1 || Number(rating) > 5)) return json({ error: "Rating must be an integer from 1 to 5." }, 400);
  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("feedback-user", auth.account.id), limit: 10 }, HOUR)) {
    return tooManyRequests("You have sent a lot of feedback. Please try again later.", HOUR, corsHeaders());
  }
  const id = crypto.randomUUID();
  const createdAt = new Date().toISOString();
  await env.RULES_DB.prepare("INSERT INTO account_feedback (id, user_id, category, message, rating, created_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(id, auth.account.id, category, message, rating, createdAt).run();
  await recordAccountEvent(env, auth.account.id, "feedback_submitted", "feedback", clientPlatform(request));
  return json({ feedback: { id, category, message, rating, created_at: createdAt } }, 201);
}

async function submitAnalyticsEvent(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
  const eventName = payload.event_name;
  const feature = payload.feature;
  const platform = payload.platform;
  const features = new Set(["home", "map", "tide", "trip_planning", "fishing_rules", "account"]);
  if (eventName !== "app_opened" && eventName !== "feature_used") return json({ error: "Unsupported analytics event." }, 400);
  if (platform !== "web" && platform !== "ios" && platform !== "android") return json({ error: "Platform must be web, ios, or android." }, 400);
  if (eventName === "feature_used" && (typeof feature !== "string" || !features.has(feature))) return json({ error: "Unsupported feature." }, 400);
  const recent = await env.RULES_DB.prepare("SELECT COUNT(*) AS count FROM account_events WHERE user_id = ? AND occurred_at > ?")
    .bind(auth.account.id, new Date(Date.now() - 60_000).toISOString()).first<{ count: number }>();
  if ((recent?.count || 0) >= 60) return json({ error: "Please slow down and try again shortly." }, 429);
  await recordAccountEvent(env, auth.account.id, eventName, eventName === "feature_used" ? feature as string : null, platform);
  return json({ ok: true }, 202);
}

async function getAccountAnalytics(request: Request, env: Env): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: "Admin authorization required." }, 401);
  const days = readLimit(new URL(request.url).searchParams.get("days"), 30, 90);
  const since = new Date(Date.now() - days * 86_400_000).toISOString();
  const [counts, plans, signups, features, eventDays, recentEvents, recentFeedback] = await Promise.all([
    env.RULES_DB.prepare(
      `SELECT COUNT(*) AS total_users, COALESCE(SUM(email_verified), 0) AS verified_users,
       COALESCE(SUM(CASE WHEN email_verified = 0 THEN 1 ELSE 0 END), 0) AS pending_users FROM account_users`
    ).first<{ total_users: number; verified_users: number; pending_users: number }>(),
    env.RULES_DB.prepare("SELECT plan, COUNT(*) AS users FROM account_users GROUP BY plan ORDER BY plan").all(),
    env.RULES_DB.prepare(
      `SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS users FROM account_users
       WHERE created_at >= ? GROUP BY day ORDER BY day`
    ).bind(since).all(),
    env.RULES_DB.prepare(
      `SELECT feature, platform, COUNT(*) AS uses, COUNT(DISTINCT user_id) AS users
       FROM account_events WHERE event_name IN ('feature_used', 'fish_identity_used') AND feature IS NOT NULL AND occurred_at >= ?
       GROUP BY feature, platform ORDER BY uses DESC`
    ).bind(since).all(),
    env.RULES_DB.prepare(
      `SELECT substr(occurred_at, 1, 10) AS day, COUNT(*) AS events, COUNT(DISTINCT user_id) AS users
       FROM account_events WHERE occurred_at >= ? GROUP BY day ORDER BY day`
    ).bind(since).all(),
    env.RULES_DB.prepare(
      `SELECT e.event_name, e.feature, e.platform, e.occurred_at, u.email
       FROM account_events e LEFT JOIN account_users u ON u.id = e.user_id
       ORDER BY e.occurred_at DESC LIMIT 30`
    ).all(),
    env.RULES_DB.prepare(
      `SELECT f.id, u.email, f.category, f.message, f.rating, f.created_at
       FROM account_feedback f JOIN account_users u ON u.id = f.user_id
       ORDER BY f.created_at DESC LIMIT 20`
    ).all(),
  ]);
  return json({
    range_days: days,
    users: counts,
    plans: plans.results,
    signups_by_day: signups.results,
    feature_usage: features.results,
    events_by_day: eventDays.results,
    recent_events: recentEvents.results,
    recent_feedback: recentFeedback.results,
  });
}

async function recordAccountEvent(env: Env, userId: string | null, eventName: string, feature: string | null, platform: "web" | "ios" | "android"): Promise<void> {
  await env.RULES_DB.prepare("INSERT INTO account_events (id, user_id, event_name, feature, platform, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(crypto.randomUUID(), userId, eventName, feature, platform, new Date().toISOString()).run();
}

async function listUsers(request: Request, env: Env): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: "Admin authorization required." }, 401);
  const params = new URL(request.url).searchParams;
  const limit = readLimit(params.get("limit"), 25, 100);
  const offset = readOffset(params.get("offset"));
  const search = (params.get("search") || "").trim();
  if (search.length > 120) return json({ error: "Search must be 120 characters or fewer." }, 400);
  const where = search ? " WHERE email LIKE ? ESCAPE '!' OR display_name LIKE ? ESCAPE '!'" : "";
  const escapedSearch = `%${search.replace(/[!%_]/g, (character) => `!${character}`)}%`;
  const filters = search ? [escapedSearch, escapedSearch] : [];
  const [count, result] = await Promise.all([
    env.RULES_DB.prepare(`SELECT COUNT(*) AS total FROM account_users${where}`).bind(...filters).first<{ total: number }>(),
    env.RULES_DB.prepare(
      `SELECT id, email, display_name, country_code, plan, created_at, email_verified, (password_hash != '') AS has_password
       FROM account_users${where} ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`
    ).bind(...filters, limit, offset).all<Account>(),
  ]);
  return json({ users: result.results.map(publicAccount), total: count?.total || 0, limit, offset });
}

async function listFeedback(request: Request, env: Env): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: "Admin authorization required." }, 401);
  const params = new URL(request.url).searchParams;
  const limit = readLimit(params.get("limit"), 20, 100);
  const offset = readOffset(params.get("offset"));
  const [count, result] = await Promise.all([
    env.RULES_DB.prepare("SELECT COUNT(*) AS total FROM account_feedback").first<{ total: number }>(),
    env.RULES_DB.prepare(
      `SELECT f.id, f.user_id, u.email, f.category, f.message, f.rating, f.created_at
       FROM account_feedback f JOIN account_users u ON u.id = f.user_id
       ORDER BY f.created_at DESC, f.id DESC LIMIT ? OFFSET ?`
    ).bind(limit, offset).all(),
  ]);
  return json({ feedback: result.results, total: count?.total || 0, limit, offset });
}

async function updatePlan(request: Request, env: Env, userId: string): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: "Admin authorization required." }, 401);
  const payload = await readJson(request);
  if (!payload || (payload.plan !== "free" && payload.plan !== "paid")) return json({ error: "Plan must be free or paid." }, 400);
  const now = new Date().toISOString();
  const result = await env.RULES_DB.prepare("UPDATE account_users SET plan = ?, updated_at = ? WHERE id = ? AND (? = 'free' OR email_verified = 1)")
    .bind(payload.plan, now, userId, payload.plan).run();
  if (!result.meta.changes) {
    const user = await env.RULES_DB.prepare("SELECT id FROM account_users WHERE id = ?").bind(userId).first<{ id: string }>();
    return user ? json({ error: "Confirm the user’s email before granting paid access." }, 409) : json({ error: "User not found." }, 404);
  }
  return json({ user_id: userId, plan: payload.plan, updated_at: now });
}

async function createSessionResponse(env: Env, account: Account, status: number): Promise<Response> {
  const token = randomHex(32);
  const now = new Date();
  const expiresAt = new Date(now.getTime() + SESSION_LIFETIME_DAYS * 86_400_000).toISOString();
  await env.RULES_DB.prepare("INSERT INTO account_sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
    .bind(await sha256(token), account.id, now.toISOString(), expiresAt).run();
  await env.RULES_DB.prepare("DELETE FROM account_sessions WHERE expires_at <= ?").bind(now.toISOString()).run();
  return json({
    token,
    token_type: "Bearer",
    expires_at: expiresAt,
    user: publicAccount(account),
    permissions: { plan: account.plan, features: { fishing_rules: true, trip_planning: true, fish_identity: true },
      fish_identity_quota: await identificationQuota(env.RULES_DB, account.id) },
  }, status);
}

async function authenticate(request: Request, env: Env): Promise<AuthContext | null> {
  const authorization = request.headers.get("authorization") || "";
  const match = authorization.match(/^Bearer ([a-f0-9]{64})$/i);
  if (!match) return null;
  const token = match[1].toLowerCase();
  const now = new Date().toISOString();
  const row = await env.RULES_DB.prepare(
    `SELECT u.id, u.email, u.display_name, u.country_code, u.plan, u.created_at, u.email_verified,
            (u.password_hash != '') AS has_password
     FROM account_sessions s JOIN account_users u ON u.id = s.user_id
     WHERE s.token_hash = ? AND s.expires_at > ?`
  ).bind(await sha256(token), now).first<Account>();
  if (row) authenticatedUsers.set(request, row.id);
  return row ? { account: row, sessionToken: token } : null;
}

function publicAccount(account: Account): PublicAccount {
  const { email_verified, has_password, ...details } = account;
  return { ...details, email_verified: email_verified === 1, has_password: has_password === 1 };
}

function clientPlatform(request: Request): "web" | "ios" | "android" {
  const platform = request.headers.get("x-client-platform");
  return platform === "ios" || platform === "android" ? platform : "web";
}

function isValidEmail(email: string): boolean {
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function isAdmin(request: Request, env: Env): boolean {
  const supplied = request.headers.get("x-account-admin-token") || "";
  return Boolean(env.ACCOUNT_ADMIN_TOKEN && supplied && constantTimeEqual(supplied, env.ACCOUNT_ADMIN_TOKEN));
}

type OpenAIFishIdentification = {
  is_fish: boolean;
  common_name_nz: string;
  subject_name?: string;
  scientific_name: string;
  confidence: number;
  other_possibilities: string[];
  visible_clues: string;
  note: string;
};

const openAIFishSchema = {
  type: "object",
  properties: {
    is_fish: { type: "boolean" },
    common_name_nz: { type: "string" },
    subject_name: { type: "string" },
    scientific_name: { type: "string" },
    confidence: { type: "number" },
    other_possibilities: { type: "array", items: { type: "string" } },
    visible_clues: { type: "string" },
    note: { type: "string" },
  },
  required: ["is_fish", "common_name_nz", "subject_name", "scientific_name", "confidence", "other_possibilities", "visible_clues", "note"],
  additionalProperties: false,
};

export default {
  async fetch(request: Request, env: Env, ctx?: ExecutionContext): Promise<Response> {
    const response = await handleRequest(request, env);
    // Count the call for the signed-in account and/or consenting device after the response is on its way.
    if (ctx) ctx.waitUntil(recordRequestUsage(request, env, response).catch((error) => console.error("Could not record API usage", String(error))));
    return response;
  },
  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    await cleanupAccountData(env);
    const areaIds = [...rulesAreas.keys()];
    const day = Math.floor(controller.scheduledTime / 86_400_000);
    const areaId = areaIds[day % areaIds.length];
    const refreshed = await refreshRuleArea(areaId, env);
    if (!refreshed) throw new Error(`MPI rules refresh failed for ${areaId}. See /v1/rules/status.`);
  },
};

async function recordRequestUsage(request: Request, env: Env, response: Response): Promise<void> {
  if (usageNotRecorded.has(request)) return;
  await recordApiUsage(env.RULES_DB, request, authenticatedUsers.get(request) ?? null, response.status);
}

async function handleRequest(request: Request, env: Env): Promise<Response> {
    try {
    const pathname = new URL(request.url).pathname;
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
    if (pathname === "/__health") return json({ ok: true, version: packageInfo.version });
    const privacyResponse = await handlePrivacyRoute(request, env);
    if (privacyResponse) return privacyResponse;
    const shareResponse = await handleShareRoute(request, env);
    if (shareResponse) return shareResponse;
    const passwordResponse = await handlePasswordRoute(request, env);
    if (passwordResponse) return passwordResponse;
    if (pathname === "/v1/auth/providers" || pathname.startsWith("/v1/auth/oauth/")) {
      return await handleSocialAuth(request, env, { authenticate, createSessionResponse });
    }
    if (pathname === "/v1/rules/status" && request.method === "GET") return await getCrawlStatus(env);
    if (pathname === "/v1/rules" && request.method === "GET") return await getRules(request, env);
    if (pathname === "/v1/fish/rules" && request.method === "GET") return await getFishRules(request, env);
    if (pathname === "/v1/rules/import" && request.method === "POST") return await importRules(request, env);
    if (pathname === "/v1/rules/source" && request.method === "POST") return await getMpiSource(request, env);
    if (pathname === "/v1/auth/register" && request.method === "POST") return await register(request, env);
    if (pathname === "/v1/auth/login" && request.method === "POST") return await login(request, env);
    if (pathname === "/v1/auth/resend-verification" && request.method === "POST") return await resendVerification(request, env);
    if (pathname === "/v1/auth/verify-email" && request.method === "GET") return await verificationPage(request);
    if (pathname === "/v1/auth/verify-email" && request.method === "POST") return await verifyEmail(request, env);
    if (pathname === "/v1/auth/logout" && request.method === "POST") return await logout(request, env);
    if (pathname === "/v1/auth/logout-all" && request.method === "POST") return await logoutEverywhere(request, env);
    if (pathname === "/v1/me/password" && request.method === "POST") return await updatePassword(request, env);
    if (pathname === "/v1/me" && ["GET", "PATCH", "DELETE"].includes(request.method)) return await me(request, env);
    if (pathname === "/v1/me/permissions" && request.method === "GET") return await permissions(request, env);
    if (pathname === "/v1/feedback" && request.method === "POST") return await submitFeedback(request, env);
    if (pathname === "/v1/analytics/events" && request.method === "POST") return await submitAnalyticsEvent(request, env);
    if (pathname === "/v1/analytics/batch" && request.method === "POST") {
      return await ingestAnalytics(request, env, (await authenticate(request, env))?.account.id ?? null);
    }
    if (pathname === "/v1/analytics/device" && request.method === "DELETE") return await deleteDeviceAnalytics(request, env);
    if (pathname === "/v1/admin/analytics" && request.method === "GET") return await getAccountAnalytics(request, env);
    if (pathname.startsWith("/v1/admin/analytics/")) {
      if (!isAdmin(request, env)) return json({ error: "Admin authorization required." }, 401);
      const adminAnalytics = await handleAdminAnalytics(request, env, pathname);
      if (adminAnalytics) return adminAnalytics;
    }
    if (pathname === "/v1/admin/users" && request.method === "GET") return await listUsers(request, env);
    if (pathname === "/v1/admin/feedback" && request.method === "GET") return await listFeedback(request, env);
    const planMatch = pathname.match(/^\/v1\/admin\/users\/([^/]+)\/plan$/);
    if (planMatch && request.method === "PATCH") return await updatePlan(request, env, decodeURIComponent(planMatch[1]));
    if (request.method !== "POST" || new URL(request.url).pathname !== "/v1/fish/identify") {
      return json({ error: "Not found" }, 404);
    }
    const auth = await authenticate(request, env);
    if (!auth) return json({ error: "Sign in to use fish identification.", code: "authentication_required" }, 401);
    const contentType = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
    const contentLength = Number(request.headers.get("content-length") || 0);
    if (!["image/jpeg", "image/png", "image/webp"].includes(contentType) || contentLength > 20 * 1024 * 1024) {
      return json({ error: "Upload a JPEG, PNG, or WebP image under 20 MB. Export HEIC photos as JPEG first." }, 400);
    }

    let reservedDay: string | null = null;
    try {
      if (!env.OPENAI_API_KEY) return json({ error: "Fish identification is not configured. Add the OpenAI API key to the Worker secrets." }, 503);
      const image = await request.arrayBuffer();
      if (image.byteLength === 0 || image.byteLength > 20 * 1024 * 1024) {
        return json({ error: "Upload an image smaller than 20 MB." }, 400);
      }
      const day = identificationDay();
      const quota = await reserveIdentification(env.RULES_DB, auth.account.id, day);
      if (!quota) return json({
        error: "You’ve used your 5 fish identifications for today. Try again after midnight New Zealand time.",
        code: "daily_identification_limit", fish_identity_quota: await identificationQuota(env.RULES_DB, auth.account.id, day),
      }, 429);
      reservedDay = day;
      const identification = await identifyFishWithOpenAI(image, contentType, env.OPENAI_API_KEY);
      const result = presentFishIdentification(identification);
      const commonName = result.commonName;
      const areaId = validatedFishingRulesArea(request.headers.get("x-fishing-rules-area"));
      let ruleResult = emptyFishRules(areaId);
      if (areaId && result.isFish && commonName !== "Unknown fish") {
        try { ruleResult = await lookupFishRules(areaId, commonName, env); }
        catch (error) { console.error("Could not attach MPI rules to fish identification", String(error)); }
      }
      await recordAccountEvent(env, auth.account.id, "fish_identity_used", "fish_identity", clientPlatform(request))
        .catch((error) => console.error("Could not record fish identification event", error));
      return json({
        fish_identity_quota: quota,
        isFish: result.isFish,
        commonName,
        scientificName: result.scientificName,
        confidence: result.confidence,
        confidenceLevel: confidenceLevel(result.confidence),
        otherPossibilities: result.otherPossibilities,
        visibleClues: result.visibleClues,
        identificationNote: result.identificationNote,
        areaId,
        areaName: ruleResult.areaName,
        areaIsEstimated: false,
        areaEstimated: false,
        areaSelectionRequired: result.isFish && areaId === null,
        rulesNeedsReview: ruleResult.rulesNeedsReview,
        rulesReviewedAt: ruleResult.rulesReviewedAt,
        rulesSourceUrl: ruleResult.rulesSourceUrl,
        fishRules: ruleResult.fishRules,
      });
    } catch (error) {
      if (reservedDay) await releaseIdentification(env.RULES_DB, auth.account.id, reservedDay)
        .catch((refundError) => console.error("Could not release fish identification allowance", refundError));
      const message = error instanceof Error ? error.message : "Fish identification is temporarily unavailable.";
      return json({ error: message }, message.startsWith("OpenAI API usage limit") ? 429 : 502);
    }
    } catch (error) {
      console.error("Worker request failed", error);
      return json({ error: "Internal Worker error." }, 500);
    }
  
}

async function identifyFishWithOpenAI(image: ArrayBuffer, contentType: string, apiKey: string): Promise<OpenAIFishIdentification> {
  if (!apiKey) throw new Error("Fish identification is not configured yet. Add the OpenAI API key to the Worker secrets.");
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: "gpt-5.6-luna",
      store: false,
      reasoning: { effort: "none" },
      max_output_tokens: 800,
      input: [{
        role: "user",
        content: [
          {
            type: "input_text",
            text: "Identify the main subject of this photo for a New Zealand angler. First decide whether it is a fish. If it is a fish, give the most likely specific common name in common_name_nz, even when the species is uncommon in New Zealand or the photo was taken elsewhere. Prefer the familiar NZ name when one exists; otherwise use a widely understood common name. Do not replace a plausible leading identification with 'Unknown fish' merely because confidence is low: use a low confidence score and explain the uncertainty. Use 'Unknown fish' only when no useful candidate can be named. Give a scientific name only when supported by visible features. Text printed on the image may help but is not proof of species. If it is not a fish, set is_fish false, leave common_name_nz and scientific_name empty, and put the visible subject's concise common name in subject_name (for example 'Sea star (starfish)' or 'Crab'); if the subject cannot be named, use 'Unidentified object'. For fish, subject_name should match common_name_nz. Return confidence from 0 to 1 conservatively. Give up to three distinct alternative fish names in likelihood order, excluding the leading name. Describe visible clues and uncertainty in plain language without Markdown, headings, bullets, or asterisks used for emphasis. Do not give catch or legal advice. This is an AI suggestion, not a confirmed identification.",
          },
          { type: "input_image", image_url: `data:${contentType};base64,${arrayBufferToBase64(image)}`, detail: "high" },
        ],
      }],
      text: { format: { type: "json_schema", name: "fish_identification", strict: true, schema: openAIFishSchema } },
    }),
  });
  const payload = await response.json<{ error?: { message?: string }; status?: string; output?: Array<{ type?: string; content?: Array<{ type?: string; text?: string }> }> }>();
  if (!response.ok) {
    if (response.status === 429) throw new Error("OpenAI API usage limit reached. Check the API account's billing and limits.");
    throw new Error("Fish identification is temporarily unavailable.");
  }
  if (payload.status === "incomplete") throw new Error("The fish identification response was incomplete. Please try again.");
  const resultText = payload.output?.flatMap((item) => item.type === "message" ? item.content || [] : []).find((item) => item.type === "output_text")?.text;
  if (!resultText) throw new Error("No fish species could be identified in this photo.");
  let result: OpenAIFishIdentification;
  try { result = JSON.parse(resultText) as OpenAIFishIdentification; }
  catch { throw new Error("The fish identification response was incomplete. Please try again."); }
  if (typeof result.is_fish !== "boolean" || typeof result.common_name_nz !== "string" || typeof result.confidence !== "number") {
    throw new Error("The fish identification response was invalid. Please try again.");
  }
  return result;
}

function presentFishIdentification(identification: OpenAIFishIdentification) {
  const isFish = identification.is_fish;
  const alternatives = Array.isArray(identification.other_possibilities)
    ? identification.other_possibilities.filter((name): name is string => typeof name === "string").map(plainFishText).filter(isUsefulFishName)
    : [];
  const primary = plainFishText(identification.common_name_nz);
  const usedAlternative = isFish && !isUsefulFishName(primary) && alternatives.length > 0;
  const visibleClues = typeof identification.visible_clues === "string" ? plainFishText(identification.visible_clues) : "";
  const identificationNote = typeof identification.note === "string" ? plainFishText(identification.note) : "";
  const subjectName = typeof identification.subject_name === "string" ? plainFishText(identification.subject_name) : "";
  const commonName = isFish
    ? (usedAlternative ? alternatives[0] : isUsefulFishName(primary) ? primary : "Unknown fish")
    : (isUsefulNonFishName(subjectName) ? subjectName : subjectFromVisibleClues(visibleClues) || "Not a fish");
  return {
    isFish,
    commonName,
    scientificName: isFish && typeof identification.scientific_name === "string" ? plainFishText(identification.scientific_name) : "",
    confidence: Math.max(0, Math.min(1, Number.isFinite(identification.confidence) ? identification.confidence : 0)),
    otherPossibilities: isFish ? (usedAlternative ? alternatives.slice(1) : alternatives.filter((name) => name.toLowerCase() !== commonName.toLowerCase())) : [],
    visibleClues,
    identificationNote,
  };
}

// The image model returns plain-text JSON fields, but may still add Markdown.
// Apply this only to model-generated text: MPI's asterisks are legal footnote markers.
function plainFishText(value: string): string {
  return value
    .replace(/(^|\n)[ \t]*#{1,6}[ \t]+/g, "$1")
    .replace(/(^|\n)[ \t]*[-*][ \t]+/g, "$1")
    .replace(/\*+/g, "")
    .replace(/_{2,}/g, "")
    .replace(/`+/g, "")
    .trim();
}

function isUsefulFishName(value: string): boolean {
  return value.length > 0 && !/^(?:unknown(?: fish)?|unidentified(?: fish)?|n\/a|none)$/i.test(value);
}

function isUsefulNonFishName(value: string): boolean {
  const normalized = value.trim().replace(/[.!?]+$/, "").trim();
  return normalized.length > 0 && normalized.length <= 80 &&
    !/^(?:unknown|unidentified(?: object| animal)?|not a fish|non[- ]?fish|object|animal|marine animal|sea creature|other)$/i.test(normalized);
}

function subjectFromVisibleClues(clues: string): string {
  const match = clues.match(/^(?:the (?:image|photo|picture) (?:shows|depicts|contains|appears to show)|the subject (?:is|appears to be)|this (?:appears to be|is|looks like)|it (?:appears to be|is|looks like))\s+(?:an?\s+)?(.+?)(?:\s+(?:on|with|near|against|beside|in)\s+|[,.;]|$)/i);
  const fallback = clues.match(/\b(?:an?\s+)?((?:[a-z-]+\s+){0,3}(?:sea star|starfish|crab|jellyfish|octopus|squid|sea urchin))\b/i);
  const subject = match?.[1]?.trim() || fallback?.[1]?.trim() || "";
  return subject && subject.length <= 80 ? subject[0].toUpperCase() + subject.slice(1) : "";
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function confidenceLevel(value: number): string {
  return value >= 0.8 ? "high" : value >= 0.5 ? "medium" : "low";
}

type RulePage = {
  area_id: string;
  area_name: string;
  source_url: string;
  page_title: string;
  reviewed_at: string | null;
  fetched_at: string;
  content_sha256: string;
  page_text: string;
  sections_json: string;
  tables_json: string;
  page_html?: string;
  crawl_status?: string | null;
};

type FishRuleDetail = { label: string; value: string };
type FishRuleMatch = { species: string; dailyLimit: string | null; minimumSize: string | null; minimumSizeLabel: string | null; details: FishRuleDetail[] };
type FishRulesResult = {
  areaId: string | null; areaName: string; rulesNeedsReview: boolean; rulesReviewedAt: string | null;
  rulesSourceUrl: string | null; fishRules: FishRuleMatch[];
};

export function validatedFishingRulesArea(value: string | null): string | null {
  const areaId = value?.trim() || "";
  return rulesAreas.has(areaId) ? areaId : null;
}

function emptyFishRules(areaId: string | null): FishRulesResult {
  return {
    areaId,
    areaName: areaId ? rulesAreas.get(areaId) || "Fishing area" : "Choose an MPI fishing area",
    rulesNeedsReview: false,
    rulesReviewedAt: null,
    rulesSourceUrl: areaId ? officialRulesUrl(areaId) : null,
    fishRules: [],
  };
}

async function lookupFishRules(areaId: string, species: string, env: Env): Promise<FishRulesResult> {
  const result = emptyFishRules(areaId);
  const rulePage = await env.RULES_DB.prepare(
    `SELECT r.area_id, r.area_name, r.source_url, r.reviewed_at, r.tables_json, s.status AS crawl_status
     FROM mpi_fishing_rules r LEFT JOIN mpi_rules_crawl_status s ON s.area_id = r.area_id
     WHERE r.area_id = ?`
  ).bind(areaId).first<{ area_id: string; area_name: string; source_url: string; reviewed_at: string | null; tables_json: string; crawl_status: string | null }>();
  if (!rulePage) return result;
  const matchesArea = cachedRuleMatchesArea(rulePage, areaId);
  result.rulesNeedsReview = !matchesArea || rulePage.crawl_status === "source_changed";
  if (result.rulesNeedsReview) return result;
  result.rulesReviewedAt = rulePage.reviewed_at || null;
  result.fishRules = findFishRules(rulePage.tables_json, species);
  return result;
}

async function getFishRules(request: Request, env: Env): Promise<Response> {
  const params = new URL(request.url).searchParams;
  const areaId = validatedFishingRulesArea(params.get("area"));
  const species = params.get("species")?.trim() || "";
  if (!areaId) return json({ error: "Choose a valid MPI fishing area." }, 400);
  if (!species || species.length > 120) return json({ error: "Provide a fish species name." }, 400);
  try { return json(await lookupFishRules(areaId, species, env)); }
  catch (error) {
    console.error("Could not load MPI rules for selected area", String(error));
    return json({ error: "Could not load this area's saved MPI rules. Please try again." }, 502);
  }
}

function findFishRules(tablesJson: string, commonName: string): FishRuleMatch[] {
  const tables = JSON.parse(tablesJson) as string[][][];
  const normalize = (value: string) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const meaningfulCell = (value?: string): string | null => {
    const trimmed = value?.trim();
    return trimmed && !/^(?:-|–|—)$/.test(trimmed) ? trimmed : null;
  };
  const target = normalize(commonName);
  if (!target) return [];
  const found = new Map<string, FishRuleMatch>();
  for (const table of tables) {
    const headers = table[0];
    if (!headers || table.length < 2) continue;
    const dailyIndex = headers.findIndex((header) => /daily|bag|catch/i.test(header) && /limit|maximum|take/i.test(header));
    const sizeIndex = headers.findIndex((header) =>
      !/\b(?:mesh|net)\b/i.test(header) && /min(imum)?.*(size|length)|legal.*(size|length)/i.test(header));
    if (!/species/i.test(headers[0] || "") || (dailyIndex < 0 && sizeIndex < 0)) continue;
    const subareaIndex = headers.findIndex((header, index) => index > 0 &&
      (normalize(header) === normalize(headers[0]) || /^(?:fishing )?(?:subarea|area|location|region)$/i.test(header.trim())));
    for (const row of table.slice(1)) {
      const sourceSpecies = row[0] || "";
      // MPI uses full-width footnotes inside tables, and some source rows have
      // merged cells. Their values cannot safely be mapped to these headers.
      if (row.length !== headers.length || /^\s*\*+/.test(sourceSpecies)) continue;
      const candidate = normalize(sourceSpecies);
      if (!candidate || !(candidate === target || candidate.startsWith(`${target} `) || candidate.includes(` ${target} `))) continue;
      const subarea = subareaIndex >= 0 ? row[subareaIndex]?.trim() || "" : "";
      const species = subarea && normalize(subarea) !== candidate ? `${sourceSpecies} — ${subarea}` : sourceSpecies;
      const dailyLimit = dailyIndex >= 0 ? meaningfulCell(row[dailyIndex]) : null;
      const minimumSize = sizeIndex >= 0 ? meaningfulCell(row[sizeIndex]) : null;
      const minimumSizeLabel = minimumSize ? headers[sizeIndex]?.trim() || null : null;
      const details = headers.flatMap((header, index) => {
        if (index === 0 || index === dailyIndex || index === sizeIndex || index === subareaIndex) return [];
        const value = meaningfulCell(row[index]);
        return value ? [{ label: header.trim(), value }] : [];
      });
      if (!dailyLimit && !minimumSize) continue;
      const item = { species, dailyLimit, minimumSize, minimumSizeLabel, details };
      found.set(JSON.stringify(item), item);
    }
  }
  return [...found.values()].slice(0, 8);
}

function ingestTokenValid(request: Request, env: Env): boolean {
  const supplied = request.headers.get("x-rules-ingest-token") || "";
  return Boolean(env.RULES_INGEST_TOKEN && supplied && constantTimeEqual(supplied, env.RULES_INGEST_TOKEN));
}

async function getMpiSource(request: Request, env: Env): Promise<Response> {
  if (!ingestTokenValid(request, env)) {
    return json({ error: "Unauthorized." }, 401);
  }
  let payload: { area_id?: unknown };
  try { payload = await request.json(); } catch { return json({ error: "Expected a JSON object with area_id." }, 400); }
  if (typeof payload.area_id !== "string" || !rulesAreas.has(payload.area_id)) return json({ error: "Unknown fishing area." }, 400);
  const url = `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(payload.area_id)}`;
  let response: Response;
  try {
    response = await fetch(url, { headers: { "user-agent": "CatchCheckNZ-rules-crawler/1.0", accept: "text/html" } });
  } catch (error) {
    return json({ error: "Could not fetch the MPI rules page.", detail: String(error) }, 502);
  }
  const body = await response.text();
  const contentType = response.headers.get("content-type") || "";
  const blockedPage = /ROBOTS[^>]*NOINDEX|Request unsuccessful|incident ID/i.test(body);
  if (!response.ok || !contentType.includes("text/html") || body.length < 500 || blockedPage) {
    return json({ error: `MPI did not return a crawlable rules page for ${payload.area_id}.`, status: response.status, contentType, bodyLength: body.length, blockedPage }, 502);
  }
  return new Response(body, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

async function getRules(request: Request, env: Env): Promise<Response> {
  const area = new URL(request.url).searchParams.get("area");
  if (area && !rulesAreas.has(area)) return json({ error: "Unknown fishing area." }, 400);
  const query = area
    ? await env.RULES_DB.prepare(
      `SELECT r.*, s.status AS crawl_status FROM mpi_fishing_rules r
       LEFT JOIN mpi_rules_crawl_status s ON s.area_id = r.area_id WHERE r.area_id = ?`
    ).bind(area).all<RulePage>()
    : await env.RULES_DB.prepare(
      `SELECT r.*, s.status AS crawl_status FROM mpi_fishing_rules r
       LEFT JOIN mpi_rules_crawl_status s ON s.area_id = r.area_id ORDER BY r.area_name`
    ).all<RulePage>();
  if (area && query.results.length === 0) return json({ error: "No cached rules for this area yet." }, 404);
  const results = query.results.map((row) => {
    const areaId = area || row.area_id;
    const needsReview = row.crawl_status === "source_changed" || !cachedRuleMatchesArea(row, areaId);
    return {
      ...row,
      area_id: areaId,
      area_name: rulesAreas.get(areaId) || row.area_name,
      source_url: rulesAreas.has(areaId) ? officialRulesUrl(areaId) : row.source_url,
      reviewed_at: needsReview ? null : row.reviewed_at,
      page_text: needsReview ? "MPI has updated this area. Review the current rules on the official site." : row.page_text,
      page_html: needsReview ? "" : row.page_html,
      sections: needsReview ? [] : JSON.parse(row.sections_json),
      tables: needsReview ? [] : JSON.parse(row.tables_json),
      sections_json: undefined,
      tables_json: undefined,
      crawl_status: undefined,
      needsReview,
    };
  });
  return json({ source: "Fisheries New Zealand (MPI)", count: results.length, rules: results });
}

async function getCrawlStatus(env: Env): Promise<Response> {
  const query = await env.RULES_DB.prepare("SELECT * FROM mpi_rules_crawl_status ORDER BY last_attempt_at DESC").all();
  return json({ schedule: "daily at 16:00 UTC; one area per run", areas: query.results });
}

async function importRules(request: Request, env: Env): Promise<Response> {
  if (!ingestTokenValid(request, env)) {
    return json({ error: "Unauthorized." }, 401);
  }
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > 4 * 1024 * 1024) return json({ error: "Import is too large." }, 413);
  let payload: unknown;
  try { payload = await request.json(); } catch { return json({ error: "Expected a JSON array of rule pages." }, 400); }
  if (!Array.isArray(payload) || payload.length === 0 || payload.length > rulesAreas.size) {
    return json({ error: "Expected one or more rule pages." }, 400);
  }

  const pages: RulePage[] = [];
  const seen = new Set<string>();
  for (const candidate of payload) {
    if (!candidate || typeof candidate !== "object") return json({ error: "Invalid rule page." }, 400);
    const row = candidate as Partial<RulePage>;
    const expectedName = rulesAreas.get(row.area_id || "");
    const expectedUrl = row.area_id ? `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(row.area_id)}` : "";
    if (!expectedName || seen.has(row.area_id!) || row.area_name !== expectedName || row.source_url !== expectedUrl ||
      typeof row.page_title !== "string" || typeof row.fetched_at !== "string" ||
      typeof row.content_sha256 !== "string" || !/^[a-f0-9]{64}$/.test(row.content_sha256) ||
      typeof row.page_text !== "string" || row.page_text.length < 500 ||
      typeof row.sections_json !== "string" || typeof row.tables_json !== "string" ||
      (row.page_html !== undefined && (typeof row.page_html !== "string" || row.page_html.length > 1_800_000))) {
      return json({ error: "Invalid or incomplete MPI rule data." }, 400);
    }
    try { JSON.parse(row.sections_json); JSON.parse(row.tables_json); } catch { return json({ error: "Rule sections and tables must be valid JSON." }, 400); }
    seen.add(row.area_id!);
    pages.push(row as RulePage);
  }

  const statements = pages.flatMap((row) => [
    env.RULES_DB.prepare(
      `INSERT INTO mpi_fishing_rules
        (area_id, area_name, source_url, page_title, reviewed_at, fetched_at, content_sha256, page_text, sections_json, tables_json, page_html)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(area_id) DO UPDATE SET
          area_name=excluded.area_name, source_url=excluded.source_url, page_title=excluded.page_title,
          reviewed_at=excluded.reviewed_at, fetched_at=excluded.fetched_at, content_sha256=excluded.content_sha256,
          page_text=excluded.page_text, sections_json=excluded.sections_json, tables_json=excluded.tables_json,
          page_html=excluded.page_html`
    ).bind(row.area_id, row.area_name, row.source_url, row.page_title, row.reviewed_at || null, row.fetched_at,
      row.content_sha256, row.page_text, row.sections_json, row.tables_json, row.page_html || ""),
    env.RULES_DB.prepare(
      `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
       VALUES (?, ?, ?, ?, 'success', 200, NULL)
       ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name,
         last_attempt_at=excluded.last_attempt_at, last_success_at=excluded.last_success_at,
         status='success', http_status=200, error=NULL`
    ).bind(row.area_id, row.area_name, row.fetched_at, row.fetched_at),
  ]);
  await env.RULES_DB.batch(statements);
  return json({ saved: pages.length, areas: pages.map((row) => row.area_id) });
}

function areaSlug(id: string): string {
  return rulesSlugs[id] || "";
}

function officialRulesUrl(areaId: string): string {
  return `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(areaId)}`;
}

function cachedRuleMatchesArea(row: { area_id: string; source_url: string }, areaId: string): boolean {
  return rulesAreas.has(areaId) && row.area_id === areaId &&
    row.source_url.replace(/\/+$/, "") === officialRulesUrl(areaId);
}

async function refreshRuleArea(areaId: string, env: Env): Promise<boolean> {
  const areaName = rulesAreas.get(areaId);
  if (!areaName) return false;
  const attemptedAt = new Date().toISOString();
  const url = `https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/${areaSlug(areaId)}`;
  try {
    const response = await fetch(url, { headers: { "user-agent": "CatchCheckNZ-rules-crawler/1.0", accept: "text/html" } });
    const html = await response.text();
    const contentType = response.headers.get("content-type") || "";
    const blocked = /NOINDEX|Request unsuccessful|incident ID|Access denied/i.test(html);
    if (!response.ok || !contentType.includes("text/html") || html.length < 500 || html.length > 1_800_000 || blocked) {
      const reason = blocked ? "MPI returned an anti-bot/interstitial page." : "MPI returned an invalid or oversized page.";
      await saveCrawlFailure(env, areaId, areaName, attemptedAt, response.status, reason);
      return false;
    }

    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(html));
    const contentHash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const reviewedAt = html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ")
      .match(/Last reviewed\s*:?\s*(\d{1,2}[./-]\d{1,2}[./-]\d{2,4})/i)?.[1] || null;
    const cached = await env.RULES_DB.prepare("SELECT content_sha256, reviewed_at FROM mpi_fishing_rules WHERE area_id = ?")
      .bind(areaId).first<{ content_sha256: string; reviewed_at: string | null }>();
    // Manual browser captures and direct fetches can have different page markup.
    // MPI's published review date is the stable comparison when both pages have one.
    const matchesReview = reviewedAt !== null && cached?.reviewed_at !== null && reviewedAt === cached?.reviewed_at;
    const status = !cached ? "needs_import" : matchesReview || cached.content_sha256 === contentHash ? "success" : "source_changed";
    const note = status === "source_changed"
      ? `MPI source changed (SHA-256 ${contentHash}). Review and re-import this area's structured rules.`
      : status === "needs_import" ? "MPI source is available, but structured rules have not been imported." : null;
    // A scheduled fetch must not replace parsed rules with placeholder text or stale limits.
    await env.RULES_DB.prepare(
      `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, last_attempt_at=excluded.last_attempt_at,
         last_success_at=excluded.last_success_at, status=excluded.status,
         http_status=excluded.http_status, error=excluded.error`
    ).bind(areaId, areaName, attemptedAt, attemptedAt, status, response.status, note).run();
    return true;
  } catch (error) {
    await saveCrawlFailure(env, areaId, areaName, attemptedAt, null, String(error).slice(0, 500));
    return false;
  }
}

async function saveCrawlFailure(env: Env, areaId: string, areaName: string, attemptedAt: string, httpStatus: number | null, error: string): Promise<void> {
  await env.RULES_DB.prepare(
    `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
     VALUES (?, ?, ?, NULL, 'failed', ?, ?)
     ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, last_attempt_at=excluded.last_attempt_at,
       status=CASE WHEN mpi_rules_crawl_status.status IN ('source_changed', 'needs_import')
         THEN mpi_rules_crawl_status.status ELSE 'failed' END,
       http_status=excluded.http_status,
       error=CASE WHEN mpi_rules_crawl_status.status IN ('source_changed', 'needs_import')
         THEN mpi_rules_crawl_status.error ELSE excluded.error END`
  ).bind(areaId, areaName, attemptedAt, httpStatus, error).run();
}
