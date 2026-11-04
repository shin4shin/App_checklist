import { createJwt } from './auth.js';

function json(data, status = 200) {
  return Response.json(data, {
    status,
    headers: {
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    },
  });
}

export async function handleAuthDevice(request, env, now = Date.now()) {
  try {
    const body = await request.json().catch(() => null);
    if (!body || !body.deviceId || typeof body.deviceId !== 'string') {
      return json({ error: 'deviceId is required' }, 400);
    }

    const deviceSub = `device:${body.deviceId.trim()}`;
    let user = await env.DB.prepare('SELECT id, google_sub, email FROM users WHERE google_sub = ?')
      .bind(deviceSub)
      .first();

    const secret = env.JWT_SECRET;
    const thirtyDays = 30 * 24 * 60 * 60;

    if (!user) {
      const id = 'usr_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
      await env.DB.prepare('INSERT INTO users (id, google_sub, email, created_at, updated_at) VALUES (?, ?, ?, ?, ?)')
        .bind(id, deviceSub, 'device@dailycheck.app', now, now)
        .run();
      user = { id, google_sub: deviceSub, email: 'device@dailycheck.app' };
    }

    const payload = {
      sub: user.id,
      email: user.email,
      exp: Math.floor(now / 1000) + thirtyDays,
    };
    const token = await createJwt(payload, secret);

    return json({
      token,
      user: { id: user.id },
    });
  } catch (err) {
    return json({ error: err.message || 'Device authentication failed' }, 500);
  }
}

export async function handleGeneratePairingCode(request, env, user, now = Date.now()) {
  try {
    // 6-digit random code
    const rawNumber = Math.floor(100000 + Math.random() * 900000);
    const code = String(rawNumber);
    const expiresAt = now + 10 * 60 * 1000; // 10 minutes

    await env.DB.prepare(`
      INSERT INTO pairing_codes (code, user_id, created_at, expires_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(code) DO UPDATE SET
        user_id = excluded.user_id,
        created_at = excluded.created_at,
        expires_at = excluded.expires_at
    `).bind(code, user.sub, now, expiresAt).run();

    const formattedCode = `${code.slice(0, 3)}-${code.slice(3)}`;

    return json({
      code: formattedCode,
      rawCode: code,
      expiresAt,
    });
  } catch (err) {
    return json({ error: err.message || 'Failed to generate pairing code' }, 500);
  }
}

export async function handleVerifyPairingCode(request, env, now = Date.now()) {
  try {
    const body = await request.json().catch(() => null);
    if (!body || !body.code) {
      return json({ error: 'Code is required' }, 400);
    }

    const cleanCode = String(body.code).replace(/[^0-9]/g, '');
    if (cleanCode.length !== 6) {
      return json({ error: '6자리 숫자를 입력해 주세요' }, 400);
    }

    const record = await env.DB.prepare(
      'SELECT user_id, expires_at FROM pairing_codes WHERE code = ?'
    ).bind(cleanCode).first();

    if (!record || now > record.expires_at) {
      return json({ error: '유효하지 않거나 만료된 연동 코드입니다' }, 400);
    }

    // Clean up code
    await env.DB.prepare('DELETE FROM pairing_codes WHERE code = ?').bind(cleanCode).run();

    const secret = env.JWT_SECRET;
    const thirtyDays = 30 * 24 * 60 * 60;
    const payload = {
      sub: record.user_id,
      email: 'paired@dailycheck.app',
      exp: Math.floor(now / 1000) + thirtyDays,
    };
    const token = await createJwt(payload, secret);

    return json({
      token,
      user: { id: record.user_id },
    });
  } catch (err) {
    return json({ error: err.message || 'Pairing verification failed' }, 500);
  }
}
