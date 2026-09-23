ALTER TABLE mpi_fishing_rules ADD COLUMN page_html TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS mpi_rules_crawl_status (
  area_id TEXT PRIMARY KEY,
  area_name TEXT NOT NULL,
  last_attempt_at TEXT NOT NULL,
  last_success_at TEXT,
  status TEXT NOT NULL,
  http_status INTEGER,
  error TEXT
);
