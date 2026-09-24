CREATE TABLE IF NOT EXISTS account_users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  country_code TEXT NOT NULL DEFAULT 'NZ',
  plan TEXT NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'paid')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS account_sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS account_sessions_user_id_idx ON account_sessions(user_id);
CREATE INDEX IF NOT EXISTS account_sessions_expires_at_idx ON account_sessions(expires_at);

CREATE TABLE IF NOT EXISTS account_feedback (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  category TEXT NOT NULL CHECK (category IN ('general', 'bug', 'idea')),
  message TEXT NOT NULL,
  rating INTEGER CHECK (rating IS NULL OR (rating >= 1 AND rating <= 5)),
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS account_feedback_created_at_idx ON account_feedback(created_at);
