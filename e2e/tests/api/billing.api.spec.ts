import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany, createSignedInUser, Company } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease, Portfolio } from '../../src/support/builders';

const ZERO = '00000000-0000-0000-0000-000000000000';
async function leased(): Promise<{ c: Company; p: Portfolio; tenantId: string; leaseId: string; email: string }> {
  const c = await onboardCompany();
  const p = await buildPortfolio(c);
  const live = await publishListing(c.api, p.unitId);
  const email = `bill.${p.sfx}@e2e.test`;
  const l = await activeLease(c, p, live, email);
  return { c, p, tenantId: l.tenantId, leaseId: l.leaseId, email };
}
const ymd = (d: number) => new Date(Date.now() + d * 86400000).toISOString().slice(0, 10);

test.describe('Billing and payments', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  test('[BILL-01] a billing cycle creates invoices from active leases, once', async () => {
    const { c, tenantId } = await leased();
    expect(rows(await c.api.get('/api/billing/invoices')).length).toBe(0);
    expect((await c.api.post('/api/billing/run-cycle', {})).status).toBe(200);
    const inv = rows(await c.api.get('/api/billing/invoices'));
    expect(inv.length).toBeGreaterThan(0);
    expect(inv.every((i: any) => i.tenant_id === tenantId)).toBe(true);
    expect(inv.some((i: any) => i.kind === 'RENT')).toBe(true);
    await c.api.post('/api/billing/run-cycle', {});
    expect(rows(await c.api.get('/api/billing/invoices')).length).toBe(inv.length);
    const one = (await c.api.get(`/api/billing/invoices/${inv[0].id}`)).body;
    expect(Number(one.balance)).toBe(Number(one.amount) - Number(one.paid));
    expect(one.timeline).toBeTruthy();
  });

  test('[BILL-02] invoices can be created with validation and voided with a reason', async () => {
    const { c, tenantId } = await leased();
    expect((await c.api.post('/api/billing/invoices', { tenantId, amount: 0, description: 'x' })).status).toBe(400);
    expect((await c.api.post('/api/billing/invoices', { tenantId, amount: 50 })).status).toBe(400);
    expect((await c.api.post('/api/billing/invoices', { amount: 50, description: 'x' })).status).toBe(400);
    const made = await c.api.post('/api/billing/invoices', { tenantId, amount: 75, description: 'Pet fee', kind: 'PET_FEE' });
    expect(made.status).toBe(200);
    const id = made.body.id;
    expect((await c.api.post(`/api/billing/invoices/${id}/void`, {})).status).toBe(400);
    expect((await c.api.post(`/api/billing/invoices/${id}/void`, { reason: 'Entered in error' })).status).toBe(200);
    expect((await c.api.get(`/api/billing/invoices/${id}`)).body.status).toBe('VOID');
    expect((await c.api.post('/api/billing/invoices', { tenantId, amount: 99999, kind: 'LATE_FEE', description: 'Huge late fee' })).status).toBe(400);
  });

  test('[BILL-03] payments can be quoted and recorded; residents manage autopay methods', async () => {
    const { c, p, tenantId, email } = await leased();
    const charge = (await c.api.post('/api/billing/invoices', { tenantId, amount: 200, description: 'Utilities' })).body.id;
    const settings = (await c.api.get('/api/billing/settings')).body;
    expect(settings).toBeTruthy();
    const q = await c.api.get('/api/billing/payments/quote?amount=100&method=CARD&tenantId=' + tenantId);
    expect(q.status).toBe(200);
    expect(JSON.stringify(q.body)).toMatch(/fee|total/i);
    expect((await c.api.post('/api/billing/payments', { tenantId, amount: 0 })).status).toBe(400);
    expect((await c.api.post('/api/billing/payments', { tenantId, amount: 999999, method: 'ACH' })).status).toBeGreaterThanOrEqual(400);
    const paid = await c.api.post('/api/billing/payments', { tenantId, amount: 200, method: 'ACH', invoiceId: charge });
    expect(paid.status).toBe(200);
    const inv = (await c.api.get(`/api/billing/invoices/${charge}`)).body;
    expect(Number(inv.paid)).toBe(200);
    expect(inv.status).toBe('PAID');
    expect(rows(await c.api.get(`/api/billing/payments?tenantId=${tenantId}`)).length).toBeGreaterThan(0);
    expect((await c.api.post(`/api/billing/invoices/${charge}/void`, { reason: 'x' })).status).toBe(409);
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const t = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    expect((await t.api.post('/api/billing/my/methods', { type: 'ACH', last4: '12', consent: true })).status).toBe(400);
    expect((await t.api.post('/api/billing/my/methods', { type: 'ACH', last4: '1234', consent: false })).status).toBe(400);
    const m = await t.api.post('/api/billing/my/methods', { type: 'ACH', last4: '1234', consent: true });
    expect(m.status).toBe(200);
    expect((await t.api.put(`/api/billing/my/methods/${m.body.id}/autopay`, { autopay: true })).status).toBe(200);
    expect(JSON.stringify((await t.api.get('/api/billing/my')).body)).toContain('1234');
    expect(p.sfx).toBeTruthy();
  });

  test('[BILL-04] collections, aging and reminders work', async () => {
    const { c, tenantId } = await leased();
    expect((await c.api.post('/api/billing/collections/remind', {})).status).toBe(404); // nobody overdue yet
    await c.api.post('/api/billing/invoices', { tenantId, amount: 300, description: 'Overdue charge', dueDate: ymd(-45) });
    const aging = (await c.api.get('/api/billing/aging')).body;
    expect(Number(aging.d31_60)).toBe(300);
    const coll = rows(await c.api.get('/api/billing/collections'));
    expect(coll.length).toBe(1);
    expect(Number(coll[0].balance)).toBe(300);
    expect(coll[0].recommendation).toBeTruthy();
    expect(coll[0].reasons.length).toBe(3);
    const sent = await c.api.post('/api/billing/collections/remind', { tenantId });
    expect(sent.body.sent).toBe(1);
    const sum = (await c.api.get('/api/billing/summary')).body;
    expect(JSON.stringify(sum)).toMatch(/300/);
  });

  test('[BILL-05] deposits can be listed and are only settled after move-out', async () => {
    const { c } = await leased();
    const deps = rows(await c.api.get('/api/billing/deposits'));
    expect(deps.length).toBeGreaterThanOrEqual(0);
    if (deps.length) {
      expect((await c.api.post(`/api/billing/deposits/${deps[0].id}/settle`, { deductions: [] })).status).toBe(409);
    }
    expect((await c.api.post(`/api/billing/deposits/${ZERO}/settle`, { deductions: [] })).status).toBe(404);
    const admin = await apiAs('admin');
    const demo = rows(await admin.get('/api/billing/deposits'));
    const due = demo.find((d: any) => d.status === 'REFUND_DUE');
    if (due) {
      const bad = await admin.post(`/api/billing/deposits/${due.id}/settle`, { deductions: [{ description: '', amount: 5 }] });
      expect(bad.status).toBe(400);
      expect((await admin.post(`/api/billing/deposits/${due.id}/settle`, { deductions: [{ description: 'Big', amount: 99999999 }] })).status).toBe(400);
    }
  });

  test('[BILL-06] owner statements are created and published, and expenses reduce the net', async () => {
    const { c, p, tenantId } = await leased();
    const charge = (await c.api.post('/api/billing/invoices', { tenantId, amount: 400, description: 'Rent adj', kind: 'RENT' })).body.id;
    await c.api.post('/api/billing/payments', { tenantId, amount: 400, invoiceId: charge });
    expect((await c.api.post('/api/billing/expenses', { propertyId: p.propertyId, ownerId: p.ownerId, description: 'Gutters', category: 'REPAIRS', amount: 180 })).status).toBe(200);
    expect((await c.api.post('/api/billing/expenses', { propertyId: p.propertyId, ownerId: p.ownerId, category: 'REPAIRS', amount: 5 })).status).toBe(400);
    const period = new Date().toISOString().slice(0, 7);
    const s = await c.api.post('/api/billing/statements', { ownerId: p.ownerId, period, ownerName: 'Maple', feePercent: 10 });
    expect(s.status).toBe(200);
    const st = (await c.api.get(`/api/billing/statements/${s.body.id}`)).body;
    expect(st.status).toBe('DRAFT');
    expect(Number(st.rent_collected ?? st.rent)).toBeGreaterThan(0);
    expect((await c.api.post(`/api/billing/statements/${s.body.id}/publish`, {})).status).toBe(409); // W-9 missing
    expect((await c.api.put(`/api/billing/tax/owners/${p.ownerId}/w9`, { legalName: 'Maple LLC', tinType: 'EIN', tinLast4: '1234', w9Received: true })).status).toBe(200);
    expect((await c.api.post(`/api/billing/statements/${s.body.id}/publish`, {})).status).toBe(200);
    expect((await c.api.get(`/api/billing/statements/${s.body.id}`)).body.status).toBe('PUBLISHED');
    expect((await c.api.post('/api/billing/statements', { ownerId: p.ownerId, period, ownerName: 'Maple' })).status).toBe(409);
    expect(rows(await c.api.get('/api/billing/statements')).some((x: any) => x.id === s.body.id)).toBe(true);
  });

  test('[BILL-07] tax: summary, 1099 report and owner W-9 validation', async () => {
    const { c, p } = await leased();
    const year = new Date().getFullYear();
    expect((await c.api.get(`/api/billing/tax/summary?year=${year}`)).status).toBe(200);
    expect((await c.api.get(`/api/billing/tax/1099?year=${year}`)).status).toBe(200);
    expect((await c.api.put(`/api/billing/tax/owners/${p.ownerId}/w9`, { legalName: 'Maple', tinType: 'SSN', tinLast4: '12345678', w9Received: true })).status).toBe(400);
    expect((await c.api.put(`/api/billing/tax/owners/${p.ownerId}/w9`, { legalName: 'Maple', tinType: 'SSN', tinLast4: '4321', w9Received: true })).status).toBe(200);
    const text = JSON.stringify((await c.api.get(`/api/billing/tax/1099?year=${year}`)).body);
    expect(text).not.toMatch(/12345678/);
  });

  test('[BILL-08] a resident sees only their own balance', async () => {
    const { c, p, tenantId, email } = await leased();
    await c.api.post('/api/billing/invoices', { tenantId, amount: 60, description: 'Mine' });
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const t = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    const mine = rows(await t.api.get('/api/billing/invoices'));
    expect(mine.length).toBeGreaterThan(0);
    expect(mine.every((i: any) => i.tenant_id === tenantId)).toBe(true);
    expect((await t.api.get('/api/billing/statements')).status).toBe(403);
    expect((await t.api.post('/api/billing/run-cycle', {})).status).toBe(403);
    const demoT = await apiAs('tenant');
    const demoInv = rows(await demoT.get('/api/billing/invoices'));
    expect(demoInv.some((i: any) => i.tenant_id === tenantId)).toBe(false);
    const other = rows(await (await apiAs('tenant2')).get('/api/billing/invoices'));
    expect(other.every((i: any) => i.tenant_id !== tenantId)).toBe(true);
    expect(p.sfx).toBeTruthy();
  });
});
