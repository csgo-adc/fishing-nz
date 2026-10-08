CREATE TABLE analytics_devices (
  device_id TEXT PRIMARY KEY,
  user_id TEXT REFERENCES account_users(id) ON DELETE SET NULL,
  platform TEXT NOT NULL CHECK (platform IN ('ios', 'android', 'web')),
  os_version TEXT NOT NULL DEFAULT '',
  device_model TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  locale TEXT NOT NULL DEFAULT '',
  time_zone TEXT NOT NULL DEFAULT '',
  first_seen_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);
CREATE INDEX analytics_devices_user_idx ON analytics_devices(user_id);
CREATE INDEX analytics_devices_seen_idx ON analytics_devices(last_seen_at);

CREATE TABLE analytics_events (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES analytics_devices(device_id) ON DELETE CASCADE,
  user_id TEXT REFERENCES account_users(id) ON DELETE SET NULL,
  event_name TEXT NOT NULL,
  props_json TEXT NOT NULL DEFAULT '{}',
  occurred_at TEXT NOT NULL
);
CREATE INDEX analytics_events_device_idx ON analytics_events(device_id, occurred_at);
CREATE INDEX analytics_events_user_idx ON analytics_events(user_id, occurred_at);
CREATE INDEX analytics_events_name_idx ON analytics_events(event_name, occurred_at);
CREATE INDEX analytics_events_time_idx ON analytics_events(occurred_at);

CREATE TABLE api_usage_daily (
  day TEXT NOT NULL,
  user_id TEXT NOT NULL DEFAULT '',
  device_id TEXT NOT NULL DEFAULT '',
  route TEXT NOT NULL,
  calls INTEGER NOT NULL DEFAULT 0,
  errors INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, user_id, device_id, route)
) WITHOUT ROWID;
CREATE INDEX api_usage_user_idx ON api_usage_daily(user_id, day);
CREATE INDEX api_usage_device_idx ON api_usage_daily(device_id, day);
