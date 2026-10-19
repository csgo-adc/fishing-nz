-- Trips and catch log entries a signed-in person chose to sync between their devices (More → Account → Sync trips and
-- catch log). Kind 'trips' is one document per account: saved fishing windows and the active trip as share-link tokens.
-- Kind 'catch' is one catch log entry each, without its photo. Rows go with the account (ON DELETE CASCADE and an
-- explicit delete), when sync is turned off with "delete the synced copy", and deleted entries after 180 days.
CREATE TABLE user_sync_items (
  user_id TEXT NOT NULL REFERENCES account_users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  item_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, kind, item_id)
) WITHOUT ROWID;
CREATE INDEX user_sync_items_deleted_idx ON user_sync_items(deleted, updated_at);
