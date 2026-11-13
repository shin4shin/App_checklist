import { createJwt, verifyGoogleToken } from './auth.js';

function json(data, status = 200) {
  return Response.json(data, {
    status,
    headers: {
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

export async function handleAuthGoogle(request, env, fetcher = fetch, now = Date.now()) {
  try {
    const body = await request.json().catch(() => null);
    if (!body || !body.idToken) {
      return json({ error: 'idToken is required' }, 400);
    }

    const { sub, email } = await verifyGoogleToken(body.idToken, fetcher);
    let user = await env.DB.prepare('SELECT id, google_sub, email FROM users WHERE google_sub = ?')
      .bind(sub)
      .first();

    const secret = env.JWT_SECRET;
    const thirtyDays = 30 * 24 * 60 * 60;

    if (!user) {
      const id = 'usr_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
      await env.DB.prepare('INSERT INTO users (id, google_sub, email, created_at, updated_at) VALUES (?, ?, ?, ?, ?)')
        .bind(id, sub, email, now, now)
        .run();
      user = { id, google_sub: sub, email };
    } else {
      await env.DB.prepare('UPDATE users SET email = ?, updated_at = ? WHERE id = ?')
        .bind(email, now, user.id)
        .run();
    }

    const payload = {
      sub: user.id,
      email: user.email,
      exp: Math.floor(now / 1000) + thirtyDays,
    };
    const token = await createJwt(payload, secret);

    return json({
      token,
      user: {
        id: user.id,
        email: user.email,
      },
    });
  } catch (err) {
    return json({ error: err.message || 'Authentication failed' }, 401);
  }
}

export async function handleSyncGet(request, env, user, now = Date.now()) {
  const url = new URL(request.url);
  const since = parseInt(url.searchParams.get('since') || '0', 10);
  const safeSince = Number.isNaN(since) ? 0 : since;

  const tasksStmt = env.DB.prepare(
    'SELECT package_name as packageName, category, task_id as taskId, title, done, deadline, routine_days as routineDays, reset_minute as resetMinute, updated_at as updatedAt, is_deleted as isDeleted FROM user_tasks WHERE user_id = ? AND updated_at > ?'
  ).bind(user.sub, safeSince);

  const settingsStmt = env.DB.prepare(
    'SELECT package_name as packageName, app_name as appName, app_icon as appIcon, app_installed as isInstalled, daily_reset_minute as dailyResetMinute, weekly_reset_day as weeklyResetDay, weekly_reset_minute as weeklyResetMinute, updated_at as updatedAt FROM user_settings WHERE user_id = ? AND updated_at > ?'
  ).bind(user.sub, safeSince);

  const tasksResult = await tasksStmt.all();
  const settingsResult = await settingsStmt.all();

  const tasks = (tasksResult.results || []).map(t => ({
    ...t,
    done: Boolean(t.done),
    isDeleted: Boolean(t.isDeleted),
  }));

  return json({
    tasks,
    settings: (settingsResult.results || []).map(setting => ({ ...setting, isInstalled: setting.isInstalled < 0 ? null : Boolean(setting.isInstalled) })),
    syncedAt: now,
  });
}

export async function handleSyncPost(request, env, user, now = Date.now()) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: 'Invalid JSON body' }, 400);

  const tasks = Array.isArray(body.tasks) ? body.tasks : [];
  const settings = Array.isArray(body.settings) ? body.settings : [];
  const since = Number.isSafeInteger(body.since) ? body.since : 0;

  const statements = [];
  for (const item of tasks) {
    if (!item.packageName || !item.category || !item.taskId || typeof item.title !== 'string') continue;
    const updatedAt = Number.isSafeInteger(item.updatedAt) ? item.updatedAt : now;
    const isDeleted = item.isDeleted ? 1 : 0;
    const done = item.done ? 1 : 0;
    const deadline = Number.isSafeInteger(item.deadline) ? item.deadline : 0;
    const routineDays = Number.isSafeInteger(item.routineDays) ? item.routineDays : 0;
    const resetMinute = Number.isSafeInteger(item.resetMinute) ? item.resetMinute : -1;

    statements.push(
      env.DB.prepare(`
        INSERT INTO user_tasks (
          user_id, package_name, category, task_id, title, done, deadline, routine_days, reset_minute, updated_at, is_deleted
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, package_name, category, task_id) DO UPDATE SET
          title = excluded.title,
          done = excluded.done,
          deadline = excluded.deadline,
          routine_days = excluded.routine_days,
          reset_minute = excluded.reset_minute,
          updated_at = excluded.updated_at,
          is_deleted = excluded.is_deleted
        WHERE excluded.updated_at > user_tasks.updated_at AND NOT (excluded.is_deleted = 1 AND user_tasks.is_deleted = 1)
      `).bind(
        user.sub,
        item.packageName,
        item.category,
        item.taskId,
        item.title,
        done,
        deadline,
        routineDays,
        resetMinute,
        updatedAt,
        isDeleted
      )
    );
  }

  for (const item of settings) {
    if (!item.packageName) continue;
    const updatedAt = Number.isSafeInteger(item.updatedAt) ? item.updatedAt : now;
    const dailyResetMinute = Number.isSafeInteger(item.dailyResetMinute) ? item.dailyResetMinute : 240;
    const weeklyResetDay = Number.isSafeInteger(item.weeklyResetDay) ? item.weeklyResetDay : 2;
    const weeklyResetMinute = Number.isSafeInteger(item.weeklyResetMinute) ? item.weeklyResetMinute : 240;

    statements.push(
      env.DB.prepare(`
        INSERT INTO user_settings (
          user_id, package_name, daily_reset_minute, weekly_reset_day, weekly_reset_minute, updated_at, app_name, app_icon, app_installed
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, package_name) DO UPDATE SET
          app_icon = CASE WHEN excluded.app_icon <> '' THEN excluded.app_icon ELSE user_settings.app_icon END,
          app_name = CASE WHEN excluded.app_name <> '' THEN excluded.app_name ELSE user_settings.app_name END,
          app_installed = CASE WHEN excluded.app_installed <> -1 AND excluded.updated_at >= user_settings.updated_at THEN excluded.app_installed ELSE user_settings.app_installed END,
          daily_reset_minute = CASE WHEN excluded.updated_at > user_settings.updated_at THEN excluded.daily_reset_minute ELSE user_settings.daily_reset_minute END,
          weekly_reset_day = CASE WHEN excluded.updated_at > user_settings.updated_at THEN excluded.weekly_reset_day ELSE user_settings.weekly_reset_day END,
          weekly_reset_minute = CASE WHEN excluded.updated_at > user_settings.updated_at THEN excluded.weekly_reset_minute ELSE user_settings.weekly_reset_minute END,
          updated_at = CASE WHEN excluded.updated_at > user_settings.updated_at THEN excluded.updated_at ELSE user_settings.updated_at END
        WHERE excluded.updated_at > user_settings.updated_at
          OR (user_settings.app_icon = '' AND excluded.app_icon <> '')
      `).bind(
        user.sub,
        item.packageName,
        dailyResetMinute,
        weeklyResetDay,
        weeklyResetMinute,
        updatedAt,
        typeof item.appName === 'string' ? item.appName : '',
        typeof item.appIcon === 'string' && item.appIcon.startsWith('data:image/png;base64,') && item.appIcon.length <= 65536 ? item.appIcon : '',
        typeof item.isInstalled === 'boolean' ? Number(item.isInstalled) : -1
      )
    );
  }

  if (statements.length > 0) {
    await env.DB.batch(statements);
  }

  await env.DB.prepare(`
    UPDATE user_tasks SET is_deleted = 1,
      updated_at = CASE WHEN updated_at >= ? THEN updated_at + 1 ELSE ? END
    WHERE user_id = ? AND (package_name, category, task_id) IN (
      SELECT package_name, category, task_id FROM (
        SELECT package_name, category, task_id,
          ROW_NUMBER() OVER (PARTITION BY package_name, TRIM(title) ORDER BY updated_at DESC, task_id ASC) AS duplicate_rank
        FROM user_tasks WHERE user_id = ? AND category = 'Daily' AND is_deleted = 0
          AND task_id <> '__app_completion__' AND TRIM(title) <> ''
      ) ranked WHERE duplicate_rank > 1
    )
  `).bind(now, now, user.sub, user.sub).run();

  // Fetch changes that are strictly newer than client's 'since' timestamp
  const serverTasksStmt = env.DB.prepare(
    'SELECT package_name as packageName, category, task_id as taskId, title, done, deadline, routine_days as routineDays, reset_minute as resetMinute, updated_at as updatedAt, is_deleted as isDeleted FROM user_tasks WHERE user_id = ? AND updated_at > ?'
  ).bind(user.sub, since);

  const serverSettingsStmt = env.DB.prepare(
    'SELECT package_name as packageName, app_name as appName, app_icon as appIcon, app_installed as isInstalled, daily_reset_minute as dailyResetMinute, weekly_reset_day as weeklyResetDay, weekly_reset_minute as weeklyResetMinute, updated_at as updatedAt FROM user_settings WHERE user_id = ? AND updated_at > ?'
  ).bind(user.sub, since);

  const serverTasks = await serverTasksStmt.all();
  const serverSettings = await serverSettingsStmt.all();

  const resultTasks = (serverTasks.results || []).map(t => ({
    ...t,
    done: Boolean(t.done),
    isDeleted: Boolean(t.isDeleted),
  }));

  return json({
    serverChanges: {
      tasks: resultTasks,
      settings: (serverSettings.results || []).map(setting => ({ ...setting, isInstalled: setting.isInstalled < 0 ? null : Boolean(setting.isInstalled) })),
    },
    syncedAt: now,
  });
}







