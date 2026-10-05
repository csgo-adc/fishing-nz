CREATE TABLE account_deletion_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX account_deletion_tokens_user_idx ON account_deletion_tokens(user_id);
CREATE INDEX account_deletion_tokens_expiry_idx ON account_deletion_tokens(expires_at);
