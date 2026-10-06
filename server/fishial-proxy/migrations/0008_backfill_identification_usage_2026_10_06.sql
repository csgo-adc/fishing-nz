-- Carry successful identifications from earlier on the migration day into the
-- new per-account allowance. New Zealand was on NZDT (UTC+13) this day.
-- MAX preserves any quota reservations made after migration 0007 was deployed.
INSERT INTO account_identification_usage (user_id, day, used)
SELECT user_id, '2026-10-06', MIN(5, COUNT(*))
FROM account_events
WHERE event_name = 'fish_identity_used'
  AND user_id IS NOT NULL
  AND occurred_at >= '2026-10-05T11:00:00.000Z'
  AND occurred_at < '2026-10-06T11:00:00.000Z'
GROUP BY user_id
ON CONFLICT(user_id, day) DO UPDATE SET used = MAX(used, excluded.used);
