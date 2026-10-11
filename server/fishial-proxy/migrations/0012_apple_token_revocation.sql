-- Apple sign-in keeps Apple's refresh token, and the client ID it was issued to, so deleting the account can revoke it
-- (App Store Review Guideline 5.1.1(v)). Google identities leave both empty.
ALTER TABLE account_oauth_identities ADD COLUMN refresh_token TEXT;
ALTER TABLE account_oauth_identities ADD COLUMN token_client_id TEXT;
