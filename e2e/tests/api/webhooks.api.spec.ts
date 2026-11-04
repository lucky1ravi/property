import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, rows } from '../../src/support/api';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, apply, until } from '../../src/support/builders';
import { startReceiver, expectedSignature } from '../../src/support/receiver';

/**
 * Webhooks. A company sends events to another system (a CRM, Slack) as signed JSON. Each delivery is signed so the receiver can trust it, is logged,
 * and is tried again when the receiver fails. The message text reads well on its own, with the details of what happened.
 */
test.describe('Webhooks', () => {
  needsWrites();
  test.setTimeout(180_000);

  test('[HOOK-01] an endpoint needs a name, a web address and events; its secret is shown once; residents and other companies cannot touch it', async () => {
    const c = await onboardCompany();
    const rx = await startReceiver();
    try {
      const events = (await c.api.get('/api/workflow/webhooks/events')).body.map((e: any) => e.key);
      expect(events).toEqual(expect.arrayContaining(['application.submitted', 'lease.signed', 'payment.received', 'work_order.created']));
      const resident = await createSignedInUser(c.api, { email: `r.${c.sfx}@e2e.test`, name: 'Rae Resident', roleKey: 'TENANT' });
      expect((await resident.api.post('/api/workflow/webhooks', { name: 'x', url: rx.url(), events })).status).toBe(403);
      expect((await c.api.post('/api/workflow/webhooks', { name: '', url: rx.url(), events })).status, 'a name is needed').toBe(400);
      expect((await c.api.post('/api/workflow/webhooks', { name: 'x', url: rx.url(), events: [] })).status, 'at least one event').toBe(400);
      expect((await c.api.post('/api/workflow/webhooks', { name: 'x', url: rx.url(), events: ['lead.created'] })).status, 'an unknown event').toBe(400);
      expect((await c.api.post('/api/workflow/webhooks', { name: 'x', url: 'ftp://example.com/x', events })).status, 'not a web address').toBe(400);

      const made = await c.api.post('/api/workflow/webhooks', { name: 'CRM', url: rx.url(), events });
      expect(made.status).toBe(200);
      expect(made.body.secret).toMatch(/^whsec_/);
      expect(JSON.stringify((await c.api.get('/api/workflow/webhooks')).body)).not.toContain('whsec_');

      const other = await onboardCompany();
      expect(rows(await other.api.get('/api/workflow/webhooks')).find((w: any) => w.id === made.body.id)).toBeUndefined();
      expect((await other.api.put(`/api/workflow/webhooks/${made.body.id}`, { active: false })).status).toBeGreaterThanOrEqual(400);
      expect((await other.api.post(`/api/workflow/webhooks/${made.body.id}/test`, {})).status).toBeGreaterThanOrEqual(400);

      expect((await c.api.put(`/api/workflow/webhooks/${made.body.id}`, { active: false })).body.active).toBe(false);
      expect((await c.api.put(`/api/workflow/webhooks/${made.body.id}`, { active: true, name: 'CRM 2' })).body.name).toBe('CRM 2');
      const rotated = (await c.api.post(`/api/workflow/webhooks/${made.body.id}/rotate-secret`, {})).body.secret as string;
      expect(rotated).toMatch(/^whsec_/);
      expect(rotated).not.toBe(made.body.secret);
      expect((await c.api.del(`/api/workflow/webhooks/${made.body.id}`)).status).toBe(200);
    } finally { await rx.close(); }
  });

  test('[HOOK-02] a delivery is signed so the receiver can check it, and the log shows it', async () => {
    const c = await onboardCompany();
    const rx = await startReceiver();
    try {
      const events = (await c.api.get('/api/workflow/webhooks/events')).body.map((e: any) => e.key);
      const made = (await c.api.post('/api/workflow/webhooks', { name: 'Signed', url: rx.url(), events })).body;
      expect((await c.api.post(`/api/workflow/webhooks/${made.id}/test`, {})).status).toBe(200);
      await until(async () => rx.got.length, (n) => n >= 1);
      const d = rx.got[0];
      expect(d.json.test).toBe(true);
      expect(d.json.text).toMatch(/^\[Test\] /);
      const ts = String(d.headers['x-propertyos-timestamp']);
      expect(d.headers['x-propertyos-signature']).toBe(expectedSignature(made.secret, ts, d.body));
      expect(d.headers['x-propertyos-event']).toBe(d.json.type);
      const log = rows(await c.api.get(`/api/workflow/webhooks/deliveries?endpointId=${made.id}`));
      expect(log.length).toBeGreaterThanOrEqual(1);
      const one = (await c.api.get(`/api/workflow/webhooks/deliveries/${log[0].id}`)).body;
      expect(JSON.stringify(one)).toContain('Test');
    } finally { await rx.close(); }
  });

  test('[HOOK-03] a real application reaches the endpoint with the applicant\'s details in the message', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const rx = await startReceiver();
    try {
      await c.api.post('/api/workflow/webhooks', { name: 'Slack-like', url: rx.url(), events: ['application.submitted'] });
      const email = `maya.${p.sfx}@example.com`;
      expect((await apply(await Api.create(), listing, { first: 'Maya', last: 'Patel', email }, { pets: 'One cat', employer: 'Acme Robotics' })).status).toBe(200);
      await until(async () => rx.got.filter((g) => g.json?.type === 'application.submitted' && !g.json.test).length, (n) => n >= 1);
      const real = rx.got.find((g) => g.json?.type === 'application.submitted' && !g.json.test)!;
      const lines = String(real.json.text).split('\n');
      expect(lines[0]).toBe('New application: Maya Patel applied for unit 1A');
      const text = lines.join('\n');
      for (const part of [`Applicant: Maya Patel`, email, 'Income: $9,000.00 a month', 'Employer Acme Robotics', '2 occupants', 'Pets: One cat', 'Came from: Public website'])
        expect(text, part).toContain(part);
      expect(real.json.data.applicantEmail).toBe(email);
      expect(real.json.data.monthlyIncome).toBe('9000.00');
      expect(real.json.data.source).toBe('WEBSITE');
    } finally { await rx.close(); }
  });

  test('[HOOK-04] a receiver that fails is tried again, and a failed delivery can be retried by hand', async () => {
    const c = await onboardCompany();
    const rx = await startReceiver(500);
    try {
      const made = (await c.api.post('/api/workflow/webhooks', { name: 'Flaky', url: rx.url(), events: ['payment.received', 'application.submitted'] })).body;
      await c.api.post(`/api/workflow/webhooks/${made.id}/test`, {});
      const failed = await until(async () => rows(await c.api.get(`/api/workflow/webhooks/deliveries?endpointId=${made.id}`)),
        (r) => r.length >= 1 && r[0].status !== 'SUCCEEDED');
      expect(failed[0].status, 'a failure is logged and waits for a retry').toMatch(/PENDING|FAILED/);
      expect(Number(failed[0].attempts)).toBeGreaterThanOrEqual(1);
      rx.answerWith(200);
      expect((await c.api.post(`/api/workflow/webhooks/deliveries/${failed[0].id}/retry`, {})).status).toBe(200);
      const ok = await until(async () => rows(await c.api.get(`/api/workflow/webhooks/deliveries?endpointId=${made.id}`)),
        (r) => r.some((d: any) => d.status === 'SUCCEEDED'));
      expect(ok.some((d: any) => d.status === 'SUCCEEDED')).toBe(true);
    } finally { await rx.close(); }
  });

  test('[HOOK-05] a company on a plan without webhooks cannot use them', async () => {
    const c = await onboardCompany({ plan: 'STARTER' });
    expect((await c.api.get('/api/workflow/webhooks')).status).toBe(403);
    expect((await c.api.post('/api/workflow/webhooks', { name: 'x', url: 'https://example.com/hook', events: ['payment.received'] })).status).toBe(403);
  });
});
