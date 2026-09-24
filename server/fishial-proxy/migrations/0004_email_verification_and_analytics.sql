ALTER TABLE account_users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0 CHECK (email_verified IN (0, 1));
UPDATE account_users SET email_verified = 1;

CREATE TABLE IF NOT EXISTS account_email_verifications (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS account_email_verifications_user_id_idx ON account_email_verifications(user_id);
CREATE INDEX IF NOT EXISTS account_email_verifications_expires_at_idx ON account_email_verifications(expires_at);

CREATE TABLE IF NOT EXISTS account_events (
  id TEXT PRIMARY KEY,
  user_id TEXT REFERENCES account_users(id) ON DELETE SET NULL,
  event_name TEXT NOT NULL,
  feature TEXT,
  platform TEXT NOT NULL CHECK (platform IN ('web', 'ios', 'android')),
  occurred_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS account_events_occurred_at_idx ON account_events(occurred_at);
CREATE INDEX IF NOT EXISTS account_events_feature_idx ON account_events(feature, occurred_at);
