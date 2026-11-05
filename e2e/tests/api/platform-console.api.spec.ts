import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { SUFFIX, CAN_WRITE } from '../../src/support/env';
import { onboardCompany } from '../../src/support/company';
import { until } from '../../src/support/builders';

/** The platform operator's console: service health, company usage and the delivery log. Platform administrators only. */
test.describe('Platform operator console', () => {
  test('[CONSOLE-01] every service reports its health, and only the platform administrator can ask', async () => {
    const platform = await apiAs('platform');
    const r = await platform.get('/api/identity/platform/health');
    expect(r.status).toBe(200);
    expect(r.body.overall).toBe('UP');
    expect(r.body.total).toBe(8);
    expect(r.body.up).toBe(8);
    expect(r.body.services.map((s: any) => s.service).sort()).toEqual(['AI', 'BILLING', 'DATABASE', 'DOCUMENTS', 'IDENTITY', 'LEASING', 'MAINTENANCE', 'WORKFLOW']);
    for (const s of r.body.services) { expect(s.status).toBe('UP'); expect(s.millis).toBeGreaterThanOrEqual(0); }
    for (const k of ['admin', 'manager', 'tenant', 'owner'] as const) expect((await (await apiAs(k)).get('/api/identity/platform/health')).status, k).toBe(403);
    expect((await (await Api.create()).get('/api/identity/platform/health')).status).toBe(401);
  });

  test('[CONSOLE-02] company usage lists each customer with who signed in, and can be filtered, sorted and searched', async () => {
    test.skip(!CAN_WRITE, 'creates a company');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const mine = await until(() => platform.get(`/api/identity/platform/usage?q=${encodeURIComponent(c.name)}`), (r) => rows(r).length > 0);
    const row = rows(mine)[0];
    expect(row.name).toBe(c.name);
    expect(row.users).toBeGreaterThanOrEqual(1);
    expect(row.active_7d).toBeLessThanOrEqual(row.users);
    expect(['ACTIVE', 'SLOWING', 'QUIET', 'NEVER']).toContain(row.engagement);
    // The first administrator has signed in (the account was set up with a password), so the company is not "not started" any more.
    expect(row.engagement).toBe('ACTIVE');
    for (const e of ['ACTIVE', 'SLOWING', 'QUIET', 'NEVER']) for (const o of rows(await platform.get(`/api/identity/platform/usage?page=1&size=100&f.engagement=${e}`))) expect(o.engagement).toBe(e);
    const bySize = rows(await platform.get('/api/identity/platform/usage?page=1&size=20&sort=users&dir=desc'));
    for (let i = 1; i < bySize.length; i++) expect(bySize[i - 1].users).toBeGreaterThanOrEqual(bySize[i].users);
    expect(rows(await platform.get('/api/identity/platform/usage?page=1&size=100')).some((o: any) => o.name === 'PropertyOS Platform')).toBe(false);
    expect((await (await apiAs('admin')).get('/api/identity/platform/usage')).status).toBe(403);
  });

  test('[CONSOLE-03] the delivery log shows messages for every company with the company named, and is closed to everyone else', async () => {
    test.skip(!CAN_WRITE, 'sends a message');
    const platform = await apiAs('platform');
    const sfx = SUFFIX();
    const startedAt = Date.now() - 2000;
    expect((await (await Api.create()).post('/api/identity/public/contact', { name: `Logan ${sfx}`, email: `logan.${sfx}@e2e.test`, message: 'Please arrange a demo for our team.', topic: 'DEMO' })).status).toBe(200);
    const platformOrg = (await platform.get('/api/identity/me')).body.organization.id;
    const found = await until(() => platform.get(`/api/workflow/platform/deliveries?page=1&size=20&sort=created_at&dir=desc&orgId=${platformOrg}`),
      (r) => rows(r).some((x: any) => new Date(x.created_at).getTime() >= startedAt));
    const d = rows(found).find((x: any) => new Date(x.created_at).getTime() >= startedAt);
    expect(d.org_name).toBe('PropertyOS Platform');
    expect(['EMAIL', 'SMS']).toContain(d.channel);
    expect(d.recipient, 'masked to the first letter and the domain, with no stray characters').toMatch(/^[A-Za-z0-9]\*\*\*@[^*]+$|^\*+\d{2}$/);
    expect(d.subject, 'the operator never sees what a message said').toBeUndefined();
    expect(d.body).toBeUndefined();
    for (const x of rows(await platform.get('/api/workflow/platform/deliveries?page=1&size=100'))) {
      expect(x.recipient, 'every recipient is masked: emails to their first letter, texts to their last two digits').toMatch(/^[^*]\*\*\*@[^*]+$|^\*+\d{2}$/);
    }
    const page = (await platform.get('/api/workflow/platform/deliveries?page=1&size=2')).body;
    expect(page.rows).toHaveLength(2);
    expect(page.total).toBeGreaterThan(2);
    const summary = (await platform.get('/api/workflow/platform/deliveries/summary')).body;
    expect(summary.last7d).toBeGreaterThan(0);
    expect(Array.isArray(summary.byChannel)).toBe(true);
    for (const k of ['admin', 'accountant', 'tenant'] as const) {
      expect((await (await apiAs(k)).get('/api/workflow/platform/deliveries')).status, k).toBe(403);
      expect((await (await apiAs(k)).get('/api/workflow/platform/deliveries/summary')).status, k).toBe(403);
    }
  });
  test('[CONSOLE-09] for support work one message can be opened in full with a reason, and the company can see in its audit log that it was', async () => {
    test.skip(!CAN_WRITE, 'sends a message');
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    await platform.post('/api/identity/platform/billing/run', {}); // the company's administrator is told their invoice is ready
    const listed = await until(() => platform.get(`/api/workflow/platform/deliveries?orgId=${c.orgId}&page=1&size=10`), (r) => rows(r).filter((x: any) => x.channel === 'EMAIL').length >= 3);   // the welcome email, the password notice from first sign-in, then the invoice notice
    const row = rows(listed).find((x: any) => x.channel === 'EMAIL') ?? rows(listed)[0];
    expect(row.recipient, 'the list still masks it').toContain('*');
    const url = `/api/workflow/platform/deliveries/${row.id}/reveal`;
    expect((await platform.post(url, {})).status, 'a reason is needed').toBe(400);
    expect((await platform.post(url, { reason: 'too short' })).status).toBe(400);
    expect((await platform.post(url, { reason: 'x'.repeat(301) })).status).toBe(400);
    expect((await platform.post(`/api/workflow/platform/deliveries/${'00000000-0000-0000-0000-000000000000'}/reveal`, { reason: 'Support request 4411' })).status).toBe(404);

    // The company now has several emails (a welcome, a password notice, the invoice), so the one about the invoice is looked for among them.
    const reason = 'Support request 4411: the administrator says no invoice email arrived';
    let shown = await platform.post(url, { reason });
    for (const other of rows(listed).filter((x: any) => x.channel === 'EMAIL' && x.id !== row.id)) {
      if (/invoice/i.test(shown.body.subject ?? '')) break;
      shown = await platform.post(`/api/workflow/platform/deliveries/${other.id}/reveal`, { reason });
    }
    expect(shown.status).toBe(200);
    expect(shown.body.recipient).not.toContain('*');
    expect(shown.body.recipient).toMatch(/@/);
    expect(shown.body.subject).toMatch(/invoice/i);
    expect(shown.body.org_name).toBe(c.name);
    expect(shown.body, 'the message text is never shown').not.toHaveProperty('body');

    const audit = rows(await c.api.get('/api/identity/audit?page=1&size=100'));
    const seen = audit.find((a: any) => a.action === 'PLATFORM_DELIVERY_VIEWED');
    expect(seen, 'the company sees that the operator looked').toBeTruthy();
    expect(seen.actor_name).toMatch(/Alex Morgan|Platform/);
    expect(JSON.stringify(seen.details)).toContain('Support request 4411');
    // Only the operator can open it, and the company cannot.
    expect((await c.api.post(url, { reason: 'I would like to see this one' })).status).toBe(403);
    expect((await (await apiAs('manager')).post(url, { reason: 'I would like to see this one' })).status).toBe(403);
    expect((await (await Api.create()).post(url, { reason: 'I would like to see this one' })).status).toBe(401);
  });
});
