// Cloudflare Workers Web Crypto JWT & Google Auth

function base64UrlEncode(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlDecode(str) {
  let base64 = str.replace(/-/g, '+').replace(/_/g, '/');
  while (base64.length % 4) base64 += '=';
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

async function getHmacKey(secret) {
  const enc = new TextEncoder();
  return crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign', 'verify']
  );
}

export async function createJwt(payload, secret) {
  const header = { alg: 'HS256', typ: 'JWT' };
  const enc = new TextEncoder();
  const headerB64 = base64UrlEncode(enc.encode(JSON.stringify(header)));
  const payloadB64 = base64UrlEncode(enc.encode(JSON.stringify(payload)));
  const data = `${headerB64}.${payloadB64}`;

  const key = await getHmacKey(secret);
  const signature = await crypto.subtle.sign('HMAC', key, enc.encode(data));
  const sigB64 = base64UrlEncode(new Uint8Array(signature));

  return `${data}.${sigB64}`;
}

export async function verifyJwt(token, secret, now = Date.now()) {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const [headerB64, payloadB64, sigB64] = parts;
    const enc = new TextEncoder();
    const data = `${headerB64}.${payloadB64}`;

    const key = await getHmacKey(secret);
    const signature = base64UrlDecode(sigB64);
    const valid = await crypto.subtle.verify('HMAC', key, signature, enc.encode(data));
    if (!valid) return null;

    const payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(payloadB64)));
    if (payload.exp && (now / 1000) > payload.exp) return null;
    return payload;
  } catch {
    return null;
  }
}

export async function verifyGoogleToken(idToken, fetcher = fetch) {
  if (!idToken || typeof idToken !== 'string') throw new Error('Missing ID token');
  const response = await fetcher(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`);
  if (!response.ok) throw new Error('Invalid Google token');
  const info = await response.json();
  if (!info.sub || !info.email) throw new Error('Malformed Google token info');
  return {
    sub: info.sub,
    email: info.email,
    emailVerified: info.email_verified === 'true' || info.email_verified === true,
  };
}

export async function authenticateRequest(request, env, now = Date.now()) {
  const authHeader = request.headers.get('Authorization') || '';
  if (!authHeader.startsWith('Bearer ')) return null;
  const token = authHeader.slice(7).trim();
  const secret = env.JWT_SECRET;
  return verifyJwt(token, secret, now);
}
