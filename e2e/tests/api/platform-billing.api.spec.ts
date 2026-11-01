import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany } from '../../src/support/company';
import { buildPortfolio, until } from '../../src/support/builders';

/**
 * What companies pay PropertyOS. The operator bills every company monthly (units managed times the plan's price, never under the
 * plan's minimum), records payments, and watches who is behind. A company sees only its own account and can pay it by card or bank.
 */
test.describe('Platform fees: companies paying PropertyOS', () => {
  test.skip(!CAN_WRITE, 'creates companies and invoices');
  test.setTimeout(120_000);

  const invoicesOf = async (platform: Api, orgId: string) => rows(await platform.get(`/api/identity/platform/billing/invoices?page=1&size=50&orgId=${orgId}`));

  test('[FEES-01] the monthly run bills each company once: units times the plan price, never under the minimum, and running it again changes nothing', async () => {
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    await buildPortfolio(c); // two units
    const first = await platform.post('/api/identity/platform/billing/run', {});
    expect(first.status).toBe(200);
    expect(first.body.period).toBe(new Date().toISOString().slice(0, 7));
    const mine = await until(() => platform.get(`/api/identity/platform/billing/orgs/${c.orgId}`), (r) => r.body.invoices?.length > 0);
    const inv = mine.body.invoices.find((i: any) => i.kind === 'SUBSCRIPTION');
    expect(inv.number).toMatch(/^PO-\d{6}-\d{4}$/);
    expect(inv.units).toBe(2);
    const price = Number(inv.unit_price), min = Number(inv.minimum);
    expect(Number(inv.amount)).toBeCloseTo(Math.max(min, price * 2), 2);
    expect(Number(inv.amount), 'two units on a small plan sit at the minimum').toBe(min);
    expect(inv.status).toBe('OPEN');
    expect(new Date(inv.due_date).getTime()).toBeGreaterThan(Date.now() - 86_400_000);
    const again = await platform.post('/api/identity/platform/billing/run', {});
    expect(again.status).toBe(200); // other companies created at the same moment may be billed by this run; this one must not be billed twice
    expect((await invoicesOf(platform, c.orgId)).filter((i: any) => i.kind === 'SUBSCRIPTION')).toHaveLength(1);
    // The company's own administrators are told, and the operator's own organisation is never billed.
    const note = await until(() => c.api.get('/api/workflow/notifications'), (r) => rows(r).some((n: any) => /PropertyOS invoice is ready/.test(n.title)));
    expect(rows(note).some((n: any) => n.link === '/admin/subscription')).toBe(true);
    expect(rows(await platform.get('/api/identity/platform/billing/invoices?page=1&size=200')).some((i: any) => i.org_name === 'PropertyOS Platform')).toBe(false);
  });

  test('[FEES-02] a company sees only its own invoices and can pay by card: a declined card changes nothing, a part payment leaves a balance, paying the rest settles it', async () => {
    const platform = await apiAs('platform');
    const a = await onboardCompany(), b = await onboardCompany();
    await platform.post('/api/identity/platform/billing/run', {});
    const mineA = await until(() => a.api.get('/api/identity/billing/invoices?page=1&size=20'), (r) => rows(r).length > 0);
    const inv = rows(mineA)[0];
    expect(rows(await b.api.get('/api/identity/billing/invoices?page=1&size=20')).every((i: any) => i.org_id !== a.orgId)).toBe(true);
    expect((await b.api.get(`/api/identity/billing/invoices/${inv.id}`)).status, 'another company cannot open it').toBe(404);
    expect((await b.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'CARD', last4: '4242' })).status).toBe(404);

    const sub = (await a.api.get('/api/identity/billing/subscription')).body;
    expect(Number(sub.balance)).toBeCloseTo(Number(inv.amount), 2);
    expect(sub.units).toBeGreaterThanOrEqual(0);
    expect(Number(sub.estimatedNextInvoice)).toBeGreaterThan(0);

    const declined = await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'CARD', last4: '0002' });
    expect(declined.status).toBe(409);
    expect(JSON.stringify(declined.body)).toMatch(/declined/i);
    expect(Number((await a.api.get(`/api/identity/billing/invoices/${inv.id}`)).body.paid), 'nothing was taken').toBe(0);
    expect((await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'WIRE' })).status, 'companies pay by card or bank here').toBe(400);
    expect((await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'CARD', last4: '12' })).status).toBe(400);
    expect((await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'CARD', last4: '4242', amount: Number(inv.amount) + 1 })).status, 'more than is owed').toBe(400);

    const part = await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'CARD', last4: '4242', amount: 50 });
    expect(part.status).toBe(200);
    expect(part.body.status).toBe('PARTIAL');
    expect(Number(part.body.balance)).toBeCloseTo(Number(inv.amount) - 50, 2);
    const rest = await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'ACH', last4: '6789' });
    expect(rest.status).toBe(200);
    expect(rest.body.status).toBe('PAID');
    expect(rest.body.payments.map((p: any) => p.status).sort()).toEqual(['FAILED', 'SUCCEEDED', 'SUCCEEDED']);
    expect((await a.api.post(`/api/identity/billing/invoices/${inv.id}/pay`, { method: 'CARD', last4: '4242' })).status, 'already paid').toBe(409);
    // The failed attempt is on record, and the operator is told about it.
    const ledger = (await platform.get(`/api/identity/platform/billing/orgs/${a.orgId}`)).body;
    expect(ledger.payments.some((p: any) => p.status === 'FAILED' && p.failure_reason)).toBe(true);
    expect(Number(ledger.balance)).toBe(0);
    expect(Number(ledger.paid)).toBeCloseTo(Number(inv.amount), 2);
  });

  test('[FEES-03] the operator records a wire, a cheque or a credit, raises a one-off charge, and cannot void an invoice that has money against it', async () => {
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    const charge = await platform.post('/api/identity/platform/billing/invoices', { orgId: c.orgId, description: 'Data import and set-up help', amount: 450 });
    expect(charge.status).toBe(200);
    expect(charge.body.kind).toBe('ONE_OFF');
    expect(Number(charge.body.balance)).toBe(450);
    expect((await platform.post('/api/identity/platform/billing/invoices', { orgId: c.orgId, description: 'Free', amount: 0 })).status).toBe(400);
    expect((await platform.post('/api/identity/platform/billing/invoices', { orgId: c.orgId, amount: 10 })).status).toBe(400);
    expect((await platform.post('/api/identity/platform/billing/invoices', { orgId: '00000000-0000-0000-0000-000000000000', description: 'Nobody', amount: 10 })).status).toBe(404);

    const wire = await platform.post(`/api/identity/platform/billing/invoices/${charge.body.id}/payments`, { method: 'WIRE', amount: 200, reference: 'WIRE-5521' });
    expect(wire.status).toBe(200);
    expect(wire.body.status).toBe('PARTIAL');
    expect((await platform.post(`/api/identity/platform/billing/invoices/${charge.body.id}/void`, { reason: 'Changed my mind' })).status, 'it has a payment against it').toBe(409);
    expect((await platform.post(`/api/identity/platform/billing/invoices/${charge.body.id}/payments`, { method: 'CRYPTO' })).status).toBe(400);
    const credit = await platform.post(`/api/identity/platform/billing/invoices/${charge.body.id}/payments`, { method: 'CREDIT', note: 'Goodwill credit after the outage' });
    expect(credit.status).toBe(200);
    expect(credit.body.status).toBe('PAID');
    expect(credit.body.payments.map((p: any) => p.method).sort()).toEqual(['CREDIT', 'WIRE']);

    const second = await platform.post('/api/identity/platform/billing/invoices', { orgId: c.orgId, description: 'Raised in error', amount: 99 });
    expect((await platform.post(`/api/identity/platform/billing/invoices/${second.body.id}/void`, {})).status, 'a reason is needed').toBe(400);
    const voided = await platform.post(`/api/identity/platform/billing/invoices/${second.body.id}/void`, { reason: 'Raised in error' });
    expect(voided.body.status).toBe('VOID');
    expect((await platform.post(`/api/identity/platform/billing/invoices/${second.body.id}/payments`, { method: 'WIRE' })).status, 'a void invoice cannot be paid').toBe(409);
    const ledger = (await platform.get(`/api/identity/platform/billing/orgs/${c.orgId}`)).body;
    expect(ledger.invoices.find((i: any) => i.id === second.body.id).status).toBe('VOID');
    const owed = ledger.invoices.filter((i: any) => ['OPEN', 'PARTIAL'].includes(i.status)).reduce((n: number, i: any) => n + Number(i.balance), 0);
    expect(Number(ledger.balance), 'a voided invoice is not owed: the balance is the open invoices and nothing else').toBeCloseTo(owed, 2);
  });

  test('[FEES-04] the operator\'s summary adds up, and everything is closed to company people', async () => {
    const platform = await apiAs('platform');
    const c = await onboardCompany();
    await platform.post('/api/identity/platform/billing/run', {});
    const s = (await platform.get('/api/identity/platform/billing/summary')).body;
    for (const k of ['billedThisMonth', 'collectedThisMonth', 'collected30d', 'outstanding', 'overdue', 'overdueCompanies', 'failed30d', 'payingCompanies']) expect(s, k).toHaveProperty(k);
    expect(Number(s.billedThisMonth)).toBeGreaterThan(0);
    expect(Number(s.outstanding)).toBeGreaterThanOrEqual(Number(s.overdue));
    expect(s.trend).toHaveLength(6);
    expect(s.byPlan.every((p: any) => p.plan && Number(p.billed) >= 0)).toBe(true);
    const owing = (await platform.get('/api/identity/platform/billing/invoices?page=1&size=5&f.status=OPEN')).body;
    expect(owing.total).toBeGreaterThan(0);
    const sorted = rows(await platform.get('/api/identity/platform/billing/invoices?page=1&size=20&sort=amount&dir=desc'));
    for (let i = 1; i < sorted.length; i++) expect(Number(sorted[i - 1].amount)).toBeGreaterThanOrEqual(Number(sorted[i].amount));
    for (const path of ['/summary', '/invoices', '/payments', `/orgs/${c.orgId}`]) {
      expect((await c.api.get(`/api/identity/platform/billing${path}`)).status, path).toBe(403);
      expect((await (await apiAs('manager')).get(`/api/identity/platform/billing${path}`)).status, path).toBe(403);
    }
    expect((await c.api.post('/api/identity/platform/billing/run', {})).status).toBe(403);
    expect((await c.api.post('/api/identity/platform/billing/invoices', { orgId: c.orgId, description: 'Self', amount: 1 })).status).toBe(403);
    expect((await (await Api.create()).get('/api/identity/platform/billing/summary')).status).toBe(401);
    // A company's other people cannot pay for it, and the resident-facing roles have no subscription at all.
    expect((await (await apiAs('tenant')).get('/api/identity/billing/subscription')).status).toBe(403);
  });
});
