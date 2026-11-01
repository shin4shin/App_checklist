// Sync API Client for Daily Check Desktop Widget

const API_BASE = 'https://events.yulda.kr'; // fallback to production worker
// In local dev, can be switched to http://localhost:8787

export const syncApi = {
  getStoredAuth() {
    const raw = localStorage.getItem('daily_check_auth');
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  },

  setStoredAuth(auth) {
    if (!auth) localStorage.removeItem('daily_check_auth');
    else localStorage.setItem('daily_check_auth', JSON.stringify(auth));
  },

  async loginWithGoogleToken(idToken, baseUrl = API_BASE) {
    const res = await fetch(`${baseUrl}/v1/auth/google`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || 'Google login failed');
    }
    const data = await res.json();
    this.setStoredAuth({ token: data.token, user: data.user });
    return data;
  },

  async verifyPairingCode(code, baseUrl = API_BASE) {
    const res = await fetch(`${baseUrl}/v1/auth/pair/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || '연동 코드 검증 실패');
    }
    const data = await res.json();
    this.setStoredAuth({ token: data.token, user: data.user });
    return data;
  },

  async sync({ tasks = [], settings = [], since = 0 }, baseUrl = API_BASE) {
    const auth = this.getStoredAuth();
    if (!auth?.token) throw new Error('Not logged in');

    const res = await fetch(`${baseUrl}/v1/sync`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${auth.token}`,
      },
      body: JSON.stringify({ tasks, settings, since }),
    });

    if (res.status === 401) {
      this.setStoredAuth(null);
      throw new Error('Session expired');
    }

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || 'Sync failed');
    }

    return res.json();
  },

  async fetchEvents(game = null, baseUrl = API_BASE) {
    const url = game ? `${baseUrl}/v1/events?game=${game}` : `${baseUrl}/v1/events`;
    const res = await fetch(url);
    if (!res.ok) return { events: [] };
    return res.json();
  },
};
