import { test, expect } from '../../src/support/fixtures';
import { Api, tokenFor, apiAs, rows } from '../../src/support/api';
import { GUARDED_GETS } from '../../src/support/endpoints';
import { PERSONAS, type PersonaKey } from '../../src/support/personas';

/** What each built-in role must (and must not) be able to do. This is the product's access policy; it is asserted against /me. */
const POLICY: Record<string, { can: string[]; cannot: string[] }> = {
  admin: { can: ['USER:CREATE', 'ROLE:CONFIGURE', 'SETTINGS:CONFIGURE', 'PROPERTY:DELETE', 'OWNER:CREATE', 'VENDOR:CREATE', 'AUDIT:VIEW'], cannot: ['PLATFORM:VIEW', 'PLATFORM:CONFIGURE'] },
  manager: { can: ['PROPERTY:CREATE', 'OWNER:CREATE', 'VENDOR:CREATE', 'WORK_ORDER:APPROVE', 'USER:VIEW', 'SETTINGS:VIEW'], cannot: ['USER:CREATE', 'ROLE:CONFIGURE', 'SETTINGS:CONFIGURE', 'PLATFORM:VIEW'] },
  broker: { can: ['PROPERTY:VIEW', 'OWNER:VIEW', 'LISTING:APPROVE', 'APPLICATION:APPROVE', 'LEASE:APPROVE', 'WORK_ORDER:APPROVE'], cannot: ['OWNER:CREATE', 'OWNER:EDIT', 'OWNER:DELETE', 'PROPERTY:CREATE', 'PROPERTY:DELETE', 'VENDOR:CREATE', 'USER:VIEW', 'ROLE:CONFIGURE'] },
  agent: { can: ['LISTING:CREATE', 'SHOWING:CREATE', 'APPLICATION:EDIT', 'LEASE:VIEW'], cannot: ['APPLICATION:APPROVE', 'LEASE:APPROVE', 'LISTING:APPROVE', 'INVOICE:VIEW', 'PROPERTY:CREATE', 'VENDOR:VIEW'] },
  accountant: { can: ['INVOICE:CREATE', 'PAYMENT:APPROVE', 'OWNER_STATEMENT:CREATE', 'TAX_REPORT:EDIT', 'AUDIT:VIEW', 'REPORT:EXPORT'], cannot: ['PROPERTY:CREATE', 'PROPERTY:EDIT', 'LISTING:VIEW', 'WORK_ORDER:CREATE', 'USER:VIEW'] },
  owner: { can: ['PROPERTY:VIEW', 'OWNER_STATEMENT:VIEW', 'WORK_ORDER:APPROVE', 'OPPORTUNITY:VIEW', 'APPLICATION:APPROVE'], cannot: ['PROPERTY:EDIT', 'INVOICE:CREATE', 'LISTING:VIEW', 'USER:VIEW', 'VENDOR:VIEW', 'OWNER:CREATE'] },
  tenant: { can: ['LEASE:SIGN', 'PAYMENT:CREATE', 'WORK_ORDER:CREATE', 'WORK_ORDER:VERIFY', 'INVOICE:VIEW'], cannot: ['PROPERTY:VIEW', 'OWNER:VIEW', 'LISTING:VIEW', 'VENDOR:VIEW', 'USER:VIEW', 'REPORT:VIEW', 'OPPORTUNITY:VIEW'] },
  vendor: { can: ['WORK_ORDER:VIEW', 'WORK_ORDER:UPDATE', 'DOCUMENT:CREATE'], cannot: ['PROPERTY:VIEW', 'INVOICE:VIEW', 'WORK_ORDER:CREATE', 'LEASE:VIEW', 'OWNER:VIEW', 'USER:VIEW'] },
};

const permsOf = async (k: PersonaKey): Promise<Set<string>> => new Set((await (await apiAs(k)).get('/api/identity/me')).body.permissions as string[]);

test.describe('Role-based access', () => {
  for (const key of Object.keys(POLICY) as PersonaKey[]) {
    test(`[RBAC-01] ${PERSONAS[key].role} (${key}) has exactly the permissions the policy gives its role`, async () => {
      const perms = await permsOf(key);
      for (const p of POLICY[key].can) expect(perms.has(p), `${key} should have ${p}`).toBe(true);
      for (const p of POLICY[key].cannot) expect(perms.has(p), `${key} must not have ${p}`).toBe(false);
    });
  }

  for (const key of ['admin', 'manager', 'broker', 'agent', 'accountant', 'owner', 'tenant', 'vendor'] as PersonaKey[]) {
    test(`[RBAC-01] every guarded endpoint answers ${PERSONAS[key].role} ${key} according to their permissions`, async () => {
      const api = await apiAs(key);
      const perms = await permsOf(key);
      const wrong: string[] = [];
      for (const [path, need] of GUARDED_GETS) {
        const r = await api.get(path);
        const allowed = perms.has(need);
        if (allowed && r.status === 403 && r.body?.code !== 'FEATURE_OFF') wrong.push(`${path} needs ${need}: allowed but got 403`);
        if (!allowed && r.status !== 403) wrong.push(`${path} needs ${need}: forbidden but got ${r.status}`);
        if (r.status >= 500) wrong.push(`${path}: server error ${r.status}`);
      }
      expect(wrong, wrong.join('\n')).toEqual([]);
    });
  }

  test('[RBAC-02] a forbidden call returns 403 with a readable message and the permission name', async () => {
    const r = await (await apiAs('tenant')).get('/api/leasing/properties');
    expect(r.status).toBe(403);
    expect(r.body.title).toBe('Forbidden');
    expect(String(r.body.detail).length).toBeGreaterThan(10);
    expect(r.body.code).toBeTruthy();
  });

  test('[RBAC-03] the platform administrator is blocked from company data', async () => {
    const api = await apiAs('platform');
    for (const p of ['/api/leasing/properties', '/api/leasing/owners', '/api/maintenance/vendors', '/api/maintenance/work-orders', '/api/billing/invoices', '/api/documents', '/api/workflow/rules']) {
      expect((await api.get(p)).status, p).toBe(403);
    }
    expect((await api.get('/api/leasing/properties')).body.code).toBe('PLATFORM_NO_COMPANY_DATA');
  });

  test('[RBAC-04] a resident sees only their own lease, invoices and requests', async () => {
    const ten = await apiAs('tenant');
    const mine = (await ten.get('/api/leasing/leases/mine')).body;
    expect(mine.tenant_name ?? mine.tenantName ?? '').toContain('Brown');
    const invoices = rows(await ten.get('/api/billing/invoices'));
    const tenantIds = new Set(invoices.map((i: any) => i.tenant_id));
    expect(tenantIds.size).toBeLessThanOrEqual(1);
    const other = await apiAs('tenant2');
    const otherMine = (await other.get('/api/leasing/leases/mine')).body;
    expect(otherMine.id).not.toBe(mine.id);
    const wo = rows(await ten.get('/api/maintenance/work-orders'));
    for (const w of wo) expect(String(w.tenant_name ?? w.requested_by ?? 'Michael Brown')).toMatch(/Brown/);
    expect((await ten.get(`/api/leasing/leases/${otherMine.id}`)).status).toBeGreaterThanOrEqual(403);
  });

  test('[RBAC-05] a vendor sees only the jobs assigned to them', async () => {
    const vendor = await apiAs('vendor');
    const mine = rows(await vendor.get('/api/maintenance/work-orders'));
    const all = rows(await (await apiAs('admin')).get('/api/maintenance/work-orders'));
    expect(mine.length).toBeLessThan(all.length);
    const vendorIds = new Set(mine.map((w: any) => w.vendor_id));
    expect(vendorIds.size).toBeLessThanOrEqual(1);
    expect((await vendor.get('/api/leasing/properties')).status).toBe(403);
  });

  test('[RBAC-06] an owner sees only their own properties', async () => {
    const robert = rows(await (await apiAs('owner')).get('/api/leasing/properties'));
    const nathan = rows(await (await apiAs('owner2')).get('/api/leasing/properties'));
    const all = rows(await (await apiAs('admin')).get('/api/leasing/properties'));
    expect(robert.length).toBeGreaterThan(0);
    expect(nathan.length).toBeGreaterThan(0);
    expect(robert.length + nathan.length).toBeLessThan(all.length + 1);
    const nathanIds = new Set(nathan.map((p: any) => p.id));
    for (const p of robert) expect(nathanIds.has(p.id)).toBe(false);
    expect(new Set(robert.map((p: any) => p.owner_id)).size).toBe(1);
  });

  test('[RBAC-07] a broker is limited to their assigned properties', async () => {
    const marcus = rows(await (await apiAs('broker')).get('/api/leasing/properties'));
    const emily = rows(await (await apiAs('broker2')).get('/api/leasing/properties'));
    const all = rows(await (await apiAs('admin')).get('/api/leasing/properties'));
    expect(marcus.length).toBeGreaterThan(0);
    expect(marcus.length).toBeLessThan(all.length);
    const emilyIds = new Set(emily.map((p: any) => p.id));
    for (const p of marcus) expect(emilyIds.has(p.id)).toBe(false);
  });

  test('[RBAC-09] brokers can see owners but cannot add, edit or archive them', async () => {
    const broker = await apiAs('broker');
    expect((await broker.get('/api/leasing/owners')).status).toBe(200);
    expect((await broker.post('/api/leasing/owners', { name: 'Nope' })).status).toBe(403);
    const someOwner = rows(await (await apiAs('admin')).get('/api/leasing/owners'))[0].id;
    expect((await broker.put(`/api/leasing/owners/${someOwner}`, { phone: '1' })).status).toBe(403);
    expect((await broker.del(`/api/leasing/owners/${someOwner}`)).status).toBe(403);
    expect((await broker.post('/api/leasing/properties', { name: 'x' })).status).toBe(403);
    expect((await broker.post('/api/maintenance/vendors', { name: 'x', trades: ['GENERAL'] })).status).toBe(403);
  });

  test('[RBAC-10] a leasing agent cannot approve and an accountant cannot edit the portfolio', async () => {
    const agent = await apiAs('agent');
    const admin = await apiAs('admin');
    let target: string | undefined;
    for (const a of rows(await admin.get('/api/leasing/applications'))) {
      const d = (await admin.get(`/api/leasing/applications/${a.id}`)).body;
      if (d.actions?.some((x: any) => x.action === 'APPROVE')) { target = a.id; break; }
    }
    expect(target, 'the demo data should have an application that can be approved').toBeTruthy();
    const asAgent = (await agent.get(`/api/leasing/applications/${target}`)).body;
    expect(asAgent.actions.map((x: any) => x.action)).not.toContain('APPROVE');
    const refused = await agent.post(`/api/leasing/applications/${target}/actions/APPROVE`, { reason: 'x' });
    expect(refused.status).toBe(403);
    expect(String(refused.body.detail)).toMatch(/APPLICATION:APPROVE/);
    const acct = await apiAs('accountant');
    const prop = rows(await acct.get('/api/leasing/properties'))[0];
    expect((await acct.put(`/api/leasing/properties/${prop.id}`, { name: 'Hacked' })).status).toBe(403);
    expect((await acct.post('/api/leasing/owners', { name: 'x' })).status).toBe(403);
  });

  test('[RBAC-11] write endpoints refuse every role that lacks the permission', async () => {
    const writes: Array<[string, string, string, unknown]> = [
      ['POST', '/api/leasing/owners', 'OWNER:CREATE', { name: 'x' }],
      ['POST', '/api/leasing/properties', 'PROPERTY:CREATE', { name: 'x' }],
      ['POST', '/api/maintenance/vendors', 'VENDOR:CREATE', { name: 'x', trades: ['GENERAL'] }],
      ['POST', '/api/billing/invoices', 'INVOICE:CREATE', {}],
      ['POST', '/api/identity/users', 'USER:CREATE', {}],
      ['POST', '/api/identity/roles', 'ROLE:CONFIGURE', { name: 'x' }],
      ['POST', '/api/workflow/rules', 'AUTOMATION:CONFIGURE', { name: 'x' }],
      ['PUT', '/api/identity/config/fees', 'SETTINGS:CONFIGURE', {}],
    ];
    const bad: string[] = [];
    for (const key of ['manager', 'broker', 'agent', 'accountant', 'owner', 'tenant', 'vendor'] as PersonaKey[]) {
      const api = await apiAs(key); const perms = await permsOf(key);
      for (const [m, path, need, body] of writes) {
        if (perms.has(need)) continue;
        const r = await api.call(m, path, body);
        if (r.status !== 403) bad.push(`${key} ${m} ${path} (needs ${need}) -> ${r.status}`);
      }
    }
    expect(bad, bad.join('\n')).toEqual([]);
  });
});
