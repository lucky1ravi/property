import { request, type APIRequestContext } from '@playwright/test';
import { API_URL, DEMO_PASSWORD } from './env';
import { PERSONAS, type PersonaKey } from './personas';

export interface Reply { status: number; body: any; headers: Record<string, string> }

/** Thin JSON client for the gateway. Pass a token to act as that person; omit it for public calls. */
export class Api {
  constructor(private ctx: APIRequestContext, readonly token?: string) {}

  static async create(token?: string): Promise<Api> {
    return new Api(await request.newContext({ baseURL: API_URL, ignoreHTTPSErrors: true }), token);
  }

  as(token?: string): Api { return new Api(this.ctx, token); }

  async call(method: string, path: string, body?: unknown, extraHeaders: Record<string, string> = {}): Promise<Reply> {
    const headers: Record<string, string> = { ...extraHeaders };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    const r = await this.ctx.fetch(path, { method, headers, data: body === undefined ? undefined : body, failOnStatusCode: false, timeout: 60_000 });
    const text = await r.text();
    let parsed: any = text;
    try { parsed = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: r.status(), body: parsed, headers: r.headers() };
  }
  /** Uploads one file as multipart/form-data. */
  async upload(path: string, file: { name: string; mimeType: string; buffer: Buffer }, fields: Record<string, string> = {}, field = 'file'): Promise<Reply> {
    const headers: Record<string, string> = {};
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    const r = await this.ctx.fetch(path, { method: 'POST', headers, multipart: { ...fields, [field]: file }, failOnStatusCode: false, timeout: 60_000 });
    const text = await r.text();
    let parsed: any = text;
    try { parsed = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: r.status(), body: parsed, headers: r.headers() };
  }
  get(path: string) { return this.call('GET', path); }
  post(path: string, body: unknown = {}) { return this.call('POST', path, body); }
  put(path: string, body: unknown = {}) { return this.call('PUT', path, body); }
  del(path: string) { return this.call('DELETE', path); }
}

/** List endpoints answer a bare array, { rows: [...] } or { items: [...] }. */
export const rows = (r: Reply): any[] => (Array.isArray(r.body) ? r.body : Array.isArray(r.body?.rows) ? r.body.rows : Array.isArray(r.body?.items) ? r.body.items : []);

const tokens = new Map<string, Promise<string>>();

/** Signs in a seeded demo user over the API (cached for the run) and returns the token. */
export function tokenFor(key: PersonaKey): Promise<string> {
  const p = PERSONAS[key];
  if (!tokens.has(key)) {
    tokens.set(key, (async () => {
      const api = await Api.create();
      const r = await api.post('/api/identity/public/login', { email: p.email, password: DEMO_PASSWORD });
      if (r.status !== 200 || !r.body?.token) throw new Error(`Could not sign in ${p.email}: ${r.status} ${JSON.stringify(r.body)}`);
      return r.body.token as string;
    })());
  }
  return tokens.get(key)!;
}

export async function apiAs(key: PersonaKey): Promise<Api> { return Api.create(await tokenFor(key)); }
export async function publicApi(): Promise<Api> { return Api.create(); }
