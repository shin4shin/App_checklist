CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  google_sub TEXT UNIQUE NOT NULL,
  email TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_tasks (
  user_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  category TEXT NOT NULL,
  task_id TEXT NOT NULL,
  title TEXT NOT NULL,
  done INTEGER NOT NULL DEFAULT 0,
  deadline INTEGER NOT NULL DEFAULT 0,
  routine_days INTEGER NOT NULL DEFAULT 0,
  reset_minute INTEGER NOT NULL DEFAULT -1,
  updated_at INTEGER NOT NULL,
  is_deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, package_name, category, task_id)
);

CREATE INDEX IF NOT EXISTS idx_user_tasks_updated ON user_tasks(user_id, updated_at);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  daily_reset_minute INTEGER NOT NULL DEFAULT 240,
  weekly_reset_day INTEGER NOT NULL DEFAULT 2,
  weekly_reset_minute INTEGER NOT NULL DEFAULT 240,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, package_name)
);
