import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE, SUFFIX } from '../../src/support/env';
import { onboardCompany } from '../../src/support/company';

test.describe('Platform administration', () => {
  test('[PLAT-01] the overview shows companies, users and activity', async () => {
    const r = await (await apiAs('platform')).get('/api/identity/platform/summary');
    expect(r.status).toBe(200);
    const b = r.body;
    expect(JSON.stringify(b)).toMatch(/compan|org/i);
    expect(Object.values(b).some((v) => typeof v === 'number' && v > 0)).toBe(true);
  });

  test('[PLAT-02] the companies list shows plan, state, status and users', async () => {
    const list = rows(await (await apiAs('platform')).get('/api/identity/platform/orgs'));
    const names = list.map((o: any) => o.name);
    expect(names).toEqual(expect.arrayContaining(['PropertyOS Platform', 'Summit Ridge Property Group', 'Harborview Residential']));
    const summit = list.find((o: any) => o.name === 'Summit Ridge Property Group');
    expect(summit.status).toBe('ACTIVE');
    expect(summit.state).toBe('TX');
    expect(Number(summit.user_count)).toBeGreaterThan(5);
    expect(summit.plan ?? summit.plan_key).toBeTruthy();
  });

  test('[PLAT-03] onboarding creates a company, its admin and a one-time password', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const sfx = SUFFIX();
    const r = await platform.post('/api/identity/platform/orgs', { name: `Onboard ${sfx}`, state: 'CA', adminName: 'Olive Admin', adminEmail: `olive.${sfx}@e2e.test`, plan: 'GROWTH' });
    expect(r.status).toBe(200);
    expect(r.body.orgId).toBeTruthy();
    expect(r.body.temporaryPassword.length).toBeGreaterThanOrEqual(10);
    expect(r.body.defaultRoles).toBeGreaterThanOrEqual(8);
    const detail = (await platform.get(`/api/identity/platform/orgs/${r.body.orgId}`)).body;
    expect(detail.name).toBe(`Onboard ${sfx}`);
    expect(detail.status).toBe('ACTIVE');
    const dup = await platform.post('/api/identity/platform/orgs', { name: `Dup ${sfx}`, state: 'CA', adminName: 'Dup', adminEmail: `olive.${sfx}@e2e.test`, plan: 'GROWTH' });
    expect(dup.status).toBe(409);
  });

  test('[PLAT-04] onboarding validates name, admin email and plan', async () => {
    test.skip(!CAN_WRITE, 'writes to the platform');
    const platform = await apiAs('platform');
    expect((await platform.post('/api/identity/platform/orgs', { state: 'TX', adminName: 'A', adminEmail: 'a@e2e.test' })).status).toBe(400);
    expect((await platform.post('/api/identity/platform/orgs', { name: 'X', state: 'TX', adminName: 'A' })).status).toBe(400);
    const bad = await platform.post('/api/identity/platform/orgs', { name: 'X', state: 'TX', adminName: 'A', adminEmail: `a.${SUFFIX()}@e2e.test`, plan: 'NO_SUCH_PLAN' });
    expect(bad.status).toBe(400);
    expect(String(bad.body.detail)).toMatch(/plan/i);
    expect((await (await apiAs('admin')).post('/api/identity/platform/orgs', { name: 'X', state: 'TX', adminName: 'A', adminEmail: 'z@e2e.test' })).status).toBe(403);
  });

  test('[PLAT-05] plan and feature entitlements can be changed per company', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const before = (await platform.get(`/api/identity/platform/orgs/${c.orgId}/features`)).body;
    expect(JSON.stringify(before)).toContain('MAINTENANCE');
    const off = await platform.put(`/api/identity/platform/orgs/${c.orgId}/features`, { MAINTENANCE: false });
    expect(off.status).toBe(200);
    const blocked = await c.api.get('/api/maintenance/work-orders');
    expect(blocked.status).toBe(403);
    expect(blocked.body.code).toBe('FEATURE_OFF');
    const on = await platform.put(`/api/identity/platform/orgs/${c.orgId}/features`, { MAINTENANCE: true });
    expect(on.status).toBe(200);
    await expect.poll(async () => (await c.api.get('/api/maintenance/work-orders')).status, { timeout: 40_000, intervals: [1000] }).toBe(200);
    const plan = await platform.put(`/api/identity/platform/orgs/${c.orgId}`, { plan: 'GROWTH' });
    expect(plan.status).toBe(200);
    expect((await platform.get(`/api/identity/platform/orgs/${c.orgId}`)).body.plan ?? '').toMatch(/GROWTH/);
    const badPlan = await platform.put(`/api/identity/platform/orgs/${c.orgId}`, { plan: 'NOPE' });
    expect(badPlan.status).toBe(400);
  });

  test('[PLAT-06] a company admin cannot change its own entitlements', async () => {
    const admin = await apiAs('admin');
    const org = rows(await (await apiAs('platform')).get('/api/identity/platform/orgs')).find((o: any) => o.name === 'Summit Ridge Property Group');
    expect((await admin.put(`/api/identity/platform/orgs/${org.id}/features`, { MAINTENANCE: false })).status).toBe(403);
    expect((await admin.put(`/api/identity/platform/orgs/${org.id}`, { plan: 'STARTER' })).status).toBe(403);
    expect((await admin.get('/api/identity/platform/orgs')).status).toBe(403);
  });

  test('[PLAT-07] product defaults can be viewed, saved and reset per section', async () => {
    test.skip(!CAN_WRITE, 'changes product-wide defaults');
    const platform = await apiAs('platform');
    const view = (await platform.get('/api/identity/platform/defaults')).body;
    expect(view.changed).toEqual([]);
    expect(view.companies).toBeGreaterThan(2);
    expect(view.values.renewals).toBeTruthy();
    const saved = await platform.put('/api/identity/platform/defaults/renewals', { ...view.values.renewals, maxIncreasePercent: 7 });
    expect(saved.status).toBe(200);
    try {
      const after = (await platform.get('/api/identity/platform/defaults')).body;
      expect(after.changed).toContain('renewals');
      expect(after.values.renewals.maxIncreasePercent).toBe(7);
    } finally {
      expect((await platform.del('/api/identity/platform/defaults/renewals')).status).toBe(200);
    }
    const reset = (await platform.get('/api/identity/platform/defaults')).body;
    expect(reset.changed).toEqual([]);
    expect(reset.values.renewals.maxIncreasePercent).toBe(view.values.renewals.maxIncreasePercent);
  });

  test('[PLAT-08] invalid product-default sections are rejected', async () => {
    const platform = await apiAs('platform');
    expect((await platform.put('/api/identity/platform/defaults/bogus', {})).status).toBe(400);
    expect((await platform.del('/api/identity/platform/defaults/bogus')).status).toBe(400);
    expect((await (await apiAs('admin')).put('/api/identity/platform/defaults/renewals', {})).status).toBe(403);
  });

  test('[PLAT-09] a product default reaches every company\'s configuration unless the company changed it', async () => {
    test.skip(!CAN_WRITE, 'changes product-wide defaults');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const before = (await c.api.get('/api/identity/config')).body;
    const section = 'renewals';
    const current = before[section] ?? before.config?.[section];
    expect(current, 'config exposes the renewals section').toBeTruthy();
    await platform.put(`/api/identity/platform/defaults/${section}`, { ...current, maxIncreasePercent: 9 });
    try {
      const after = (await c.api.get('/api/identity/config')).body;
      expect((after[section] ?? after.config?.[section]).maxIncreasePercent).toBe(9);
      const summit = (await (await apiAs('admin')).get('/api/identity/config')).body;
      expect((summit[section] ?? summit.config?.[section]).maxIncreasePercent).toBe(9);
    } finally {
      await platform.del(`/api/identity/platform/defaults/${section}`);
    }
    const restored = (await c.api.get('/api/identity/config')).body;
    expect((restored[section] ?? restored.config?.[section]).maxIncreasePercent).toBe(current.maxIncreasePercent);
  });

  test('[PLAT-10] suspending a company needs a reason and a valid status', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const path = `/api/identity/platform/orgs/${c.orgId}`;
    expect((await platform.put(path, { status: 'SUSPENDED' })).status).toBe(400);
    expect((await platform.put(path, { status: 'PAUSED' })).status).toBe(400);
    const ok = await platform.put(path, { status: 'SUSPENDED', reason: 'Unpaid invoice' });
    expect(ok.status).toBe(200);
    const d = (await platform.get(path)).body;
    expect(d.status).toBe('SUSPENDED');
    expect(d.status_reason).toBe('Unpaid invoice');
    expect(d.status_changed_at).toBeTruthy();
    await platform.put(path, { status: 'ACTIVE' });
  });

  test('[PLAT-11] a suspended company\'s users cannot sign in', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    await platform.put(`/api/identity/platform/orgs/${c.orgId}`, { status: 'SUSPENDED', reason: 'Test' });
    const r = await (await Api.create()).post('/api/identity/public/login', { email: c.adminEmail, password: c.adminPassword });
    expect(r.status).toBe(403);
    expect(String(r.body.detail)).toMatch(/suspended/i);
    await platform.put(`/api/identity/platform/orgs/${c.orgId}`, { status: 'ACTIVE' });
    expect((await (await Api.create()).post('/api/identity/public/login', { email: c.adminEmail, password: c.adminPassword })).status).toBe(200);
  });

  test('[PLAT-12] [PLAT-14] a suspended company\'s signed-in users are turned away by every service, and let back in on reactivation', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const path = `/api/identity/platform/orgs/${c.orgId}`;
    await platform.put(path, { status: 'SUSPENDED', reason: 'Policy breach' });
    const services = ['/api/leasing/properties', '/api/maintenance/vendors', '/api/billing/invoices', '/api/workflow/rules', '/api/documents', '/api/identity/users'];
    for (const s of services) {
      await expect.poll(async () => (await c.api.get(s)).status, { message: s, timeout: 40_000, intervals: [1000] }).toBe(403);
      expect((await c.api.get(s)).body.code, s).toBe('ORG_SUSPENDED');
    }
    await platform.put(path, { status: 'ACTIVE' });
    for (const s of services) await expect.poll(async () => (await c.api.get(s)).status, { message: s, timeout: 40_000, intervals: [1000] }).toBe(200);
  });

  test('[PLAT-13] a suspended company\'s homes leave the public site and return on reactivation', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const owner = (await c.api.post('/api/leasing/owners', { name: `Owner ${c.sfx}` })).body.id;
    const prop = (await c.api.post('/api/leasing/properties', { ownerId: owner, name: `Home ${c.sfx}`, street: '1 Test St', city: 'Austin', state: 'TX', zip: '78701', type: 'MULTIFAMILY' })).body.id;
    const unit = (await c.api.post(`/api/leasing/properties/${prop}/units`, { number: '1', beds: 2, baths: '1', sqft: 800, marketRent: 1500 })).body.id;
    const listing = (await c.api.post('/api/leasing/listings', { unitId: unit })).body.id;
    await c.api.post(`/api/leasing/listings/${listing}/ai-assist`, {});
    await c.api.post(`/api/leasing/listings/${listing}/actions/SUBMIT`, {});
    await expect.poll(async () => (await c.api.get(`/api/leasing/listings/${listing}`)).body.status, { timeout: 60_000, intervals: [1000] }).toBe('LIVE');
    const pub = await Api.create();
    const visible = async () => rows(await pub.get('/api/leasing/public/listings')).some((l: any) => l.id === listing);
    expect(await visible()).toBe(true);
    await platform.put(`/api/identity/platform/orgs/${c.orgId}`, { status: 'SUSPENDED', reason: 'Test' });
    await expect.poll(visible, { timeout: 40_000, intervals: [1000] }).toBe(false);
    expect((await pub.get(`/api/leasing/public/listings/${listing}`)).status).toBe(404);
    await platform.put(`/api/identity/platform/orgs/${c.orgId}`, { status: 'ACTIVE' });
    await expect.poll(visible, { timeout: 40_000, intervals: [1000] }).toBe(true);
  });

  test('[PLAT-15] the platform itself cannot be suspended', async () => {
    test.skip(!CAN_WRITE, 'writes to the platform');
    const platform = await apiAs('platform');
    const me = rows(await platform.get('/api/identity/platform/orgs')).find((o: any) => o.name === 'PropertyOS Platform');
    expect((await platform.put(`/api/identity/platform/orgs/${me.id}`, { status: 'SUSPENDED', reason: 'x' })).status).toBe(400);
    expect((await platform.get('/api/identity/me')).status).toBe(200);
  });

  test('[PLAT-16] plans can be created, edited and deleted', async () => {
    test.skip(!CAN_WRITE, 'writes plans');
    const platform = await apiAs('platform');
    const key = `E2E_${SUFFIX().toUpperCase()}`;
    expect((await platform.post('/api/identity/platform/plans', { key: 'bad key', name: 'x', pricePerUnit: 1, minimumMonthly: 1, features: ['*'] })).status).toBe(400);
    const created = await platform.post('/api/identity/platform/plans', { key, name: 'E2E plan', pricePerUnit: 2.5, minimumMonthly: 100, features: ['LEASING'] });
    expect(created.status).toBe(200);
    expect(rows(created).some((p: any) => (p.key ?? p.plan_key) === key)).toBe(true);
    const edited = await platform.put(`/api/identity/platform/plans/${key}`, { name: 'E2E plan renamed' });
    expect(edited.status).toBe(200);
    expect(rows(await platform.get('/api/identity/platform/plans')).find((p: any) => (p.key ?? p.plan_key) === key).name).toBe('E2E plan renamed');
    expect((await platform.put(`/api/identity/platform/plans/${key}`, { features: ['NOT_A_FEATURE'] })).status).toBe(400);
    expect((await platform.del(`/api/identity/platform/plans/${key}`)).status).toBe(200);
    expect(rows(await platform.get('/api/identity/platform/plans')).some((p: any) => (p.key ?? p.plan_key) === key)).toBe(false);
    expect((await (await apiAs('admin')).post('/api/identity/platform/plans', {})).status).toBe(403);
  });

  test('[PLAT-17] website features and contact requests can be managed', async () => {
    test.skip(!CAN_WRITE, 'writes website content');
    const platform = await apiAs('platform');
    const pub = await Api.create();
    const sfx = SUFFIX();
    expect((await pub.post('/api/identity/public/contact', { name: 'A', email: 'bad', message: 'short' })).status).toBe(400);
    const sent = await pub.post('/api/identity/public/contact', { name: `Pat ${sfx}`, email: `pat.${sfx}@e2e.test`, message: 'I manage 120 doors and would like a demo please.', topic: 'DEMO', company: 'E2E Co', units: 120 });
    expect(sent.status).toBe(200);
    const list = rows(await platform.get('/api/identity/platform/site/contact-requests'));
    const mine = list.find((x: any) => String(x.email) === `pat.${sfx}@e2e.test`);
    expect(mine, 'request shows for the operator').toBeTruthy();
    expect((await platform.put(`/api/identity/platform/site/contact-requests/${mine.id}`, { status: 'CONTACTED' })).status).toBe(200);
    expect(rows(await platform.get('/api/identity/platform/site/contact-requests?status=CONTACTED')).some((x: any) => x.id === mine.id)).toBe(true);
    const features = rows(await platform.get('/api/identity/platform/site/features'));
    expect(features.length).toBeGreaterThan(3);
    const f = features[0];
    expect((await platform.put(`/api/identity/platform/site/features/${f.key ?? f.feature_key}`, { title: f.title })).status).toBe(200);
  });

  test('[PLAT-18] operator actions are audited', async () => {
    test.skip(!CAN_WRITE, 'changes product-wide defaults');
    const platform = await apiAs('platform');
    const v = (await platform.get('/api/identity/platform/defaults')).body.values.renewals;
    await platform.put('/api/identity/platform/defaults/renewals', { ...v, maxIncreasePercent: 5 });
    await platform.del('/api/identity/platform/defaults/renewals');
    const audit = rows(await platform.get('/api/identity/audit?limit=30'));
    const actions = audit.map((a: any) => a.action);
    expect(actions).toEqual(expect.arrayContaining(['PRODUCT_DEFAULT_UPDATED', 'PRODUCT_DEFAULT_RESET']));
    expect(audit.find((a: any) => a.action === 'PRODUCT_DEFAULT_UPDATED').actor_name).toBe('Alex Morgan');
  });
});
