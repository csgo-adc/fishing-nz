import type { Env } from "./index";
import { sendEmail } from "./email";
import { escapeHtml, page } from "./privacy";
import { HOUR, MINUTE, allowAttempt, clientAddress, isRateLimited, rateKey, recordAttempt } from "./rate-limit";
import { hashPassword, passwordMatches, randomHex, sha256 } from "./security";

const RESET_LIFETIME_MS = HOUR;
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 128;
const resetNotice = "If an account uses that email, a password reset link will be sent. Check your inbox, including spam. The link expires in 1 hour.";

export function passwordProblem(password: unknown): string | null {
  if (typeof password !== "string" || password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
    return `Password must be between ${PASSWORD_MIN} and ${PASSWORD_MAX} characters.`;
  }
  return null;
}

function authBase(env: Env, url: URL): string {
  return env.AUTH_BASE_URL || url.origin;
}

function notifyPasswordChanged(env: Env, email: string, base: string): Promise<boolean> {
  return sendEmail(env, {
    to: email,
    subject: "Your Fishdays - NZ password was changed",
    text: `The password for your Fishdays - NZ account was just changed, and you were signed out of your other devices.\n\nIf this was you, no action is needed. If it was not, reset your password now at ${base}/forgot-password and contact support from ${base}/privacy.`,
  });
}

/** Public web pages: /forgot-password asks for an email and /reset-password sets a new password from the emailed link. */
export async function handlePasswordRoute(request: Request, env: Env): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/forgot-password" && url.pathname !== "/reset-password") return null;
  if (request.method !== "GET" && request.method !== "POST") return page("Method not allowed", "<p>Use the form on the password page.</p>", 405);
  if (request.method === "POST") {
    const origin = request.headers.get("origin");
    if (origin && origin !== url.origin) return page("Request rejected", "<p>Open the password page and try again.</p>", 403);
    if (Number(request.headers.get("content-length") || 0) > 4096) return page("Request too large", "<p>Use the form on the password page.</p>", 413);
  }
  return url.pathname === "/forgot-password" ? forgotPassword(request, env, url) : resetPassword(request, env, url);
}

async function forgotPassword(request: Request, env: Env, url: URL): Promise<Response> {
  if (request.method === "GET") {
    return page("Reset your Fishdays - NZ password", `<p>Enter the email used for your account. We will send a link that lets you choose a new password. This also works if you signed up with Google or Apple and want to add a password.</p><form method="post" action="/forgot-password"><label for="email">Account email</label><input id="email" name="email" type="email" maxlength="254" required autocomplete="email"><button>Send reset link</button></form>`);
  }
  const form = await request.formData().catch(() => null);
  const email = String(form?.get("email") || "").trim().toLowerCase();
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return page("Check your email address", "<p>Enter a valid email on the <a href=\"/forgot-password\">reset form</a>.</p>", 400);
  }
  if (!await allowAttempt(env.RULES_DB, { key: await rateKey("forgot-ip", clientAddress(request)), limit: 10 }, HOUR)) {
    return page("Too many requests", "<p>Please wait a while before requesting another reset link.</p>", 429);
  }
  if (!env.RESEND_API_KEY || !env.ACCOUNT_EMAIL_FROM) {
    return page("Please try again later", "<p>Reset emails are temporarily unavailable. Contact the publisher using the <a href=\"/privacy\">privacy policy</a>.</p>", 503);
  }
  // The response never depends on whether the email is registered, throttled or undeliverable.
  const withinEmailLimit = await allowAttempt(env.RULES_DB, { key: await rateKey("forgot-email", email), limit: 5 }, HOUR);
  const account = withinEmailLimit
    ? await env.RULES_DB.prepare("SELECT id, email FROM account_users WHERE email = ? COLLATE NOCASE").bind(email).first<{ id: string; email: string }>()
    : null;
  if (account) {
    const recent = await env.RULES_DB.prepare("SELECT token_hash FROM account_password_resets WHERE user_id = ? AND created_at > ? LIMIT 1")
      .bind(account.id, new Date(Date.now() - MINUTE).toISOString()).first();
    if (!recent) {
      const token = randomHex(32);
      const tokenHash = await sha256(token);
      const now = new Date();
      await env.RULES_DB.prepare("INSERT INTO account_password_resets (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
        .bind(tokenHash, account.id, now.toISOString(), new Date(now.getTime() + RESET_LIFETIME_MS).toISOString()).run();
      const sent = await sendEmail(env, {
        to: account.email,
        subject: "Reset your Fishdays - NZ password",
        text: `Someone asked to reset the password for your Fishdays - NZ account. To choose a new password, open: ${authBase(env, url)}/reset-password?token=${token}\n\nThe link expires in 1 hour and can be used once. Resetting signs you out of every device. If you did not ask for this, ignore this email; your password will not change.`,
      });
      if (sent) {
        await env.RULES_DB.prepare("DELETE FROM account_password_resets WHERE user_id = ? AND token_hash != ?").bind(account.id, tokenHash).run();
      } else {
        await env.RULES_DB.prepare("DELETE FROM account_password_resets WHERE token_hash = ?").bind(tokenHash).run();
        console.error("Password reset email delivery failed");
      }
    }
  }
  return page("Check your inbox", `<p>${resetNotice}</p><p>If the email does not arrive, retry after one minute or use the support contact in our <a href="/privacy">privacy policy</a>.</p>`, 202);
}

function resetForm(token: string, problem = ""): string {
  return `${problem ? `<p role="alert"><strong>${escapeHtml(problem)}</strong></p>` : ""}<p>Choose a new password of ${PASSWORD_MIN}–${PASSWORD_MAX} characters. You will be signed out of every device.</p><form method="post" action="/reset-password"><input name="token" type="hidden" value="${token}"><label for="password">New password</label><input id="password" name="password" type="password" minlength="${PASSWORD_MIN}" maxlength="${PASSWORD_MAX}" required autocomplete="new-password"><label for="confirm">Repeat new password</label><input id="confirm" name="confirm" type="password" minlength="${PASSWORD_MIN}" maxlength="${PASSWORD_MAX}" required autocomplete="new-password"><button>Set new password</button></form>`;
}

const invalidLink = () => page("Reset link expired or used", "<p>Request a new link from the <a href=\"/forgot-password\">reset page</a>.</p>", 400);

async function resetPassword(request: Request, env: Env, url: URL): Promise<Response> {
  const form = request.method === "POST" ? await request.formData().catch(() => null) : null;
  const token = request.method === "GET" ? url.searchParams.get("token") || "" : String(form?.get("token") || "");
  if (!/^[a-f0-9]{64}$/.test(token)) return page("Invalid reset link", "<p>Request a new link from the <a href=\"/forgot-password\">reset page</a>.</p>", 400);
  const tokenHash = await sha256(token);
  const now = new Date().toISOString();
  const row = await env.RULES_DB.prepare(
    `SELECT u.id, u.email FROM account_password_resets r JOIN account_users u ON u.id = r.user_id
     WHERE r.token_hash = ? AND r.expires_at > ?`
  ).bind(tokenHash, now).first<{ id: string; email: string }>();
  if (!row) return invalidLink();
  // Opening the link only shows the form: mail scanners that follow links must not consume the token.
  if (request.method === "GET") return page("Choose a new password", resetForm(token));

  const password = form?.get("password");
  const problem = passwordProblem(password) || (password !== form?.get("confirm") ? "The two passwords do not match." : null);
  if (problem) return page("Choose a new password", resetForm(token, problem), 400);

  const salt = randomHex(16);
  const passwordHash = await hashPassword(String(password), salt);
  const valid = "(SELECT user_id FROM account_password_resets WHERE token_hash = ? AND expires_at > ?)";
  // One transaction: the token can only be spent once, and every old session and pending confirmation goes with it.
  // Receiving the link also proves the person controls the inbox, so the email counts as confirmed.
  const results = await env.RULES_DB.batch([
    env.RULES_DB.prepare(`UPDATE account_users SET password_hash = ?, password_salt = ?, email_verified = 1, updated_at = ? WHERE id = ${valid}`)
      .bind(passwordHash, salt, now, tokenHash, now),
    env.RULES_DB.prepare(`DELETE FROM account_sessions WHERE user_id = ${valid}`).bind(tokenHash, now),
    env.RULES_DB.prepare(`DELETE FROM account_email_verifications WHERE user_id = ${valid}`).bind(tokenHash, now),
    env.RULES_DB.prepare(`DELETE FROM account_password_resets WHERE user_id = ${valid}`).bind(tokenHash, now),
  ]);
  if (!results[0].meta.changes) return invalidLink();
  await notifyPasswordChanged(env, row.email, authBase(env, url));
  return page("Password updated", "<p>Your password has been changed and you were signed out of every device. Open Fishdays - NZ and sign in with the new password.</p>");
}

export type PasswordChangeResult = { ok: true } | { ok: false; status: number; error: string; code: string };

/** Signed-in password change. Other sessions end; the one making the change stays signed in. */
export async function changePassword(
  request: Request, env: Env, account: { id: string; email: string }, sessionToken: string, currentPassword: unknown, newPassword: unknown,
): Promise<PasswordChangeResult> {
  const problem = passwordProblem(newPassword);
  if (problem) return { ok: false, status: 400, error: problem, code: "invalid_password" };
  if (typeof currentPassword !== "string") return { ok: false, status: 400, error: "Enter your current password.", code: "invalid_password" };
  const failures = [{ key: await rateKey("password-change", account.id), limit: 5 }];
  if (await isRateLimited(env.RULES_DB, failures, 15 * MINUTE)) {
    return { ok: false, status: 429, error: "Too many incorrect passwords. Try again in a few minutes.", code: "rate_limited" };
  }
  const stored = await env.RULES_DB.prepare("SELECT password_hash, password_salt FROM account_users WHERE id = ?")
    .bind(account.id).first<{ password_hash: string; password_salt: string }>();
  if (!stored?.password_hash) {
    return { ok: false, status: 409, error: "This account has no password. Use “Forgot password” to create one.", code: "no_password" };
  }
  if (!await passwordMatches(currentPassword, stored)) {
    await recordAttempt(env.RULES_DB, failures.map((rule) => rule.key), 15 * MINUTE);
    return { ok: false, status: 401, error: "Your current password is incorrect.", code: "incorrect_password" };
  }
  if (currentPassword === newPassword) return { ok: false, status: 400, error: "Choose a password you are not already using.", code: "invalid_password" };
  const salt = randomHex(16);
  const passwordHash = await hashPassword(newPassword as string, salt);
  await env.RULES_DB.batch([
    env.RULES_DB.prepare("UPDATE account_users SET password_hash = ?, password_salt = ?, updated_at = ? WHERE id = ?")
      .bind(passwordHash, salt, new Date().toISOString(), account.id),
    env.RULES_DB.prepare("DELETE FROM account_sessions WHERE user_id = ? AND token_hash != ?").bind(account.id, await sha256(sessionToken)),
    env.RULES_DB.prepare("DELETE FROM account_password_resets WHERE user_id = ?").bind(account.id),
  ]);
  await notifyPasswordChanged(env, account.email, authBase(env, new URL(request.url)));
  return { ok: true };
}
