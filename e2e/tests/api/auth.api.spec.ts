import { test, expect } from '../../src/support/fixtures';
import { Api, tokenFor } from '../../src/support/api';
import { DEMO_PASSWORD, CAN_WRITE, SUFFIX } from '../../src/support/env';
import { PERSONAS } from '../../src/support/personas';

test.describe('Sign-in and session (API)', () => {
  test('[AUTH-01] a valid user signs in and receives a token and profile', async () => {
    const api = await Api.create();
    const r = await api.post('/api/identity/public/login', { email: PERSONAS.admin.email, password: DEMO_PASSWORD });
    expect(r.status).toBe(200);
    expect(r.body.token).toBeTruthy();
    expect(r.body.expiresInSeconds).toBeGreaterThan(3600);
    const me = await (await Api.create(r.body.token)).get('/api/identity/me');
    expect(me.body.email).toBe(PERSONAS.admin.email);
  });

  test('[AUTH-02] a wrong password is refused with a friendly message', async () => {
    const api = await Api.create();
    const r = await api.post('/api/identity/public/login', { email: PERSONAS.admin.email, password: 'not-the-password' });
    expect(r.status).toBe(401);
    expect(String(r.body.detail)).toMatch(/email or password/i);
  });

  test('[AUTH-03] an unknown email gets the same refusal as a wrong password', async () => {
    const api = await Api.create();
    const wrong = await api.post('/api/identity/public/login', { email: PERSONAS.admin.email, password: 'nope' });
    const unknown = await api.post('/api/identity/public/login', { email: `nobody.${SUFFIX()}@example.com`, password: 'nope' });
    expect(unknown.status).toBe(wrong.status);
    expect(unknown.body.detail).toBe(wrong.body.detail);
  });

  test('[AUTH-04] protected endpoints refuse requests without a token', async () => {
    const api = await Api.create();
    for (const p of ['/api/identity/me', '/api/leasing/properties', '/api/billing/invoices', '/api/maintenance/work-orders', '/api/workflow/rules', '/api/documents', '/api/identity/users']) {
      expect((await api.get(p)).status, p).toBe(401);
    }
    expect((await api.post('/api/ai/chat', { message: 'hi' })).status).toBe(401);
  });

  test('[AUTH-05] a tampered or invalid token is refused', async () => {
    const good = await tokenFor('admin');
    const tampered = good.slice(0, -4) + (good.endsWith('AAAA') ? 'BBBB' : 'AAAA');
    for (const t of ['garbage', tampered, 'a.b.c']) {
      const r = await (await Api.create(t)).get('/api/identity/me');
      expect(r.status, t.slice(0, 10)).toBe(401);
    }
  });

  test('[AUTH-06] /me returns name, role, permissions, features and branding', async () => {
    const me = (await (await Api.create(await tokenFor('admin'))).get('/api/identity/me')).body;
    expect(me.name).toBe(PERSONAS.admin.name);
    expect(me.email).toBe(PERSONAS.admin.email);
    expect(me.roles).toContain('Org Admin');
    expect(me.permissions.length).toBeGreaterThan(20);
    expect(me.permissions).toContain('PROPERTY:VIEW');
    expect(me.branding?.companyName).toBe(PERSONAS.admin.company);
    expect(me.features ?? me.entitlements ?? me.plan).toBeTruthy();
  });

  test('[AUTH-07] a token can be refreshed and the new token works', async () => {
    const api = await Api.create(await tokenFor('manager'));
    const r = await api.post('/api/identity/refresh', {});
    expect(r.status).toBe(200);
    expect(r.body.token).toBeTruthy();
    expect((await (await Api.create(r.body.token)).get('/api/identity/me')).status).toBe(200);
  });

  test('[AUTH-08] a temporary password forces a change on first sign-in', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const admin = await Api.create(await tokenFor('admin'));
    const roles = (await admin.get('/api/identity/roles')).body;
    const role = (Array.isArray(roles) ? roles : roles.rows).find((r: any) => r.role_key === 'LEASING_AGENT');
    const email = `temp.${SUFFIX()}@e2e.test`;
    const created = await admin.post('/api/identity/users', { email, name: 'Temp Person', roleIds: [role.id] });
    expect(created.status).toBe(200);
    const temp = created.body.temporaryPassword;
    expect(temp).toBeTruthy();
    const pub = await Api.create();
    const blocked = await pub.post('/api/identity/public/login', { email, password: temp });
    expect(blocked.status).toBe(403);
    expect(blocked.body.code).toBe('PASSWORD_CHANGE_REQUIRED');
    const changed = await pub.post('/api/identity/public/change-password', { email, currentPassword: temp, newPassword: 'Brand-new-pass-1' });
    expect(changed.status).toBe(200);
    expect(changed.body.token).toBeTruthy();
    expect((await pub.post('/api/identity/public/login', { email, password: 'Brand-new-pass-1' })).status).toBe(200);
    expect((await pub.post('/api/identity/public/login', { email, password: temp })).status).toBe(401);
  });

  test('[AUTH-09] a new password must meet the minimum length', async () => {
    test.skip(!CAN_WRITE, 'creates a user');
    const admin = await Api.create(await tokenFor('admin'));
    const roles = (await admin.get('/api/identity/roles')).body;
    const role = (Array.isArray(roles) ? roles : roles.rows).find((r: any) => r.role_key === 'LEASING_AGENT');
    const email = `short.${SUFFIX()}@e2e.test`;
    const temp = (await admin.post('/api/identity/users', { email, name: 'Short Pass', roleIds: [role.id] })).body.temporaryPassword;
    const r = await (await Api.create()).post('/api/identity/public/change-password', { email, currentPassword: temp, newPassword: 'short' });
    expect(r.status).toBe(400);
    expect(String(r.body.detail)).toMatch(/10|characters|password/i);
  });

  test('[AUTH-15] one-time code endpoints validate their input', async () => {
    const api = await Api.create();
    // Requesting a code never reveals whether an account exists: known and unknown emails get the same answer.
    const known = await api.post('/api/identity/public/otp/request', { email: PERSONAS.admin.email });
    const unknown = await api.post('/api/identity/public/otp/request', { email: `nobody.${SUFFIX()}@example.com` });
    expect(known.status).toBe(200);
    expect(unknown.body.message).toBe(known.body.message);
    expect((await api.post('/api/identity/public/otp/request', {})).status).toBe(400);
    expect((await api.post('/api/identity/public/otp/request', { email: 'not-an-email' })).status).toBe(400);
    expect((await api.post('/api/identity/public/otp/verify', { email: 'x@example.com', code: '000000' })).status).toBe(401);
    expect((await api.post('/api/identity/public/login', {})).status).toBeGreaterThanOrEqual(400);
  });

  test('[AUTH-10] the demo environment publishes its personas and options', async () => {
    const api = await Api.create();
    const opts = (await api.get('/api/identity/public/options')).body;
    expect(opts.demo).toBe(true);
    const users = (await api.get('/api/identity/public/demo-users')).body;
    expect(users.enabled).toBe(true);
    expect(users.users.map((u: any) => u.email)).toEqual(expect.arrayContaining(Object.values(PERSONAS).map((p) => p.email)));
  });
});
