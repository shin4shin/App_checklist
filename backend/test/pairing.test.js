import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { handle } from '../src/index.js';

function createDatabase() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../migrations/0001_events.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0002_sync.sql', import.meta.url), 'utf8'));
  db.exec(readFileSync(new URL('../migrations/0003_pairing.sql', import.meta.url), 'utf8'));

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

test('6-digit pairing code generation and verification links devices to same user', async () => {
  const env = { DB: createDatabase(), JWT_SECRET: 'test-pairing-secret' };
  const baseTime = Date.parse('2026-11-02T00:00:00Z');

  // 1. Mobile logs in anonymously as device
  const deviceRes = await handle(
    new Request('https://events.test/v1/auth/device', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: 'mobile-galaxy-s24' }),
    }),
    env,
    baseTime
  );
  assert.equal(deviceRes.status, 200);
  const mobileData = await deviceRes.json();
  const mobileToken = mobileData.token;
  const mobileUserId = mobileData.user.id;
  assert.ok(mobileToken);
  assert.ok(mobileUserId.startsWith('usr_'));

  // 2. Mobile generates 6-digit pairing code
  const codeRes = await handle(
    new Request('https://events.test/v1/auth/pair/code', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${mobileToken}`,
      },
    }),
    env,
    baseTime
  );
  assert.equal(codeRes.status, 200);
  const emptyGetRes = await handle(
    new Request('https://events.test/v1/auth/pair/code', {
      method: 'GET',
      headers: { Authorization: `Bearer ${mobileToken}` },
    }),
    env,
    baseTime
  );
  assert.equal(emptyGetRes.status, 200);
  const codeData = await codeRes.json();
  assert.equal(codeData.rawCode.length, 6);
  assert.ok(codeData.code.includes('-'));

  // 3. Desktop verifies 6-digit code
  const verifyRes = await handle(
    new Request('https://events.test/v1/auth/pair/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: codeData.code }), // supports formatted code
    }),
    env,
    baseTime + 1000
  );
  assert.equal(verifyRes.status, 200);
  const desktopData = await verifyRes.json();
  assert.ok(desktopData.token);
  // Verify that Desktop got the SAME user_id as Mobile!
  assert.equal(desktopData.user.id, mobileUserId);

  // 4. Code cannot be used twice
  const reuseRes = await handle(
    new Request('https://events.test/v1/auth/pair/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: codeData.rawCode }),
    }),
    env,
    baseTime + 2000
  );
  assert.equal(reuseRes.status, 400);
});
