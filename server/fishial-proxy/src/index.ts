export interface Env {
  OPENAI_API_KEY: string;
  RULES_DB: D1Database;
  RULES_INGEST_TOKEN: string;
  ACCOUNT_ADMIN_TOKEN: string;
  RESEND_API_KEY?: string;
  ACCOUNT_EMAIL_FROM?: string;
}

type Account = {
  id: string;
  email: string;
  display_name: string;
  country_code: string;
  plan: "free" | "paid";
  created_at: string;
  email_verified: number;
};

type AccountWithCredentials = Account & { password_hash: string; password_salt: string };
type AuthContext = { account: Account; sessionToken: string };
type PublicAccount = Omit<Account, "email_verified"> & { email_verified: boolean };
// Cloudflare Workers caps a single PBKDF2 operation at 100,000 iterations.
const PASSWORD_ITERATIONS = 100_000;
const SESSION_LIFETIME_DAYS = 30;

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
  if (password.length < 10 || password.length > 128) return json({ error: "Password must be between 10 and 128 characters." }, 400);
  if (displayName.length > 80) return json({ error: "Display name must be 80 characters or fewer." }, 400);

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
  const row = await env.RULES_DB.prepare(
    `SELECT id, email, display_name, country_code, plan, created_at, password_hash, password_salt, email_verified
     FROM account_users WHERE email = ? COLLATE NOCASE`
  ).bind(email).first<AccountWithCredentials>();
  if (!row || !password || !constantTimeEqual(await hashPassword(password, row.password_salt), row.password_hash)) {
    return json({ error: "Email or password is incorrect." }, 401);
  }
  if (row.email_verified !== 1) return json({ error: "Confirm your email before signing in.", code: "email_not_verified" }, 403);
  const { password_hash: _passwordHash, password_salt: _passwordSalt, ...account } = row;
  return await createSessionResponse(env, account, 200);
}

async function resendVerification(request: Request, env: Env): Promise<Response> {
  const payload = await readJson(request);
  if (!payload || typeof payload.email !== "string" || !isValidEmail(payload.email.trim())) return json({ error: "Enter a valid email address." }, 400);
  const email = payload.email.trim().toLowerCase();
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
  return new Response(verificationHtml("Your email is confirmed. Return to CatchCheck and sign in to continue.", true), { headers: htmlHeaders() });
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
      subject: "Confirm your CatchCheck NZ account",
      html: `<div style="font-family:Arial,sans-serif;max-width:560px;margin:auto;color:#15323d"><h1 style="color:#082c3c">Confirm your email</h1><p>Tap the button below to activate your CatchCheck NZ account. This link expires in 24 hours.</p><p><a href="${verifyUrl}" style="background:#087f8c;color:white;padding:13px 18px;border-radius:8px;text-decoration:none;display:inline-block">Confirm email</a></p><p>If you didn’t create a CatchCheck account, you can ignore this email.</p></div>`,
      text: `Confirm your CatchCheck NZ account: ${verifyUrl}\nThis link expires in 24 hours.`,
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
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Confirm CatchCheck account</title></head><body style="font-family:Arial,sans-serif;background:#f4f7f5;color:#15323d;margin:0;padding:48px 18px"><main style="background:white;border-radius:14px;margin:auto;max-width:520px;padding:32px"><h1 style="color:#082c3c">Confirm your CatchCheck email</h1><p>Press the button to activate your account. The link expires after 24 hours.</p><form method="post" action="/v1/auth/verify-email"><input type="hidden" name="token" value="${token}"><button style="background:#087f8c;border:0;border-radius:8px;color:white;font-size:16px;padding:13px 18px">Confirm email</button></form></main></body></html>`;
}

function verificationHtml(message: string, success: boolean): string {
  const color = success ? "#087f8c" : "#a43d25";
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CatchCheck email confirmation</title></head><body style="font-family:Arial,sans-serif;background:#f4f7f5;color:#15323d;margin:0;padding:48px 18px"><main style="background:white;border-radius:14px;margin:auto;max-width:520px;padding:32px"><h1 style="color:${color}">${success ? "Email confirmed" : "Confirmation needed"}</h1><p>${message}</p></main></body></html>`;
}

function htmlHeaders(): HeadersInit { return { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer", "x-content-type-options": "nosniff" }; }

async function logout(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  await env.RULES_DB.prepare("DELETE FROM account_sessions WHERE token_hash = ?").bind(await sha256(auth.sessionToken)).run();
  return json({ ok: true });
}

async function me(request: Request, env: Env): Promise<Response> {
  const auth = await authenticate(request, env);
  if (!auth) return json({ error: "Authentication required." }, 401);
  if (request.method === "GET") return json({ user: publicAccount(auth.account) });
  const payload = await readJson(request);
  if (!payload) return json({ error: "Send a JSON object." }, 400);
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
    features: { fishing_rules: true, trip_planning: true, fish_identity: auth.account.plan === "paid" },
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
      `SELECT id, email, display_name, country_code, plan, created_at, email_verified
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
    permissions: { plan: account.plan, features: { fishing_rules: true, trip_planning: true, fish_identity: account.plan === "paid" } },
  }, status);
}

async function authenticate(request: Request, env: Env): Promise<AuthContext | null> {
  const authorization = request.headers.get("authorization") || "";
  const match = authorization.match(/^Bearer ([a-f0-9]{64})$/i);
  if (!match) return null;
  const token = match[1].toLowerCase();
  const now = new Date().toISOString();
  const row = await env.RULES_DB.prepare(
    `SELECT u.id, u.email, u.display_name, u.country_code, u.plan, u.created_at, u.email_verified
     FROM account_sessions s JOIN account_users u ON u.id = s.user_id
     WHERE s.token_hash = ? AND s.expires_at > ?`
  ).bind(await sha256(token), now).first<Account>();
  return row ? { account: row, sessionToken: token } : null;
}

function publicAccount(account: Account): PublicAccount {
  const { email_verified, ...details } = account;
  return { ...details, email_verified: email_verified === 1 };
}

function clientPlatform(request: Request): "web" | "ios" | "android" {
  const platform = request.headers.get("x-client-platform");
  return platform === "ios" || platform === "android" ? platform : "web";
}

async function hashPassword(password: string, saltHex: string): Promise<string> {
  const material = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: hexToBytes(saltHex), iterations: PASSWORD_ITERATIONS },
    material,
    256,
  );
  return bytesToHex(new Uint8Array(derived));
}

async function sha256(value: string): Promise<string> {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))));
}

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(value: string): Uint8Array {
  return new Uint8Array(value.match(/.{2}/g)?.map((byte) => parseInt(byte, 16)) || []);
}

function constantTimeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index++) difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return difference === 0;
}

function isValidEmail(email: string): boolean {
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > 16_384) return null;
  try {
    const body = await request.arrayBuffer();
    if (body.byteLength > 16_384) return null;
    const value = JSON.parse(new TextDecoder().decode(body)) as unknown;
    return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function readLimit(value: string | null, fallback: number, maximum: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, maximum) : fallback;
}

function readOffset(value: string | null): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? Math.min(parsed, 1_000_000) : 0;
}

function isAdmin(request: Request, env: Env): boolean {
  const supplied = request.headers.get("x-account-admin-token") || "";
  return Boolean(env.ACCOUNT_ADMIN_TOKEN && supplied && constantTimeEqual(supplied, env.ACCOUNT_ADMIN_TOKEN));
}

function corsHeaders(): HeadersInit {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, POST, PATCH, OPTIONS",
    "access-control-allow-headers": "authorization, content-type, x-account-admin-token, x-rules-ingest-token, x-location-lat-lon, x-location-source, x-client-platform",
    "access-control-max-age": "86400",
  };
}

type OpenAIFishIdentification = {
  is_fish: boolean;
  common_name_nz: string;
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
    scientific_name: { type: "string" },
    confidence: { type: "number" },
    other_possibilities: { type: "array", items: { type: "string" } },
    visible_clues: { type: "string" },
    note: { type: "string" },
  },
  required: ["is_fish", "common_name_nz", "scientific_name", "confidence", "other_possibilities", "visible_clues", "note"],
  additionalProperties: false,
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
    const pathname = new URL(request.url).pathname;
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
    if (pathname === "/__health") return json({ ok: true });
    if (pathname === "/v1/rules/status" && request.method === "GET") return await getCrawlStatus(env);
    if (pathname === "/v1/rules" && request.method === "GET") return await getRules(request, env);
    if (pathname === "/v1/rules/import" && request.method === "POST") return await importRules(request, env);
    if (pathname === "/v1/rules/source" && request.method === "POST") return await getMpiSource(request, env);
    if (pathname === "/v1/auth/register" && request.method === "POST") return await register(request, env);
    if (pathname === "/v1/auth/login" && request.method === "POST") return await login(request, env);
    if (pathname === "/v1/auth/resend-verification" && request.method === "POST") return await resendVerification(request, env);
    if (pathname === "/v1/auth/verify-email" && request.method === "GET") return await verificationPage(request);
    if (pathname === "/v1/auth/verify-email" && request.method === "POST") return await verifyEmail(request, env);
    if (pathname === "/v1/auth/logout" && request.method === "POST") return await logout(request, env);
    if (pathname === "/v1/me" && (request.method === "GET" || request.method === "PATCH")) return await me(request, env);
    if (pathname === "/v1/me/permissions" && request.method === "GET") return await permissions(request, env);
    if (pathname === "/v1/feedback" && request.method === "POST") return await submitFeedback(request, env);
    if (pathname === "/v1/analytics/events" && request.method === "POST") return await submitAnalyticsEvent(request, env);
    if (pathname === "/v1/admin/analytics" && request.method === "GET") return await getAccountAnalytics(request, env);
    if (pathname === "/v1/admin/users" && request.method === "GET") return await listUsers(request, env);
    if (pathname === "/v1/admin/feedback" && request.method === "GET") return await listFeedback(request, env);
    const planMatch = pathname.match(/^\/v1\/admin\/users\/([^/]+)\/plan$/);
    if (planMatch && request.method === "PATCH") return await updatePlan(request, env, decodeURIComponent(planMatch[1]));
    if (request.method !== "POST" || new URL(request.url).pathname !== "/v1/fish/identify") {
      return json({ error: "Not found" }, 404);
    }
    const auth = await authenticate(request, env);
    if (!auth) return json({ error: "Sign in to use fish identification.", code: "authentication_required" }, 401);
    if (auth.account.plan !== "paid") return json({ error: "Fish identification is available on the paid plan.", code: "plan_required" }, 403);
    const contentType = request.headers.get("content-type") || "";
    const contentLength = Number(request.headers.get("content-length") || 0);
    if (!contentType.startsWith("image/") || contentLength > 20 * 1024 * 1024) {
      return json({ error: "Upload a JPEG, PNG, or other image smaller than 20 MB." }, 400);
    }

    try {
      if (!env.OPENAI_API_KEY) return json({ error: "Fish identification is not configured. Add the OpenAI API key to the Worker secrets." }, 503);
      const image = await request.arrayBuffer();
      if (image.byteLength === 0 || image.byteLength > 20 * 1024 * 1024) {
        return json({ error: "Upload an image smaller than 20 MB." }, 400);
      }
      const identification = await identifyFishWithOpenAI(image, contentType, env.OPENAI_API_KEY);
      if (!identification.is_fish) return json({ error: "No fish could be identified in this photo." }, 422);
      const commonName = identification.common_name_nz || "Unknown fish";
      const point = parseLocation(request.headers.get("x-location-lat-lon"));
      const areaId = rulesAreaForLocation(point.latitude, point.longitude);
      const rulePage = await env.RULES_DB.prepare("SELECT area_name, source_url, reviewed_at, tables_json FROM mpi_fishing_rules WHERE area_id = ?")
        .bind(areaId).first<{ area_name: string; source_url: string; reviewed_at: string | null; tables_json: string }>();
      const rules = rulePage ? findFishRules(rulePage.tables_json, commonName) : [];
      await recordAccountEvent(env, auth.account.id, "fish_identity_used", "fish_identity", clientPlatform(request));
      return json({
        commonName,
        scientificName: identification.scientific_name || "",
        confidence: Math.max(0, Math.min(1, Number(identification.confidence) || 0)),
        confidenceLevel: confidenceLevel(identification.confidence),
        otherPossibilities: identification.other_possibilities,
        visibleClues: identification.visible_clues,
        identificationNote: identification.note,
        areaId,
        areaName: rulePage?.area_name || rulesAreas.get(areaId),
        areaEstimated: request.headers.get("x-location-source") !== "device",
        rulesReviewedAt: rulePage?.reviewed_at || null,
        rulesSourceUrl: rulePage?.source_url || null,
        fishRules: rules,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Fish identification is temporarily unavailable.";
      return json({ error: message }, message.startsWith("OpenAI API usage limit") ? 429 : 502);
    }
    } catch (error) {
      console.error("Worker request failed", error);
      return json({ error: "Internal Worker error." }, 500);
    }
  },
  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    const areaIds = [...rulesAreas.keys()];
    const day = Math.floor(controller.scheduledTime / 86_400_000);
    const areaId = areaIds[day % areaIds.length];
    const refreshed = await refreshRuleArea(areaId, env);
    if (!refreshed) throw new Error(`MPI rules refresh failed for ${areaId}. See /v1/rules/status.`);
  },
};

async function identifyFishWithOpenAI(image: ArrayBuffer, contentType: string, apiKey: string): Promise<OpenAIFishIdentification> {
  if (!apiKey) throw new Error("Fish identification is not configured yet. Add the OpenAI API key to the Worker secrets.");
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: "gpt-5.6-luna",
      reasoning: { effort: "none" },
      max_output_tokens: 800,
      input: [{
        role: "user",
        content: [
          {
            type: "input_text",
            text: "Identify the fish for a New Zealand angler. Prefer the familiar New Zealand common name (for example snapper, kahawai, kingfish, blue cod, or tarakihi), then give the scientific name if you can support it. Use visible features and NZ species knowledge. Do not invent a species. If this is not clearly a fish, set is_fish false. If the species cannot be distinguished, use common_name_nz 'Unknown fish', an empty scientific_name, and low confidence. Return confidence from 0 to 1 conservatively: 1 means unmistakable visible features; 0.5 means uncertain. Give up to three alternatives and briefly state visible clues and uncertainty. Do not give catch or legal advice. This is an AI suggestion, not a confirmed identification.",
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
};

type FishRuleDetail = { label: string; value: string };
type FishRuleMatch = { species: string; dailyLimit: string | null; minimumSize: string | null; details: FishRuleDetail[] };

function parseLocation(value: string | null): { latitude: number; longitude: number } {
  if (value) {
    const [latitude, longitude] = value.split(",").map(Number);
    if (Number.isFinite(latitude) && Number.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) return { latitude, longitude };
  }
  return { latitude: -36.85, longitude: 174.76 };
}

function rulesAreaForLocation(latitude: number, longitude: number): string {
  if (longitude < -175 && latitude < -40 && latitude > -49) return "chatham-rise";
  if (latitude <= -44.5 && longitude >= 166 && longitude <= 168.8) return "fiordland";
  if (latitude <= -46.3) return "southland";
  if (latitude < -42.4 && latitude > -44 && longitude >= 172.2 && longitude <= 174.4) return "kaikoura";
  if (latitude <= -40 && latitude >= -46.3 && longitude < 171) return "challenger";
  if (latitude <= -42.5 && latitude > -46.3 && longitude >= 171) return "south-east";
  if (latitude > -37.7) return "auckland-kermadec";
  if (latitude > -41.6) return "central";
  return "challenger";
}

function findFishRules(tablesJson: string, commonName: string): FishRuleMatch[] {
  const tables = JSON.parse(tablesJson) as string[][][];
  const normalize = (value: string) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const target = normalize(commonName);
  if (!target) return [];
  const found = new Map<string, FishRuleMatch>();
  for (const table of tables) {
    const headers = table[0];
    if (!headers || table.length < 2) continue;
    const dailyIndex = headers.findIndex((header) => /daily|bag|catch/i.test(header) && /limit|maximum|take/i.test(header));
    const sizeIndex = headers.findIndex((header) => /min(imum)?.*(size|length)|legal.*(size|length)/i.test(header));
    for (const row of table.slice(1)) {
      const species = row[0] || "";
      const candidate = normalize(species);
      if (!candidate || !(candidate === target || candidate.startsWith(`${target} `) || candidate.includes(` ${target} `))) continue;
      const dailyLimit = dailyIndex >= 0 ? row[dailyIndex]?.trim() || null : null;
      const minimumSize = sizeIndex >= 0 ? row[sizeIndex]?.trim() || null : null;
      const details = headers.flatMap((header, index) => {
        if (index === 0 || index === dailyIndex || index === sizeIndex) return [];
        const value = row[index]?.trim();
        return value && value !== "—" ? [{ label: header.trim(), value }] : [];
      });
      const item = { species, dailyLimit, minimumSize, details };
      found.set(JSON.stringify(item), item);
    }
  }
  return [...found.values()].slice(0, 8);
}

async function getMpiSource(request: Request, env: Env): Promise<Response> {
  if (!env.RULES_INGEST_TOKEN || request.headers.get("x-rules-ingest-token") !== env.RULES_INGEST_TOKEN) {
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
  const query = area
    ? await env.RULES_DB.prepare("SELECT * FROM mpi_fishing_rules WHERE area_id = ?").bind(area).all<RulePage>()
    : await env.RULES_DB.prepare("SELECT * FROM mpi_fishing_rules ORDER BY area_name").all<RulePage>();
  if (area && !rulesAreas.has(area)) return json({ error: "Unknown fishing area." }, 400);
  if (area && query.results.length === 0) return json({ error: "No cached rules for this area yet." }, 404);
  const results = query.results.map((row) => ({
    ...row,
    sections: JSON.parse(row.sections_json),
    tables: JSON.parse(row.tables_json),
    sections_json: undefined,
    tables_json: undefined,
  }));
  return json({ source: "Fisheries New Zealand (MPI)", count: results.length, rules: results });
}

async function getCrawlStatus(env: Env): Promise<Response> {
  const query = await env.RULES_DB.prepare("SELECT * FROM mpi_rules_crawl_status ORDER BY last_attempt_at DESC").all();
  return json({ schedule: "daily at 16:00 UTC; one area per run", areas: query.results });
}

async function importRules(request: Request, env: Env): Promise<Response> {
  if (!env.RULES_INGEST_TOKEN || request.headers.get("x-rules-ingest-token") !== env.RULES_INGEST_TOKEN) {
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

    const title = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]?.replace(/<[^>]+>/g, " ").trim() || `${areaName} fishing rules`;
    const review = html.match(/Last reviewed\s*:?\s*([^<\n]{4,30})/i)?.[1]?.trim() || null;
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(html));
    const contentHash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const sourceText = `${title}\nOfficial source: ${url}`;
    await env.RULES_DB.batch([
      env.RULES_DB.prepare(
        `INSERT INTO mpi_fishing_rules
          (area_id, area_name, source_url, page_title, reviewed_at, fetched_at, content_sha256, page_text, sections_json, tables_json, page_html)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, source_url=excluded.source_url,
           page_title=excluded.page_title, reviewed_at=excluded.reviewed_at, fetched_at=excluded.fetched_at,
           content_sha256=excluded.content_sha256, page_text=excluded.page_text,
           sections_json=excluded.sections_json, tables_json=excluded.tables_json, page_html=excluded.page_html`
      ).bind(areaId, areaName, url, title, review, attemptedAt, contentHash, sourceText,
        JSON.stringify([{ heading: title, text: sourceText }]), "[]", html),
      env.RULES_DB.prepare(
        `INSERT INTO mpi_rules_crawl_status (area_id, area_name, last_attempt_at, last_success_at, status, http_status, error)
         VALUES (?, ?, ?, ?, 'success', ?, NULL)
         ON CONFLICT(area_id) DO UPDATE SET area_name=excluded.area_name, last_attempt_at=excluded.last_attempt_at,
           last_success_at=excluded.last_success_at, status='success', http_status=excluded.http_status, error=NULL`
      ).bind(areaId, areaName, attemptedAt, attemptedAt, response.status),
    ]);
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
       status='failed', http_status=excluded.http_status, error=excluded.error`
  ).bind(areaId, areaName, attemptedAt, httpStatus, error).run();
}

function json(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders() },
  });
}
