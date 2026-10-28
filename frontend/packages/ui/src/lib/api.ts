// API client shared by all micro-frontends. The token lives in localStorage so the shell
// and every remote (bundled separately) see the same session without a shared singleton.

const TOKEN_KEY = 'pos.token';

declare global {
  interface Window {
    __PROPERTYOS_API__?: string;
  }
}

export function apiBase(): string {
  const fromWindow = typeof window !== 'undefined' ? window.__PROPERTYOS_API__ : undefined;
  return (fromWindow || (import.meta as any).env?.VITE_API_URL || 'http://localhost:8080').replace(/\/$/, '');
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage unavailable */
  }
  window.dispatchEvent(new CustomEvent('pos:session-changed'));
}

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Can’t reach the PropertyOS servers. Check your connection and try again.');
  }
  if (res.status === 401 && !path.includes('/public/')) {
    setToken(null);
    if (!location.pathname.startsWith('/signin') && location.pathname !== '/') location.assign('/signin?expired=1');
  }
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    const message = (data && (data.detail || data.title)) || `Request failed (${res.status})`;
    throw new ApiError(res.status, message, data?.code);
  }
  return data as T;
}

function safeJson(text: string) {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

export const api = {
  get: <T = any>(path: string) => request<T>('GET', path),
  post: <T = any>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T = any>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  del: <T = any>(path: string) => request<T>('DELETE', path),
};
