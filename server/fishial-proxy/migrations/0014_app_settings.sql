-- Settings the admin CMS changes without a deploy: the fish identification AI providers (src/fish-ai), their
-- encrypted API keys and last results, and the fishing rules reminders (src/rules-admin.ts). One JSON value per key.
CREATE TABLE IF NOT EXISTS app_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
