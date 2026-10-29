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

const OFFLINE = 'We can’t connect to PropertyOS right now. Check your internet connection and try again in a moment.';

/** Looks like a stack trace, class name, SQL or a raw status line rather than something written for a person. */
function technical(m: string) {
  return /exception|\bat [\w.$]+\(|com\.propertyos|org\.springframework|java\.|sql|jdbc|psql|null ?pointer|stack|undefined|ECONN|<html|<!doctype|error has been logged/i.test(m) || m.length > 300;
}

/** The message shown to a person. Server messages written for people (validation, rules) are kept; anything technical is replaced. */
export function friendlyMessage(status: number, serverMessage?: string | null): string {
  const m = (serverMessage ?? '').trim();
  const usable = m && !technical(m) && !/^request failed|^download failed|^upload failed|^internal server error$|^bad gateway$|^service unavailable$|^gateway timeout$/i.test(m);
  if (status === 0) return OFFLINE;
  if (status >= 500) return status === 502 || status === 503 || status === 504 ? 'PropertyOS is temporarily unavailable. Please try again in a few minutes.' : 'Something went wrong on our side. Please try again, and contact support if it keeps happening.';
  if (usable) return m;
  if (status === 401) return 'Your session has ended. Please sign in again.';
  if (status === 403) return 'You don’t have access to do this. Ask an administrator if you need it.';
  if (status === 404) return 'We couldn’t find that. It may have been moved or removed.';
  if (status === 409) return 'This conflicts with a recent change. Refresh the page and try again.';
  if (status === 413) return 'That file is too large.';
  if (status === 429) return 'You’re going a little fast. Wait a moment and try again.';
  return 'We couldn’t complete that. Please check what you entered and try again.';
}

/** Turns anything thrown into a sentence that is safe to show on screen. */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) return friendlyMessage(e.status, e.message);
  if (e instanceof TypeError || (e instanceof Error && /failed to fetch|networkerror|load failed/i.test(e.message))) return OFFLINE;
  if (e instanceof Error && e.message && !technical(e.message)) return e.message;
  return 'Something went wrong. Please try again.';
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
    throw new ApiError(0, OFFLINE);
  }
  if (res.status === 401 && !path.includes('/public/')) {
    setToken(null);
    if (!location.pathname.startsWith('/signin') && location.pathname !== '/') location.assign('/signin?expired=1');
  }
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    throw new ApiError(res.status, friendlyMessage(res.status, data && (data.detail || data.title)), data?.code);
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

/** Multipart upload (files). The browser sets the multipart boundary itself. */
async function upload<T>(path: string, form: FormData): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, { method: 'POST', headers, body: form });
  } catch {
    throw new ApiError(0, OFFLINE);
  }
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) throw new ApiError(res.status, friendlyMessage(res.status, data && (data.detail || data.title)), data?.code);
  return data as T;
}

/** Fetches a file with the session token and returns it with its file name (from Content-Disposition). */
async function blob(method: 'GET' | 'POST', path: string, body?: unknown): Promise<{ blob: Blob; fileName: string }> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, OFFLINE);
  }
  if (!res.ok) {
    const t = await res.text();
    const d = t ? safeJson(t) : null;
    throw new ApiError(res.status, friendlyMessage(res.status, d && (d.detail || d.title)));
  }
  const cd = res.headers.get('Content-Disposition') ?? '';
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  const fileName = star ? decodeURIComponent(star[1]) : plain ? plain[1] : 'download';
  return { blob: await res.blob(), fileName };
}

/** Saves a Blob as a file in the browser. */
export function saveBlob(b: Blob, fileName: string) {
  const url = URL.createObjectURL(b);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

export const api = {
  upload: <T = any>(path: string, form: FormData) => upload<T>(path, form),
  blob: (path: string) => blob('GET', path),
  postBlob: (path: string, body?: unknown) => blob('POST', path, body ?? {}),
  get: <T = any>(path: string) => request<T>('GET', path),
  post: <T = any>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T = any>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  del: <T = any>(path: string) => request<T>('DELETE', path),
};
