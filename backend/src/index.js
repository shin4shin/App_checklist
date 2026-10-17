export const SOURCES = {
  zzz: { name: '젠레스 존 제로', url: 'https://api.ennead.cc/mihoyo/zenless/calendar?lang=ko-kr' },
  hsr: { name: '붕괴: 스타레일', url: 'https://api.ennead.cc/mihoyo/starrail/calendar?lang=ko-kr' },
};
const STALE_MS = 60 * 60 * 1000;

function requiredText(value, field) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`Invalid ${field}`);
  return value.trim();
}

function parseTime(value) {
  if (!Number.isSafeInteger(value) || value <= 0 || value > 253402300799) throw new Error('Invalid Unix timestamp');
  return new Date(value * 1000).toISOString();
}

export async function normalize(game, input) {
  if (!SOURCES[game] || !input || !Array.isArray(input.events) || input.events.length === 0) throw new Error('Empty or invalid event list');
  const ids = new Set();
  const events = input.events.map(item => {
    if (!Number.isSafeInteger(item.id) || item.id <= 0) throw new Error('Invalid event ID');
    const id = `${game}_ennead_${item.id}`;
    if (ids.has(id)) throw new Error('Duplicate event identity');
    ids.add(id);
    const startAt = parseTime(item.start_time);
    const endAt = parseTime(item.end_time);
    if (item.end_time <= item.start_time) throw new Error('End must follow start');
    return {
      id, game, version: null, title: requiredText(item.name, 'name'), language: 'ko-KR',
      description: typeof item.description === 'string' ? item.description : '',
      startAt, endAt, startTimeRaw: item.start_time, endTimeRaw: item.end_time,
      timeZoneStatus: 'resolved', source: 'HoYoverse API (Ennead)', sourceUrl: SOURCES[game].url,
    };
  });
  return { game, version: null, events };
}

async function readSource(url, fetcher) {
  const response = await fetcher(url, { signal: AbortSignal.timeout(15_000), headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`Upstream HTTP ${response.status}`);
  const reader = response.body.getReader();
  const parts = []; let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 1_000_000) { await reader.cancel(); throw new Error('Upstream body too large'); }
      parts.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let cursor = 0;
  for (const part of parts) { bytes.set(part, cursor); cursor += part.length; }
  return JSON.parse(new TextDecoder().decode(bytes));
}

export async function collect(env, fetcher = fetch, now = Date.now()) {
  return Promise.all(Object.entries(SOURCES).map(async ([game, source]) => {
    try {
      const payload = await normalize(game, await readSource(source.url, fetcher));
      await env.DB.batch([
        env.DB.prepare('INSERT INTO snapshots(game,payload,fetched_at) VALUES(?,?,?) ON CONFLICT(game) DO UPDATE SET payload=excluded.payload,fetched_at=excluded.fetched_at')
          .bind(game, JSON.stringify(payload), now),
        env.DB.prepare('INSERT INTO sync_status(game,attempted_at,error) VALUES(?,?,NULL) ON CONFLICT(game) DO UPDATE SET attempted_at=excluded.attempted_at,error=NULL')
          .bind(game, now),
      ]);
      return { game, ok: true, count: payload.events.length };
    } catch (error) {
      await env.DB.prepare('INSERT INTO sync_status(game,attempted_at,error) VALUES(?,?,?) ON CONFLICT(game) DO UPDATE SET attempted_at=excluded.attempted_at,error=excluded.error')
        .bind(game, now, String(error.message).slice(0, 200)).run();
      return { game, ok: false };
    }
  }));
}

function json(data, status = 200) {
  return Response.json(data, { status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

export async function handle(request, env, now = Date.now()) {
  if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
  const url = new URL(request.url);
  if (url.pathname !== '/v1/events' && url.pathname !== '/health') return json({ error: 'Not found' }, 404);
  const game = url.searchParams.get('game');
  if (game && !Object.hasOwn(SOURCES, game)) return json({ error: 'game must be zzz or hsr' }, 400);
  const state = url.searchParams.get('status') || 'all';
  if (!['all', 'active', 'upcoming', 'ended', 'unverified'].includes(state)) return json({ error: 'Invalid status' }, 400);
  const wanted = game ? [game] : Object.keys(SOURCES);
  const events = []; const sources = [];
  for (const key of wanted) {
    const snapshot = await env.DB.prepare('SELECT payload,fetched_at FROM snapshots WHERE game=?').bind(key).first();
    const sync = await env.DB.prepare('SELECT attempted_at,error FROM sync_status WHERE game=?').bind(key).first();
    sources.push({ game: key, available: !!snapshot,
      fetchedAt: snapshot ? new Date(snapshot.fetched_at).toISOString() : null,
      lastAttemptAt: sync ? new Date(sync.attempted_at).toISOString() : null,
      stale: !snapshot || now - snapshot.fetched_at > STALE_MS || !!sync?.error,
      lastError: sync?.error || null });
    if (snapshot) for (const event of JSON.parse(snapshot.payload).events) {
      const status = event.timeZoneStatus !== 'resolved' ? 'unverified'
        : now < Date.parse(event.startAt) ? 'upcoming' : now >= Date.parse(event.endAt) ? 'ended' : 'active';
      if (state === 'all' || state === status) events.push({ ...event, status, importable: status !== 'unverified' });
    }
  }
  const available = sources.some(s => s.available);
  if (url.pathname === '/health') return json({ status: sources.every(s => !s.stale) ? 'ok' : 'degraded', sources }, available ? 200 : 503);
  return json({ generatedAt: new Date(now).toISOString(), sources, events }, available ? 200 : 503);
}

export default {
  async fetch(request, env) {
    try { return await handle(request, env); }
    catch (error) { console.error('API storage failure', error); return json({ error: 'Service unavailable' }, 503); }
  },
  async scheduled(_controller, env) { console.log('Event sync', JSON.stringify(await collect(env))); },
};
