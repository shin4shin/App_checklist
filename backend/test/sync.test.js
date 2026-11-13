import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { handle } from '../src/index.js';
import { createJwt } from '../src/auth.js';
import { handleSyncPost } from '../src/sync.js';

function createDatabase() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../migrations/0001_events.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0002_sync.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0004_app_names.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0005_app_icons.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0006_app_installed.sql', import.meta.url), 'utf8'));

  return {
    prepare(sql) {
      return {
        bind(...args) {
          return {
            run: async () => db.prepare(sql).run(...args),
            first: async () => db.prepare(sql).get(...args) || null,
            all: async () => ({ results: db.prepare(sql).all(...args) }),
          };
        },
      };
    },
    async batch(statements) {
      db.exec('BEGIN');
      try {
        const results = statements.map(s => s.run());
        db.exec('COMMIT');
        return Promise.all(results);
      } catch (e) {
        db.exec('ROLLBACK');
        throw e;
      }
    },
  };
}

const mockFetcher = async url => {
  if (url.includes('tokeninfo')) {
    const parsed = new URL(url);
    const token = parsed.searchParams.get('id_token');
    if (token === 'valid_google_token') {
      return Response.json({
        sub: 'google_sub_12345',
        email: 'user@example.com',
        email_verified: 'true',
      });
    }
    return new Response(JSON.stringify({ error: 'invalid_token' }), { status: 400 });
  }
  return new Response('Not found', { status: 404 });
};

test('Google authentication flow issues valid JWT token', async () => {
  const env = { DB: createDatabase(), JWT_SECRET: 'test-secret' };
  const req = new Request('https://events.test/v1/auth/google', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ idToken: 'valid_google_token' }),
  });

  const res = await handle(req, env, Date.now(), mockFetcher);
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.ok(data.token);
  assert.equal(data.user.email, 'user@example.com');
  assert.ok(data.user.id.startsWith('usr_'));

  // Invalid token check
  const badReq = new Request('https://events.test/v1/auth/google', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ idToken: 'invalid_token' }),
  });
  const badRes = await handle(badReq, env, Date.now(), mockFetcher);
  assert.equal(badRes.status, 401);
});

test('Sync push and pull updates tasks and settings with timestamps', async () => {
  const env = { DB: createDatabase(), JWT_SECRET: 'test-secret' };
  const baseTime = Date.parse('2026-11-01T12:00:00Z');
  const authRes = await handle(
    new Request('https://events.test/v1/auth/google', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken: 'valid_google_token' }),
    }),
    env,
    baseTime,
    mockFetcher
  );
  const { token, user } = await authRes.json();
  const authHeader = { Authorization: `Bearer ${token}` };

  // 1. Initial Sync Push
  const pushReq = new Request('https://events.test/v1/sync', {
    method: 'POST',
    headers: { ...authHeader, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      since: 0,
      tasks: [
        {
          packageName: 'com.hoyoverse.nap',
          category: 'Daily',
          taskId: 'daily_coffee',
          title: '카페 커피 마시기',
          done: false,
          deadline: 0,
          routineDays: 0,
          resetMinute: 240,
          updatedAt: baseTime + 50,
          isDeleted: false,
        },
        {
          packageName: 'com.hoyoverse.hkrpg',
          category: 'Weekly',
          taskId: 'weekly_simulated',
          title: '시뮬레이션 우주',
          done: false,
          deadline: 0,
          routineDays: 0,
          resetMinute: 240,
          updatedAt: baseTime + 50,
          isDeleted: false,
        },
      ],
      settings: [
        {
          packageName: 'com.hoyoverse.nap',
          dailyResetMinute: 240,
          weeklyResetDay: 2,
          weeklyResetMinute: 240,
          updatedAt: baseTime + 50,
        },
      ],
    }),
  });

  const pushRes = await handle(pushReq, env, baseTime + 100, mockFetcher);
  assert.equal(pushRes.status, 200);

  // 2. Sync Pull (Another client fetching state)
  const pullReq = new Request('https://events.test/v1/sync?since=0', {
    method: 'GET',
    headers: authHeader,
  });
  const pullRes = await handle(pullReq, env, baseTime + 200, mockFetcher);
  assert.equal(pullRes.status, 200);
  const pullData = await pullRes.json();
  assert.equal(pullData.tasks.length, 2);
  assert.equal(pullData.settings.length, 1);
  assert.equal(pullData.tasks[0].title, '카페 커피 마시기');
  assert.equal(pullData.tasks[0].done, false);

  // 3. Mark done from another client
  const updateReq = new Request('https://events.test/v1/sync', {
    method: 'POST',
    headers: { ...authHeader, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      since: baseTime + 50,
      tasks: [
        {
          packageName: 'com.hoyoverse.nap',
          category: 'Daily',
          taskId: 'daily_coffee',
          title: '카페 커피 마시기',
          done: true,
          updatedAt: baseTime + 300,
        },
      ],
    }),
  });
  const updateRes = await handle(updateReq, env, baseTime + 350, mockFetcher);
  assert.equal(updateRes.status, 200);

  // 4. Verify incremental pull since baseTime + 200 only gets the updated task
  const incPullReq = new Request(`https://events.test/v1/sync?since=${baseTime + 200}`, {
    method: 'GET',
    headers: authHeader,
  });
  const incData = await (await handle(incPullReq, env, baseTime + 400, mockFetcher)).json();
  assert.equal(incData.tasks.length, 1);
  assert.equal(incData.tasks[0].taskId, 'daily_coffee');
  assert.equal(incData.tasks[0].done, true);
});

test('older icon backfill preserves newer reset settings and timestamp', async () => {
  const env = { DB: createDatabase() };
  await env.DB.prepare('INSERT INTO users (id, google_sub, email, created_at, updated_at) VALUES (?, ?, ?, ?, ?)').bind('icon-test', 'icon-test', 'test@example.com', 1, 1).run();
  const send = async setting => {
    const response = await handleSyncPost(new Request('https://events.test/v1/sync', {
      method: 'POST', body: JSON.stringify({ settings: [setting], since: 0 }),
    }), env, { sub: 'icon-test' });
    assert.equal(response.status, 200);
    return (await response.json()).serverChanges.settings[0];
  };
  await send({ packageName: 'test.game', dailyResetMinute: 300, weeklyResetMinute: 360, weeklyResetDay: 4, updatedAt: 200, isInstalled: false });
  const result = await send({ packageName: 'test.game', dailyResetMinute: 240, weeklyResetMinute: 240, weeklyResetDay: 2, updatedAt: 100, appIcon: 'data:image/png;base64,test' });
  assert.equal(result.appIcon, 'data:image/png;base64,test');
  assert.equal(result.dailyResetMinute, 300);
  assert.equal(result.weeklyResetMinute, 360);
  assert.equal(result.weeklyResetDay, 4);
  assert.equal(result.updatedAt, 200);
  assert.equal(result.isInstalled, false);
  const installed = await send({ packageName: 'test.game', updatedAt: 300, isInstalled: true });
  assert.equal(installed.isInstalled, true);
});

test('same daily title keeps the latest record and syncs a tombstone without merging other categories', async () => {
  const env = { DB: createDatabase() };
  await env.DB.prepare('INSERT INTO users (id, google_sub, email, created_at, updated_at) VALUES (?, ?, ?, ?, ?)').bind('duplicate-test', 'duplicate-test', 'test@example.com', 1, 1).run();
  const tasks = [
    { category: 'Daily', taskId: 'old', updatedAt: 10 },
    { category: 'Daily', taskId: 'new', updatedAt: 20, done: true },
    { category: 'Event', taskId: 'event-1', updatedAt: 10 },
    { category: 'Event', taskId: 'event-2', updatedAt: 20 },
  ].map(task => ({ packageName: 'test.game', title: '일일퀘스트', ...task }));
  const send = async items => (await handleSyncPost(new Request('https://events.test/v1/sync', {
    method: 'POST', body: JSON.stringify({ tasks: items, since: 0 }),
  }), env, { sub: 'duplicate-test' }, 100)).json();
  const first = (await send(tasks)).serverChanges.tasks;
  assert.equal(first.find(task => task.taskId === 'old').isDeleted, true);
  assert.equal(first.find(task => task.taskId === 'new').done, true);
  assert.equal(first.filter(task => task.category === 'Event' && !task.isDeleted).length, 2);
  assert.equal((await send([])).serverChanges.tasks.find(task => task.taskId === 'old').updatedAt, 100);
});

test('Unauthenticated sync requests are rejected with 401', async () => {
  const env = { DB: createDatabase(), JWT_SECRET: 'test-secret' };
  const res = await handle(new Request('https://events.test/v1/sync'), env);
  assert.equal(res.status, 401);
});


