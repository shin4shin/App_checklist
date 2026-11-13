CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  google_sub TEXT UNIQUE NOT NULL,
  email TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_tasks (
  user_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  category TEXT NOT NULL,
  task_id TEXT NOT NULL,
  title TEXT NOT NULL,
  done BIGINT NOT NULL DEFAULT 0,
  deadline BIGINT NOT NULL DEFAULT 0,
  routine_days BIGINT NOT NULL DEFAULT 0,
  reset_minute BIGINT NOT NULL DEFAULT -1,
  updated_at BIGINT NOT NULL,
  is_deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, package_name, category, task_id)
);

CREATE INDEX IF NOT EXISTS idx_user_tasks_updated ON user_tasks(user_id, updated_at);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  daily_reset_minute BIGINT NOT NULL DEFAULT 240,
  weekly_reset_day BIGINT NOT NULL DEFAULT 2,
  weekly_reset_minute BIGINT NOT NULL DEFAULT 240,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (user_id, package_name)
);

CREATE TABLE IF NOT EXISTS pairing_codes (
  code TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pairing_expires ON pairing_codes(expires_at);

ALTER TABLE user_settings ADD COLUMN app_name TEXT NOT NULL DEFAULT '';

ALTER TABLE user_settings ADD COLUMN app_icon TEXT NOT NULL DEFAULT '';
ALTER TABLE user_settings ADD COLUMN app_installed BIGINT NOT NULL DEFAULT -1;

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE pairing_codes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON users, user_tasks, user_settings, pairing_codes FROM anon, authenticated;

