const DAILY_LIMIT = 5;
const TIME_ZONE = "Pacific/Auckland";

export function identificationDay(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-NZ", {
    timeZone: TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(now);
  const part = (type: string) => parts.find((value) => value.type === type)!.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}

function allowance(day: string, used: number) {
  return { limit: DAILY_LIMIT, used, remaining: Math.max(0, DAILY_LIMIT - used), day, time_zone: TIME_ZONE };
}

export async function identificationQuota(db: D1Database, userId: string, day = identificationDay()) {
  const row = await db.prepare("SELECT used FROM account_identification_usage WHERE user_id = ? AND day = ?")
    .bind(userId, day).first<{ used: number }>();
  return allowance(day, row?.used ?? 0);
}

export async function reserveIdentification(db: D1Database, userId: string, day = identificationDay()) {
  // One conditional write prevents simultaneous requests or devices exceeding the allowance.
  const row = await db.prepare(`INSERT INTO account_identification_usage (user_id, day, used) VALUES (?, ?, 1)
    ON CONFLICT(user_id, day) DO UPDATE SET used = used + 1 WHERE used < ? RETURNING used`)
    .bind(userId, day, DAILY_LIMIT).first<{ used: number }>();
  return row ? allowance(day, row.used) : null;
}

export async function releaseIdentification(db: D1Database, userId: string, day: string) {
  await db.prepare("UPDATE account_identification_usage SET used = MAX(0, used - 1) WHERE user_id = ? AND day = ?")
    .bind(userId, day).run();
}
