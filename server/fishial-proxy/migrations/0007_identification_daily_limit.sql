CREATE TABLE IF NOT EXISTS account_identification_usage (
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,
  used INTEGER NOT NULL DEFAULT 0 CHECK (used BETWEEN 0 AND 5),
  PRIMARY KEY (user_id, day)
);
