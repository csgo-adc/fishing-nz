CREATE TABLE account_oauth_identities (
  provider TEXT NOT NULL CHECK (provider IN ('google', 'apple')),
  subject TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (provider, subject),
  UNIQUE (user_id, provider)
);

CREATE TABLE account_oauth_flows (
  state_hash TEXT PRIMARY KEY,
  provider TEXT NOT NULL CHECK (provider IN ('google', 'apple')),
  nonce TEXT NOT NULL,
  pkce_verifier TEXT NOT NULL,
  exchange_secret_hash TEXT NOT NULL,
  return_uri TEXT NOT NULL,
  link_user_id TEXT REFERENCES account_users(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL
);

CREATE TABLE account_oauth_grants (
  code_hash TEXT PRIMARY KEY,
  exchange_secret_hash TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  linked INTEGER NOT NULL DEFAULT 0,
  expires_at TEXT NOT NULL
);

CREATE INDEX account_oauth_flows_expiry_idx ON account_oauth_flows(expires_at);
CREATE INDEX account_oauth_grants_expiry_idx ON account_oauth_grants(expires_at);
