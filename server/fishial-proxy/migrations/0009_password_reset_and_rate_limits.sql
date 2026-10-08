CREATE TABLE account_password_resets (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX account_password_resets_user_idx ON account_password_resets(user_id);
CREATE INDEX account_password_resets_expiry_idx ON account_password_resets(expires_at);

CREATE TABLE rate_limit_counters (
  key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (key, window_start)
) WITHOUT ROWID;
CREATE INDEX rate_limit_counters_window_idx ON rate_limit_counters(window_start);
