CREATE TABLE IF NOT EXISTS mpi_fishing_rules (
  area_id TEXT PRIMARY KEY,
  area_name TEXT NOT NULL,
  source_url TEXT NOT NULL,
  page_title TEXT NOT NULL,
  reviewed_at TEXT,
  fetched_at TEXT NOT NULL,
  content_sha256 TEXT NOT NULL,
  page_text TEXT NOT NULL,
  sections_json TEXT NOT NULL,
  tables_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS mpi_fishing_rules_fetched_at_idx
  ON mpi_fishing_rules(fetched_at);
