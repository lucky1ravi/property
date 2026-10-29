import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { MODE } from '../../src/support/env';

const SERVICES = ['identity', 'leasing', 'billing', 'maintenance', 'workflow', 'ai', 'documents'];

test.describe('Operations', () => {
  test('[OPS-01] health, liveness and readiness probes answer', async () => {
    const pub = await Api.create();
    const h = await pub.get('/actuator/health');
    if (MODE === 'live' && h.status === 404) { test.info().annotations.push({ type: 'note', description: 'probes are not exposed through the public CDN' }); return; }
    expect(h.status).toBe(200);
    expect(h.body.status).toBe('UP');
    expect((await pub.get('/actuator/health/liveness')).body.status).toBe('UP');
    expect((await pub.get('/actuator/health/readiness')).body.status).toBe('UP');
    const env = await pub.get('/actuator/env');
    expect([401, 403, 404]).toContain(env.status); // sensitive endpoints are not exposed
  });

  test('[OPS-02] responses carry a correlation id and security headers', async () => {
    const pub = await Api.create();
    const r = await pub.get('/api/identity/public/options');
    expect(r.headers['x-correlation-id']).toMatch(/^[0-9a-f-]{8,}/i);
    expect(r.headers['x-content-type-options']).toContain('nosniff');
    expect(r.headers['x-frame-options']).toMatch(/DENY|SAMEORIGIN/);
    expect(r.headers['referrer-policy']).toBeTruthy();
    if (MODE !== 'local') expect(r.headers['strict-transport-security']).toContain('max-age');
    const mine = await pub.call('GET', '/api/identity/public/options', undefined, { 'X-Correlation-Id': 'e2e-trace-12345' });
    expect(mine.headers['x-correlation-id']).toContain('e2e-trace-12345');
    const err = await pub.get('/api/leasing/properties');
    expect(err.status).toBe(401);
    expect(err.headers['x-correlation-id']).toBeTruthy();
    expect(JSON.stringify(err.body)).not.toMatch(/Exception|at com\.|stack/i);
  });

  test('[OPS-03] the API is documented (OpenAPI per service)', async () => {
    const pub = await Api.create();
    for (const s of SERVICES) {
      const r = await pub.get(`/api/${s}/public/v3/api-docs`);
      expect(r.status, s).toBe(200);
      expect(r.body.openapi, s).toMatch(/^3\./);
      expect(Object.keys(r.body.paths).length, s).toBeGreaterThan(3);
      expect(r.body.info.title, s).toContain(s === 'documents' ? 'document' : s);
    }
    const all = await Promise.all(SERVICES.map(async (s) => Object.keys((await pub.get(`/api/${s}/public/v3/api-docs`)).body.paths).length));
    expect(all.reduce((a, b) => a + b, 0)).toBeGreaterThan(150);
  });

  test('[OPS-04] rate-limit headers are present', async () => {
    const pub = await Api.create();
    const r = await pub.get('/api/identity/public/options');
    expect(r.headers['x-ratelimit-remaining']).toBeDefined();
    const me = await (await apiAs('admin')).get('/api/identity/me');
    expect(me.headers['x-ratelimit-remaining']).toBeDefined();
  });

  test('[OPS-05] internal endpoints are not reachable from outside', async () => {
    const pub = await Api.create();
    const admin = await apiAs('admin');
    // Outside, an /internal path either fails or is answered by the web app's own page (never by a service).
    const notAService = (r: { status: number; body: any }) => r.status >= 400 || (typeof r.body === 'string' && /<!doctype html|<html/i.test(r.body));
    for (const path of ['/internal/leasing/occupancy', '/internal/anything', '/internal/identity/suspended-orgs', '/internal/ai/vision-status']) {
      expect(notAService(await pub.get(path)), path).toBe(true);
      expect(notAService(await admin.get(path)), `${path} signed in`).toBe(true);
    }
    for (const s of SERVICES) {
      const r = await admin.get(`/api/${s}/internal/x`);
      expect(r.status, s).toBeGreaterThanOrEqual(400);
    }
    expect(notAService(await pub.post('/internal/ai/moderate-image', {}))).toBe(true);
  });

  test('[OPS-06] unknown API paths and bad input return a clean error', async () => {
    const pub = await Api.create();
    const admin = await apiAs('admin');
    const nf = await admin.get('/api/leasing/does-not-exist');
    expect(nf.status).toBe(404);
    expect(JSON.stringify(nf.body)).not.toMatch(/Exception|at com\./);
    const bad = await admin.call('POST', '/api/leasing/owners', undefined, { 'content-type': 'application/json' });
    expect([400, 415]).toContain(bad.status);
    expect((await admin.get('/api/leasing/properties/not-a-uuid')).status).toBe(400);
    expect((await admin.get('/api/billing/payments/quote?amount=abc')).status).toBe(400);
    const wrongMethod = await admin.del('/api/identity/me');
    expect([404, 405]).toContain(wrongMethod.status);
    const fallback = await pub.get('/api/nope');
    expect(fallback.status).toBe(404);
    expect(rows(fallback)).toBeDefined();
  });
});
