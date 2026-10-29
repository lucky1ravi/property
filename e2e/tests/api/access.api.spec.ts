import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE, SUFFIX } from '../../src/support/env';
import { onboardCompany, roleId, createSignedInUser } from '../../src/support/company';

const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

test.describe('Profile', () => {
  test('[PROF-01] a user can change name, title and phone', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    const r = await c.api.put('/api/identity/me/profile', { name: 'Ada Renamed', title: 'Head of Ops', phone: '+1 512 555 0100' });
    expect(r.status).toBe(200);
    const me = (await c.api.get('/api/identity/me')).body;
    expect(me.name).toBe('Ada Renamed');
    expect(me.title).toBe('Head of Ops');
    expect(me.phone).toBe('+1 512 555 0100');
  });

  test('[PROF-02] email cannot be changed from the profile', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    await c.api.put('/api/identity/me/profile', { name: 'Ada', email: 'hacker@e2e.test' });
    expect((await c.api.get('/api/identity/me')).body.email).toBe(c.adminEmail);
  });

  test('[PROF-03] a photo must be a small jpeg, png or webp image', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    expect((await c.api.put('/api/identity/me/profile', { name: 'Ada', photo: PNG })).status).toBe(200);
    expect((await c.api.get('/api/identity/me')).body.photo).toBe(PNG);
    expect((await c.api.put('/api/identity/me/profile', { name: 'Ada', photo: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=' })).status).toBe(400);
    expect((await c.api.put('/api/identity/me/profile', { name: 'Ada', photo: 'not an image' })).status).toBe(400);
    const big = 'data:image/png;base64,' + 'A'.repeat(160_000);
    expect((await c.api.put('/api/identity/me/profile', { name: 'Ada', photo: big })).status).toBe(400);
    expect((await c.api.put('/api/identity/me/profile', { name: 'Ada' })).status).toBe(200);
    expect((await c.api.get('/api/identity/me')).body.photo ?? null).toBeNull();
  });

  test('[PROF-04] a blank name is rejected', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    expect((await c.api.put('/api/identity/me/profile', { name: '   ' })).status).toBe(400);
    expect((await c.api.put('/api/identity/me/profile', {})).status).toBe(400);
    expect((await c.api.put('/api/identity/me/profile', { name: 'x'.repeat(161) })).status).toBe(400);
    expect((await c.api.get('/api/identity/me')).body.name).toBe('Ada Admin');
  });
});

test.describe('Users and roles', () => {
  test('[ACC-01] an admin lists users with roles and status', async () => {
    const users = rows(await (await apiAs('admin')).get('/api/identity/users'));
    expect(users.length).toBeGreaterThanOrEqual(10);
    const sarah = users.find((u: any) => u.email === 'sarah.mitchell@summitridgepg.com');
    expect(sarah.status).toBe('ACTIVE');
    expect(JSON.stringify(sarah.roles)).toMatch(/ORG_ADMIN|Org Admin|Company/i);
    expect(users.every((u: any) => !('password_hash' in u) && !('passwordHash' in u))).toBe(true);
    expect(users.some((u: any) => u.email === 'kevin.park@harborviewres.com')).toBe(false);
  });

  test('[ACC-02] creating a user returns a one-time temporary password that must be changed', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    const email = `new.${SUFFIX()}@e2e.test`;
    const r = await c.api.post('/api/identity/users', { email, name: 'New Person', roleIds: [await roleId(c.api, 'BROKER')] });
    expect(r.status).toBe(200);
    expect(r.body.temporaryPassword.length).toBeGreaterThanOrEqual(10);
    const login = await (await Api.create()).post('/api/identity/public/login', { email, password: r.body.temporaryPassword });
    expect(login.status).toBe(403);
    expect(login.body.code).toBe('PASSWORD_CHANGE_REQUIRED');
    const changed = await (await Api.create()).post('/api/identity/public/change-password', { email, currentPassword: r.body.temporaryPassword, newPassword: 'Brand-new-pass-1' });
    expect(changed.status).toBe(200);
    const again = await (await Api.create()).post('/api/identity/public/change-password', { email, currentPassword: r.body.temporaryPassword, newPassword: 'Another-pass-22' });
    expect(again.status).toBeGreaterThanOrEqual(400);
    expect((await (await Api.create()).post('/api/identity/public/login', { email, password: 'Brand-new-pass-1' })).status).toBe(200);
  });

  test('[ACC-03] a duplicate email or invalid email is rejected', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    const body = { email: `dup.${c.sfx}@e2e.test`, name: 'Dup', roleIds: [await roleId(c.api, 'BROKER')] };
    expect((await c.api.post('/api/identity/users', body)).status).toBe(200);
    expect([400, 409]).toContain((await c.api.post('/api/identity/users', body)).status);
    expect((await c.api.post('/api/identity/users', { ...body, email: 'not-an-email' })).status).toBe(400);
    expect((await c.api.post('/api/identity/users', { ...body, email: `x.${c.sfx}@e2e.test`, name: '' })).status).toBe(400);
  });

  test('[ACC-04] an admin can reset a password, but not their own', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    const u = await createSignedInUser(c.api, { email: `rp.${c.sfx}@e2e.test`, name: 'Reset Me', roleKey: 'BROKER' });
    const email = `rp.${c.sfx}@e2e.test`;
    const r = await c.api.post(`/api/identity/users/${u.userId}/reset-password`);
    expect(r.status).toBe(200);
    expect((await (await Api.create()).post('/api/identity/public/login', { email, password: u.password })).status).toBe(401);
    const forced = await (await Api.create()).post('/api/identity/public/login', { email, password: r.body.temporaryPassword });
    expect(forced.body.code).toBe('PASSWORD_CHANGE_REQUIRED');
    const me = (await c.api.get('/api/identity/me')).body;
    expect((await c.api.post(`/api/identity/users/${me.userId}/reset-password`)).status).toBe(400);
    expect((await c.api.post('/api/identity/users/00000000-0000-0000-0000-000000000000/reset-password')).status).toBe(404);
  });

  test('[ACC-05] an admin can edit, deactivate and delete a user', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const c = await onboardCompany();
    const email = `ed.${c.sfx}@e2e.test`;
    const u = await createSignedInUser(c.api, { email, name: 'Edit Me', roleKey: 'BROKER' });
    expect((await c.api.put(`/api/identity/users/${u.userId}`, { name: 'Edited', title: 'Senior broker' })).status).toBe(200);
    let row = rows(await c.api.get('/api/identity/users')).find((x: any) => x.email === email);
    expect(row.name).toBe('Edited');
    expect((await c.api.put(`/api/identity/users/${u.userId}`, { status: 'INACTIVE' })).status).toBe(200);
    const blocked = await (await Api.create()).post('/api/identity/public/login', { email, password: u.password });
    expect(blocked.status).toBeGreaterThanOrEqual(401);
    expect((await c.api.put(`/api/identity/users/${u.userId}`, { status: 'ACTIVE' })).status).toBe(200);
    expect((await (await Api.create()).post('/api/identity/public/login', { email, password: u.password })).status).toBe(200);
    const me = (await c.api.get('/api/identity/me')).body;
    expect((await c.api.del(`/api/identity/users/${me.userId}`)).status).toBe(400);
    expect((await c.api.put(`/api/identity/users/${me.userId}`, { roleIds: [] })).status).toBe(400);
    expect((await c.api.del(`/api/identity/users/${u.userId}`)).status).toBe(200);
    row = rows(await c.api.get('/api/identity/users')).find((x: any) => x.email === email);
    expect(row).toBeUndefined();
  });

  test('[ACC-06] the roles list shows built-in roles with their permissions', async () => {
    const roles = rows(await (await apiAs('admin')).get('/api/identity/roles'));
    const keys = roles.map((r: any) => r.role_key);
    expect(keys).toEqual(expect.arrayContaining(['ORG_ADMIN', 'PROPERTY_MANAGER', 'BROKER', 'LEASING_AGENT', 'ACCOUNTANT', 'OWNER', 'TENANT', 'VENDOR']));
    const admin = roles.find((r: any) => r.role_key === 'ORG_ADMIN');
    expect(admin.system_role).toBe(true);
    expect(admin.permissions.length).toBeGreaterThan(40);
    expect(roles.find((r: any) => r.role_key === 'TENANT').permissions.length).toBeLessThan(admin.permissions.length);
  });

  test('[ACC-07] an admin can create, edit and delete custom roles but not built-in ones', async () => {
    test.skip(!CAN_WRITE, 'creates a role');
    const c = await onboardCompany();
    const brokerId = await roleId(c.api, 'BROKER');
    expect((await c.api.post('/api/identity/roles', { name: 'Bad', scope: 'GALAXY' })).status).toBe(400);
    expect((await c.api.post('/api/identity/roles', { description: 'no name' })).status).toBe(400);
    const made = await c.api.post('/api/identity/roles', { name: 'Showing host', description: 'Shows homes', scope: 'PROPERTY', copyFromRoleId: brokerId });
    expect(made.status).toBe(200);
    const id = made.body.id;
    let role = rows(await c.api.get('/api/identity/roles')).find((r: any) => r.id === id);
    expect(role.name).toBe('Showing host');
    expect(role.system_role).toBeFalsy();
    expect(role.permissions.length).toBe(rows(await c.api.get('/api/identity/roles')).find((r: any) => r.id === brokerId).permissions.length);
    expect((await c.api.put(`/api/identity/roles/${id}`, { name: 'Showing host 2', color: '#112233' })).status).toBe(200);
    role = rows(await c.api.get('/api/identity/roles')).find((r: any) => r.id === id);
    expect(role.name).toBe('Showing host 2');
    expect((await c.api.del(`/api/identity/roles/${id}`)).status).toBe(200);
    expect(rows(await c.api.get('/api/identity/roles')).some((r: any) => r.id === id)).toBe(false);
    expect((await c.api.del(`/api/identity/roles/${brokerId}`)).status).toBe(400);
  });

  test('[ACC-08] a role\'s permissions change per feature and action and apply after refresh', async () => {
    test.skip(!CAN_WRITE, 'creates a role');
    const c = await onboardCompany();
    const role = (await c.api.post('/api/identity/roles', { name: 'Vendor viewer', scope: 'ORG' })).body.id;
    const email = `pv.${c.sfx}@e2e.test`;
    const u = await c.api.post('/api/identity/users', { email, name: 'Perm Viewer', roleIds: [role] });
    const pw = 'Perm-viewer-pass-1';
    const signed = await (await Api.create()).post('/api/identity/public/change-password', { email, currentPassword: u.body.temporaryPassword, newPassword: pw });
    const user = await Api.create(signed.body.token);
    expect((await user.get('/api/maintenance/vendors')).status).toBe(403);
    const set = await c.api.put(`/api/identity/roles/${role}/permissions`, [{ feature: 'VENDOR', action: 'VIEW' }]);
    expect(set.status).toBe(200);
    expect(set.body.grants).toBe(1);
    expect((await user.get('/api/maintenance/vendors')).status).toBe(403); // old token keeps old rights
    const fresh = await user.post('/api/identity/refresh');
    expect(fresh.status).toBe(200);
    const after = await Api.create(fresh.body.token);
    expect((await after.get('/api/maintenance/vendors')).status).toBe(200);
    expect((await after.post('/api/maintenance/vendors', { name: 'x' })).status).toBe(403);
    expect((await c.api.put(`/api/identity/roles/${role}/permissions`, [{ feature: 'VENDOR', action: 'TELEPORT' }])).status).toBe(400);
    expect((await c.api.put(`/api/identity/roles/${role}/permissions`, [{ feature: 'PLATFORM', action: 'CONFIGURE' }])).status).toBe(403);
    const orgAdmin = await roleId(c.api, 'ORG_ADMIN');
    expect((await c.api.put(`/api/identity/roles/${orgAdmin}/permissions`, [{ feature: 'VENDOR', action: 'VIEW' }])).status).toBe(400);
  });

  test('[ACC-09] the permission catalog lists features, actions, scopes and fields', async () => {
    const cat = (await (await apiAs('admin')).get('/api/identity/catalog')).body;
    expect(cat.features.length).toBeGreaterThan(15);
    expect(cat.features.map((f: any) => f.key)).toEqual(expect.arrayContaining(['USER', 'ROLE', 'LEASE', 'INVOICE', 'WORK_ORDER', 'VENDOR', 'SETTINGS', 'AUDIT']));
    expect(cat.actions.length).toBeGreaterThan(5);
    expect(Object.keys(cat.scopes)).toEqual(expect.arrayContaining(['ORG', 'PROPERTY', 'OWNER', 'TENANT', 'VENDOR']));
    expect(cat.fields).toBeTruthy();
  });

  test('[ACC-10] owner, resident and vendor sign-ins link to their own records', async () => {
    for (const key of ['owner', 'tenant', 'vendor'] as const) {
      const me = (await (await apiAs(key)).get('/api/identity/me')).body;
      expect(me.partyId, key).toBeTruthy();
    }
    const me = (await (await apiAs('owner')).get('/api/identity/me')).body;
    expect(me.scope).toBe('OWNER');
    const t = (await (await apiAs('tenant')).get('/api/identity/me')).body;
    expect(t.scope).toBe('TENANT');
    const v = (await (await apiAs('vendor')).get('/api/identity/me')).body;
    expect(v.scope).toBe('VENDOR');
  });
});

test.describe('Configuration and audit', () => {
  test('[CFG-01] configuration is returned with defaults applied', async () => {
    const cfg = (await (await apiAs('admin')).get('/api/identity/config')).body;
    for (const s of ['features', 'fees', 'invoicing', 'approvals', 'screening', 'sla', 'branding']) expect(cfg[s], s).toBeTruthy();
    expect((await (await apiAs('tenant')).get('/api/identity/config')).status).toBe(200);
  });

  test('[CFG-02] a section can be changed and the change is versioned', async () => {
    test.skip(!CAN_WRITE, 'changes configuration');
    const c = await onboardCompany();
    const cfg = (await c.api.get('/api/identity/config')).body;
    const h0 = rows(await c.api.get('/api/identity/config/history')).length;
    const r = await c.api.put('/api/identity/config/fees?summary=Raise%20late%20fee', { ...cfg.fees, lateFeePercent: 8 });
    expect(r.status).toBe(200);
    expect(r.body.fees.lateFeePercent).toBe(8);
    expect((await c.api.get('/api/identity/config')).body.fees.lateFeePercent).toBe(8);
    const hist = rows(await c.api.get('/api/identity/config/history'));
    expect(hist.length).toBe(h0 + 1);
    expect(hist[0].summary).toBe('Raise late fee');
    expect(hist[0].section).toBe('fees');
    expect(hist[0].changed_by).toBe('Ada Admin');
    expect((await c.api.put('/api/identity/config/nonsense', {})).status).toBe(400);
  });

  test('[CFG-03] history lists changes and rolls back to an earlier version', async () => {
    test.skip(!CAN_WRITE, 'changes configuration');
    const c = await onboardCompany();
    const base = (await c.api.get('/api/identity/config')).body;
    const original = base.fees.lateFeePercent;
    await c.api.put('/api/identity/config/fees', { ...base.fees, lateFeePercent: 9 });
    const hist = rows(await c.api.get('/api/identity/config/history'));
    const before = hist[hist.length - 1].version; // onboarding version
    const back = await c.api.post(`/api/identity/config/rollback/${before}`);
    expect(back.status).toBe(200);
    expect(back.body.fees.lateFeePercent).toBe(original);
    const after = rows(await c.api.get('/api/identity/config/history'));
    expect(after[0].summary).toMatch(/Rolled back/);
    expect(after[0].version).toBeGreaterThan(hist[0].version);
    expect((await c.api.post('/api/identity/config/rollback/9999')).status).toBe(404);
  });

  test('[CFG-04] overrides list only what the company changed', async () => {
    test.skip(!CAN_WRITE, 'changes configuration');
    const c = await onboardCompany();
    const base = (await c.api.get('/api/identity/config')).body;
    const before = Object.keys((await c.api.get('/api/identity/config/overrides')).body);
    await c.api.put('/api/identity/config/sla', base.sla);
    const after = (await c.api.get('/api/identity/config/overrides')).body;
    expect(Object.keys(after)).toContain('sla');
    expect(Object.keys(after).length).toBe(new Set([...before, 'sla']).size);
    expect(Object.keys(after).length).toBeLessThan(Object.keys(base).length);
  });

  test('[CFG-05] plan gating hides features the plan excludes', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const c = await onboardCompany({ plan: 'STARTER' });
    const full = (await (await apiAs('admin')).get('/api/identity/config')).body.features;
    const starter = (await c.api.get('/api/identity/config')).body.features;
    expect(JSON.stringify(starter)).not.toEqual(JSON.stringify(full));
    const me = (await c.api.get('/api/identity/me')).body;
    expect(me.organization.plan).toBe('STARTER');
    const off = Object.entries(me.features).filter(([, v]) => v === false || (v as any)?.enabled === false).map(([k]) => k);
    expect(off.length).toBeGreaterThan(0);
  });

  test('[CFG-06] branding company name falls back to the company\'s own name', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const c = await onboardCompany();
    const me = (await c.api.get('/api/identity/me')).body;
    expect(me.branding.companyName).toBe(c.name);
    const b = (await c.api.get('/api/identity/config')).body.branding;
    await c.api.put('/api/identity/config/branding', { ...b, companyName: 'Custom Brand' });
    expect((await c.api.get('/api/identity/me')).body.branding.companyName).toBe('Custom Brand');
  });

  test('[CFG-08] the audit log records who changed what', async () => {
    test.skip(!CAN_WRITE, 'changes configuration');
    const c = await onboardCompany();
    const cfg = (await c.api.get('/api/identity/config')).body;
    await c.api.put('/api/identity/config/fees', { ...cfg.fees, lateFeePercent: 6 });
    const email = `au.${c.sfx}@e2e.test`;
    await c.api.post('/api/identity/users', { email, name: 'Audited', roleIds: [await roleId(c.api, 'BROKER')] });
    const log = rows(await c.api.get('/api/identity/audit?limit=50'));
    const upd = log.find((a: any) => a.action === 'CONFIG_UPDATED');
    expect(upd.actor_name).toBe('Ada Admin');
    expect(upd.details).toContain('lateFeePercent');
    expect(log.some((a: any) => a.action === 'USER_CREATED')).toBe(true);
    expect(rows(await c.api.get('/api/identity/audit?limit=50&entityType=User')).every((a: any) => a.entity_type === 'User')).toBe(true);
    expect((await (await apiAs('tenant')).get('/api/identity/audit')).status).toBe(403);
    const none = rows(await (await apiAs('admin')).get('/api/identity/audit?limit=500'));
    expect(none.some((a: any) => String(a.details).includes(email))).toBe(false);
  });
});
