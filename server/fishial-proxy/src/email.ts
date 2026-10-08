export type EmailEnv = { RESEND_API_KEY?: string; ACCOUNT_EMAIL_FROM?: string };

/** Send a plain-text account email through Resend. Returns false when it is not configured or delivery fails. */
export async function sendEmail(env: EmailEnv, message: { to: string; subject: string; text: string }): Promise<boolean> {
  if (!env.RESEND_API_KEY || !env.ACCOUNT_EMAIL_FROM) return false;
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({ from: env.ACCOUNT_EMAIL_FROM, to: [message.to], subject: message.subject, text: message.text }),
  }).catch(() => null);
  return Boolean(response?.ok);
}
