import type { Env } from "./index";
import { expiredCountersStatement } from "./rate-limit";

const genericNotice = "If a Fishdays - NZ account uses that email, a deletion link will be sent. Check your inbox, including spam. The link expires in 24 hours.";
const deletionDetails = "Deletion permanently removes your profile, sign-in connections, sessions, feedback and account-linked usage records from our live database. You cannot undo it. It does not delete your Google or Apple account or photos saved on your phone. Cloudflare database recovery copies can retain deleted records for up to 30 days. Provider security and abuse-prevention records follow their own retention rules.";

export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]!));
}

export function page(title: string, body: string, status = 200): Response {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)} · Fishdays - NZ</title><style>body{font:17px/1.6 system-ui,sans-serif;color:#172235;background:#f6f8fc;margin:0;padding:32px 18px}main{max-width:760px;margin:auto;background:white;padding:28px;border-radius:16px}h1,h2{line-height:1.3}a{color:#315de0}input,button{font:inherit;padding:12px;border-radius:8px;box-sizing:border-box}input[type=email]{display:block;width:100%;margin:8px 0 20px;border:1px solid #8490a4}button{background:#315de0;color:white;border:0;cursor:pointer}label{display:block;margin:16px 0}nav{display:flex;gap:24px;flex-wrap:wrap}small{color:#536179}</style></head><body><main><nav><a href="/privacy">Privacy policy</a><a href="/delete-account">Delete account</a></nav><h1>${escapeHtml(title)}</h1>${body}</main></body></html>`, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8", "cache-control": "no-store",
      "referrer-policy": "no-referrer", "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    },
  });
}

export function privacyPolicy(env: Env): Response {
  const name = env.PUBLIC_DEVELOPER_NAME?.trim();
  const email = env.PUBLIC_SUPPORT_EMAIL?.trim();
  if (!name || !email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return page("Privacy policy unavailable", "<p>The publisher must configure a developer name and support email before this service is released.</p>", 503);
  }
  return page("Fishdays - NZ privacy policy", `
    <p><small>Updated 8 October 2026</small></p>
    <p>Fishdays - NZ is operated by ${escapeHtml(name)}, an individual developer. For privacy questions, access or correction requests, or help deleting data, contact <a href="mailto:${escapeHtml(email)}">${escapeHtml(email)}</a>.</p>
    <h2>Accounts and feedback</h2><p>We collect your email, optional display name, country code, account identifier, sign-in connections and feedback to provide your account and respond to problems. Passwords are stored as salted hashes. Google or Apple sign-in supplies a provider identifier, verified email and, when available, your name. We do not receive your Google or Apple password. Cloudflare hosts the API and database; Resend sends account confirmation, password reset, password-change notice and deletion emails.</p>
    <h2>Location, weather and maps</h2><p>Location access is optional and used while you use the app to find nearby places, tides and rules. You can choose places manually or revoke access in Android settings. Coordinates for places you view or search are sent to Open-Meteo for weather and sea forecasts. Map tiles and styles come from OpenFreeMap and LINZ; those services receive the map areas requested and connection information such as your IP address. LINZ also supplies tide predictions. Your precise coordinates are not included in fish-photo requests to our API.</p>
    <h2>Fish photos and AI</h2><p>On Android, only a photo you choose and explicitly agree to upload is sent through Cloudflare to OpenAI for fish identification. Android uploads a JPEG without the original photo metadata. We do not save the image or identification result in our account database. OpenAI Responses storage is disabled for these requests, but provider abuse-monitoring records can remain for up to 30 days or longer where required for legal or safety reasons. Do not upload photos containing people or private information. Identification is an AI suggestion and can be wrong. Camera permission is optional; the system photo picker gives access only to selected photos.</p>
    <h2>Usage information</h2><p>Account operations, feedback submission and fish-identification usage create account-linked service events. Our API also counts, per day and by type (for example fish identification or rules), the requests made by each signed-in account, so we can run the service and block abuse. Anonymous usage statistics are on by default. The first time you open the app after this was introduced it shows a notice and sends nothing until you answer. Unless you choose Turn off, the app creates a random identifier on your device (not your advertising ID or any hardware identifier) and sends our service your device model, operating system and app version, language and time zone, together with app-open, screen-view and feature-use events such as which screen you opened or whether a search or fish identification worked. This applies whether or not you are signed in, and your API requests are then also counted against that identifier. When you sign in, the identifier is linked to your account so we can see how the app is used before and after sign-in. These events never include your location, photos, email address or text you type. You can turn this off at any time in More → Settings → Terms &amp; privacy: that stops it, resets the identifier, and the app asks us to erase what we stored for it. On Android, Google Analytics (Firebase) is a separate switch that stays off unless you turn it on; it then receives app interactions, device information, app-instance identifiers and approximate location derived from your masked IP address. Advertising ID collection and ad personalisation are disabled. We do not sell your personal information or show advertising.</p>
    <h2>Retention and deletion</h2><p>Profiles and sign-in connections remain until account deletion. Sessions expire after 30 days, email confirmation and deletion links after 24 hours, password reset links after 1 hour, and OAuth handoffs within minutes. Sign-in, sign-up and reset attempts are counted to block abuse; those counters store only a one-way hash of your network address or email and are deleted within a day. Analytics events, device records and API request counts are removed after 90 days, and service events after 90 days and feedback after 365 days by daily cleanup. Open-Meteo's API troubleshooting logs can include IP addresses and requested coordinates and are deleted after 90 days. OpenFreeMap does not normally store IP addresses in access logs; its error logs can include IP addresses for seven days and security-incident logs for up to 30 days. Our current Firebase Analytics configuration retains event data for two months and user data for 14 months; the user-data retention period restarts with new activity. These settings do not cover aggregated standard reports. Contact us to request help with provider-held data. Provider email delivery and infrastructure security records follow provider retention policies.</p>
    <p>${escapeHtml(deletionDetails)}</p><p>Delete immediately in the Android or iPhone app through More → Account → Delete account, or <a href="/delete-account">request deletion here without installing the app</a>. The web route verifies access to your account email and asks for confirmation before deletion. Contact us if you cannot access that inbox. Photos already in your gallery and device preferences can be removed on your device. Deleting an account resets optional Android analytics when performed in the app; provider-held records may need a separate request to us.</p>
    <h2>Security and your choices</h2><p>Data is transmitted using HTTPS. The Android app stores session credentials encrypted using Android Keystore and excludes them from backup. Core planning, maps, tides and rules can be used without an account. You may ask us to access or correct your information, or request deletion of particular data using the contact above. Providers may process information outside New Zealand. We update this policy when our data practices change.</p>
    <h2>Independent fishing information</h2><p>Fishdays - NZ is independent and is not affiliated with or endorsed by the New Zealand Government, MPI or LINZ. Rule summaries use <a href="https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/">official MPI information</a>. Always check current official rules and local restrictions before fishing.</p>`);
}

async function hash(value: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function deleteAccountData(env: Env, userId: string): Promise<void> {
  // Events use ON DELETE SET NULL: delete them explicitly rather than retaining them anonymously.
  // D1 batch is transactional, including the account and all cascading child records.
  // Device analytics go too: events and usage tied to the account, and to any device it has used (including activity before sign-in).
  const devices = "(SELECT device_id FROM analytics_devices WHERE user_id = ?)";
  await env.RULES_DB.batch([
    env.RULES_DB.prepare(`DELETE FROM analytics_events WHERE user_id = ? OR device_id IN ${devices}`).bind(userId, userId),
    env.RULES_DB.prepare(`DELETE FROM api_usage_daily WHERE user_id = ? OR device_id IN ${devices}`).bind(userId, userId),
    env.RULES_DB.prepare("DELETE FROM analytics_devices WHERE user_id = ?").bind(userId),
    env.RULES_DB.prepare("DELETE FROM account_events WHERE user_id = ?").bind(userId),
    env.RULES_DB.prepare("DELETE FROM account_users WHERE id = ?").bind(userId),
  ]);
}

export async function cleanupAccountData(env: Env, now = Date.now()): Promise<void> {
  await env.RULES_DB.batch([
    env.RULES_DB.prepare("DELETE FROM account_events WHERE occurred_at < ?").bind(new Date(now - 90 * 86_400_000).toISOString()),
    env.RULES_DB.prepare("DELETE FROM account_identification_usage WHERE day < ?").bind(new Date(now - 90 * 86_400_000).toISOString().slice(0, 10)),
    env.RULES_DB.prepare("DELETE FROM account_feedback WHERE created_at < ?").bind(new Date(now - 365 * 86_400_000).toISOString()),
    ...["account_sessions", "account_email_verifications", "account_password_resets", "account_deletion_tokens", "account_oauth_flows", "account_oauth_grants"].map((table) =>
      env.RULES_DB.prepare(`DELETE FROM ${table} WHERE expires_at <= ?`).bind(new Date(now).toISOString())),
    expiredCountersStatement(env.RULES_DB, now),
    env.RULES_DB.prepare("DELETE FROM analytics_events WHERE occurred_at < ?").bind(new Date(now - 90 * 86_400_000).toISOString()),
    env.RULES_DB.prepare("DELETE FROM api_usage_daily WHERE day < ?").bind(new Date(now - 90 * 86_400_000).toISOString().slice(0, 10)),
    env.RULES_DB.prepare("DELETE FROM analytics_devices WHERE last_seen_at < ?").bind(new Date(now - 90 * 86_400_000).toISOString()),
  ]);
}

export async function handlePrivacyRoute(request: Request, env: Env): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname === "/privacy" && request.method === "GET") return privacyPolicy(env);
  if (url.pathname !== "/delete-account" && url.pathname !== "/delete-account/confirm") return null;
  if (request.method !== "GET" && request.method !== "POST") return page("Method not allowed", "<p>Use the deletion form.</p>", 405);
  if (request.method === "POST") {
    const origin = request.headers.get("origin");
    if (origin && origin !== url.origin) return page("Request rejected", "<p>Open the deletion page and try again.</p>", 403);
    if (Number(request.headers.get("content-length") || 0) > 4096) return page("Request too large", "<p>Use the deletion form.</p>", 413);
  }
  if (url.pathname === "/delete-account") {
    if (request.method === "GET") return page("Delete your Fishdays - NZ account", `<p>${escapeHtml(deletionDetails)}</p><p>You can request deletion without installing the app or remembering your password. Enter the email used for your account, including your Apple private relay address if applicable. We send a secure link; you then confirm deletion. If you cannot access your inbox, use the support contact in our <a href="/privacy">privacy policy</a>.</p><form method="post" action="/delete-account"><label for="email">Account email</label><input id="email" name="email" type="email" maxlength="254" required autocomplete="email"><button>Send deletion link</button></form>`);
    const form = await request.formData().catch(() => null);
    const email = String(form?.get("email") || "").trim().toLowerCase();
    if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return page("Check your email address", "<p>Enter a valid email on the <a href=\"/delete-account\">deletion form</a>.</p>", 400);
    if (!env.RESEND_API_KEY || !env.ACCOUNT_EMAIL_FROM) return page("Please try again later", "<p>Deletion emails are temporarily unavailable. You can delete from the app or contact the publisher using the <a href=\"/privacy\">privacy policy</a>.</p>", 503);
    const account = await env.RULES_DB.prepare("SELECT id, email FROM account_users WHERE email = ? COLLATE NOCASE").bind(email).first<{ id: string; email: string }>();
    if (account) {
      const recent = await env.RULES_DB.prepare("SELECT token_hash FROM account_deletion_tokens WHERE user_id = ? AND created_at > ? LIMIT 1").bind(account.id, new Date(Date.now() - 60_000).toISOString()).first();
      if (!recent) {
        const token = [...crypto.getRandomValues(new Uint8Array(32))].map((byte) => byte.toString(16).padStart(2, "0")).join("");
        const tokenHash = await hash(token);
        await env.RULES_DB.prepare("INSERT INTO account_deletion_tokens (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)").bind(tokenHash, account.id, new Date().toISOString(), new Date(Date.now() + 86_400_000).toISOString()).run();
        const deletionUrl = `${env.AUTH_BASE_URL || url.origin}/delete-account/confirm?token=${token}`;
        const sent = await fetch("https://api.resend.com/emails", {
          method: "POST", headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, "content-type": "application/json" },
          body: JSON.stringify({ from: env.ACCOUNT_EMAIL_FROM, to: [account.email], subject: "Delete your Fishdays - NZ account", text: `Someone requested deletion of your Fishdays - NZ account. To review and confirm: ${deletionUrl}\n\n${deletionDetails}\n\nThe link expires in 24 hours. If you did not request deletion, ignore this email; your account will remain active.` }),
        }).catch(() => null);
        if (!sent?.ok) {
          await env.RULES_DB.prepare("DELETE FROM account_deletion_tokens WHERE token_hash = ?").bind(tokenHash).run();
          // Keep the public response identical for registered and unregistered addresses.
          console.error("Account deletion email delivery failed");
        }
      }
    }
    return page("Check your inbox", `<p>${genericNotice}</p><p>If the email does not arrive, retry after one minute or use the support contact in our <a href="/privacy">privacy policy</a>.</p>`, 202);
  }
  const form = request.method === "POST" ? await request.formData().catch(() => null) : null;
  const token = request.method === "GET" ? url.searchParams.get("token") || "" : String(form?.get("token") || "");
  if (!/^[a-f0-9]{64}$/.test(token)) return page("Invalid deletion link", "<p>Request a new link from the <a href=\"/delete-account\">deletion page</a>.</p>", 400);
  const row = await env.RULES_DB.prepare("SELECT user_id FROM account_deletion_tokens WHERE token_hash = ? AND expires_at > ?").bind(await hash(token), new Date().toISOString()).first<{ user_id: string }>();
  if (!row) return page("Deletion link expired or used", "<p>Request a new link from the <a href=\"/delete-account\">deletion page</a>.</p>", 400);
  if (request.method === "GET") return page("Confirm account deletion", `<p>${escapeHtml(deletionDetails)}</p><form method="post" action="/delete-account/confirm"><input name="token" type="hidden" value="${token}"><label><input type="checkbox" name="confirm" value="delete" required> I understand and want to permanently delete my account.</label><button>Delete my account permanently</button></form><p><a href="/privacy">Cancel and view privacy policy</a></p>`);
  if (form?.get("confirm") !== "delete") return page("Confirmation required", "<p>Confirm deletion using the link in your email.</p>", 400);
  await deleteAccountData(env, row.user_id);
  return page("Account deleted", "<p>Your Fishdays - NZ account and associated data have been removed from our live database. You can close this page.</p>");
}
