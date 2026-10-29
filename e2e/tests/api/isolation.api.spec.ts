import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany, roleId } from '../../src/support/company';

test.describe('Tenant isolation', () => {
  test('[ISOL-01] a second company cannot read the first company\'s records', async () => {
    const summit = await apiAs('admin');
    const harbor = await apiAs('harbor');
    for (const path of ['/api/leasing/properties', '/api/leasing/units', '/api/leasing/owners', '/api/leasing/leases', '/api/leasing/applications', '/api/leasing/listings', '/api/maintenance/work-orders', '/api/maintenance/vendors', '/api/billing/invoices', '/api/billing/payments', '/api/documents']) {
      const a = new Set(rows(await summit.get(path)).map((r: any) => r.id));
      const b = rows(await harbor.get(path)).map((r: any) => r.id);
      expect(a.size, `${path}: Summit has data`).toBeGreaterThan(0);
      for (const id of b) expect(a.has(id), `${path}: ${id} leaked`).toBe(false);
    }
  });

  test('[ISOL-02] a second company cannot change or archive the first company\'s records', async () => {
    const summit = await apiAs('admin');
    const harbor = await apiAs('harbor');
    const prop = rows(await summit.get('/api/leasing/properties'))[0];
    const owner = rows(await summit.get('/api/leasing/owners'))[0];
    const vendor = rows(await summit.get('/api/maintenance/vendors'))[0];
    const unit = rows(await summit.get('/api/leasing/units'))[0];
    expect((await harbor.put(`/api/leasing/properties/${prop.id}`, { name: 'Stolen' })).status).toBe(404);
    expect((await harbor.del(`/api/leasing/properties/${prop.id}`)).status).toBe(404);
    expect((await harbor.put(`/api/leasing/owners/${owner.id}`, { phone: '1' })).status).toBe(404);
    expect((await harbor.del(`/api/leasing/owners/${owner.id}`)).status).toBe(404);
    expect((await harbor.put(`/api/maintenance/vendors/${vendor.id}`, { phone: '1' })).status).toBe(404);
    expect((await harbor.put(`/api/leasing/units/${unit.id}`, { marketRent: 1 })).status).toBe(404);
    const after = (await summit.get(`/api/leasing/properties/${prop.id}`)).body;
    expect(after.name).toBe(prop.name);
  });

  test('[ISOL-03] guessing an id from another company returns 404', async () => {
    const summit = await apiAs('admin');
    const harbor = await apiAs('harbor');
    const prop = rows(await summit.get('/api/leasing/properties'))[0];
    const lease = rows(await summit.get('/api/leasing/leases'))[0];
    const wo = rows(await summit.get('/api/maintenance/work-orders'))[0];
    const inv = rows(await summit.get('/api/billing/invoices'))[0];
    const doc = rows(await summit.get('/api/documents'))[0];
    expect((await harbor.get(`/api/leasing/properties/${prop.id}`)).status).toBe(404);
    expect((await harbor.get(`/api/leasing/leases/${lease.id}`)).status).toBe(404);
    expect((await harbor.get(`/api/maintenance/work-orders/${wo.id}`)).status).toBe(404);
    expect((await harbor.get(`/api/billing/invoices/${inv.id}`)).status).toBe(404);
    if (doc) expect((await harbor.get(`/api/documents/${doc.id}`)).status).toBe(404);
  });

  test('[ISOL-04] user and role lists are per company', async () => {
    const summitUsers = rows(await (await apiAs('admin')).get('/api/identity/users')).map((u: any) => u.email);
    const harborUsers = rows(await (await apiAs('harbor')).get('/api/identity/users')).map((u: any) => u.email);
    expect(summitUsers).toContain('sarah.mitchell@summitridgepg.com');
    expect(harborUsers).toContain('kevin.park@harborviewres.com');
    expect(harborUsers).not.toContain('sarah.mitchell@summitridgepg.com');
    expect(summitUsers).not.toContain('kevin.park@harborviewres.com');
    const sr = new Set(rows(await (await apiAs('admin')).get('/api/identity/roles')).map((r: any) => r.id));
    for (const r of rows(await (await apiAs('harbor')).get('/api/identity/roles'))) expect(sr.has(r.id)).toBe(false);
  });

  test('[ISOL-05] a role from another company cannot be assigned', async () => {
    const summit = await apiAs('admin');
    const harbor = await apiAs('harbor');
    const foreign = rows(await summit.get('/api/identity/roles'))[0].id;
    const r = await harbor.post('/api/identity/users', { email: `x.${Date.now()}@e2e.test`, name: 'Foreign Role', roleIds: [foreign] });
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(r.status).toBeLessThan(500);
  });

  test('[ISOL-06] a new company starts empty and inherits nothing', async () => {
    test.skip(!CAN_WRITE, 'onboards a company');
    const c = await onboardCompany();
    for (const path of ['/api/leasing/properties', '/api/leasing/units', '/api/leasing/owners', '/api/leasing/listings', '/api/leasing/leases', '/api/leasing/applications', '/api/maintenance/vendors', '/api/maintenance/work-orders', '/api/billing/invoices', '/api/billing/payments']) {
      expect(rows(await c.api.get(path)), path).toHaveLength(0);
    }
    const users = rows(await c.api.get('/api/identity/users'));
    expect(users).toHaveLength(1);
    expect(users[0].email).toBe(c.adminEmail);
    expect((await roleId(c.api, 'BROKER')).length).toBeGreaterThan(10);
    const summitProps = new Set(rows(await (await apiAs('admin')).get('/api/leasing/properties')).map((p: any) => p.id));
    expect(summitProps.size).toBeGreaterThan(0);
  });
});
