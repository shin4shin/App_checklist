CREATE TABLE IF NOT EXISTS pairing_codes (
  code TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pairing_expires ON pairing_codes(expires_at);
