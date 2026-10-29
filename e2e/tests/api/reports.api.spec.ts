import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';

test.describe('Dashboards, reports and deadlines', () => {
  test('[REPT-01] the home dashboard shows role-appropriate figures', async () => {
    const admin = await (await apiAs('admin')).get('/api/billing/summary');
    expect(admin.status).toBe(200);
    expect(JSON.stringify(admin.body)).toMatch(/\d/);
    const tenant = await apiAs('tenant');
    const mine = (await tenant.get('/api/billing/my')).body;
    expect(JSON.stringify(mine)).toMatch(/balance|due|invoice/i);
    const owner = await apiAs('owner');
    const ownerProps = rows(await owner.get('/api/leasing/properties'));
    const adminProps = rows(await (await apiAs('admin')).get('/api/leasing/properties'));
    expect(ownerProps.length).toBeGreaterThan(0);
    expect(ownerProps.length).toBeLessThan(adminProps.length);
    const vendor = await apiAs('vendor');
    expect(rows(await vendor.get('/api/maintenance/work-orders')).length).toBeGreaterThan(0);
    expect((await vendor.get('/api/billing/summary')).status).toBe(403);
  });

  test('[REPT-02] reports: aging, collections, tax and statements are available to finance roles only', async () => {
    const acc = await apiAs('accountant');
    for (const p of ['/api/billing/aging', '/api/billing/collections', '/api/billing/summary', '/api/billing/statements', '/api/billing/deposits', `/api/billing/tax/summary?year=${new Date().getFullYear()}`, `/api/billing/tax/1099?year=${new Date().getFullYear()}`]) {
      expect((await acc.get(p)).status, p).toBe(200);
    }
    const aging = (await acc.get('/api/billing/aging')).body;
    for (const k of ['d1_30', 'd31_60', 'd61_plus']) expect(aging).toHaveProperty(k);
    for (const k of ['tenant', 'vendor', 'agent'] as const) expect((await (await apiAs(k)).get('/api/billing/tax/1099')).status, k).toBe(403);
  });

  test('[REPT-03] profit opportunities are available to the allowed roles', async () => {
    const r = await (await apiAs('manager')).get('/api/ai/opportunities');
    expect(r.status).toBe(200);
    expect(JSON.stringify(r.body).length).toBeGreaterThan(10);
    expect((await (await apiAs('tenant')).get('/api/ai/opportunities')).status).toBe(403);
  });

  test('[REPT-04] the broker cockpit shows the work queue', async () => {
    const q = (await (await apiAs('broker')).get('/api/ai/broker/queue')).body;
    expect(q).toHaveProperty('queue');
    expect(q).toHaveProperty('needsYou');
    expect(q).toHaveProperty('briefing');
    expect(q.portfolio.units).toBeGreaterThan(0);
    for (const item of q.queue) { expect(item.title).toBeTruthy(); expect(item.path).toMatch(/^\//); }
    const other = (await (await apiAs('broker2')).get('/api/ai/broker/queue')).body;
    expect(other.portfolio.units).toBeGreaterThan(0);
    expect(JSON.stringify(other.queue)).not.toBe(JSON.stringify(q.queue));
  });

  test('[REPT-05] deadlines and alerts list from every service and can be run', async () => {
    const mgr = await apiAs('manager');
    for (const s of ['leasing', 'billing', 'maintenance', 'documents']) {
      const r = await mgr.get(`/api/${s}/deadlines`);
      expect([200, 404], s).toContain(r.status);
      if (r.status === 200) expect(Array.isArray(rows(r)) || Array.isArray(r.body)).toBe(true);
    }
    const lease = await (await apiAs('admin')).post('/api/leasing/alerts/run', {});
    expect(lease.status).toBe(200);
    expect((await (await apiAs('tenant')).post('/api/leasing/alerts/run', {})).status).toBe(403);
    expect((await (await Api.create()).get('/api/leasing/deadlines')).status).toBe(401);
  });
});
