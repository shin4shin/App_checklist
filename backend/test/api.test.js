import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { collect, handle, normalize } from '../src/index.js';

function database() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../migrations/0001_events.sql', import.meta.url), 'utf8'));
  return {
    prepare(sql) {
      return { bind(...args) {
        return { run: async () => db.prepare(sql).run(...args), first: async () => db.prepare(sql).get(...args) || null };
      } };
    },
    async batch(statements) {
      db.exec('BEGIN');
      try { const results = statements.map(s => s.run()); db.exec('COMMIT'); return Promise.all(results); }
      catch (e) { db.exec('ROLLBACK'); throw e; }
    },
  };
}
const fixture = () => ({ events: [
  { id: 123, name: '한국어 이벤트', description: 'fixture', start_time: 1790848800, end_time: 1792897199 },
] });
const now = Date.parse('2026-10-17T00:00:00Z');

test('Korean titles and Unix seconds preserve absolute time', async () => {
  const event = (await normalize('zzz', fixture())).events[0];
  assert.equal(event.title, '한국어 이벤트');
  assert.equal(event.language, 'ko-KR');
  assert.equal(Date.parse(event.endAt), fixture().events[0].end_time * 1000);
  assert.equal(event.timeZoneStatus, 'resolved');
});

test('title and date corrections keep IDs; invalid times and duplicate IDs are rejected', async () => {
  const data = fixture();
  const original = (await normalize('hsr', data)).events[0].id;
  data.events[0].name = '수정된 제목';
  data.events[0].end_time += 3600;
  assert.equal((await normalize('hsr', data)).events[0].id, original);
  for (const invalid of [null, '1792897199', 0, 1792897199000, 1.5]) {
    data.events[0].end_time = invalid;
    await assert.rejects(normalize('hsr', data));
  }
  const duplicate = fixture(); duplicate.events.push(duplicate.events[0]);
  await assert.rejects(normalize('hsr', duplicate));
  await assert.rejects(normalize('hsr', { events: [] }));
});

test('failed source retains old snapshot while other game updates independently', async () => {
  const env = { DB: database() };
  await collect(env, async () => Response.json(fixture()), now);
  await collect(env, async url => url.includes('zenless') ? new Response('bad', { status: 502 }) : Response.json(fixture()), now + 600000);
  const response = await handle(new Request('https://events.test/v1/events'), env, now + 600000);
  const result = await response.json();
  assert.equal(result.events.length, 2);
  assert.equal(result.sources[0].fetchedAt, new Date(now).toISOString());
  assert.equal(result.sources[0].stale, true);
  assert.equal(result.sources[1].stale, false);
  assert.equal(result.events.find(e => e.game === 'zzz').status, 'active');
  assert.equal(result.events.find(e => e.game === 'hsr').importable, true);
});

test('empty cache returns 503; filters, expiry, method and game validation work', async () => {
  const env = { DB: database() };
  assert.equal((await handle(new Request('https://events.test/v1/events'), env, now)).status, 503);
  await collect(env, async () => Response.json(fixture()), now);
  const result = await (await handle(new Request('https://events.test/v1/events?game=zzz&status=active'), env, now)).json();
  assert.equal(result.events.length, 1);
  const expired = await (await handle(new Request('https://events.test/v1/events?status=ended'), env, Date.parse('2026-11-01T00:00:00Z'))).json();
  assert.equal(expired.events.length, 2);
  assert.equal((await handle(new Request('https://events.test/v1/events?game=bad'), env)).status, 400);
  assert.equal((await handle(new Request('https://events.test/v1/events', { method: 'POST' }), env)).status, 405);
});

test('malformed successful response cannot destroy the last good snapshot', async () => {
  const env = { DB: database() };
  await collect(env, async () => Response.json(fixture()), now);
  await collect(env, async () => Response.json({ events: [] }), now + 1);
  const result = await (await handle(new Request('https://events.test/v1/events'), env, now + 1)).json();
  assert.equal(result.events.length, 2);
  assert.ok(result.sources.every(s => s.stale));
});
