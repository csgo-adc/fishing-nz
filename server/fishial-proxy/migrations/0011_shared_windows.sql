-- Short links for shared fishing windows (/w/<id>). Each row is the window's link token, which holds the place, times and
-- forecast summary and nothing about the person who shared it. Rows expire after 60 days and the daily cleanup deletes them.
CREATE TABLE shared_windows (
  id TEXT PRIMARY KEY,
  token TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
) WITHOUT ROWID;
CREATE INDEX shared_windows_expiry_idx ON shared_windows(expires_at);
