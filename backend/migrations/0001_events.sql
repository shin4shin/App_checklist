CREATE TABLE IF NOT EXISTS snapshots (
  game TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  fetched_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sync_status (
  game TEXT PRIMARY KEY,
  attempted_at INTEGER NOT NULL,
  error TEXT
);
