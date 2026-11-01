import { test, expect, needsWrites, pick } from '../../src/support/fixtures';
import { Api, rows } from '../../src/support/api';
import { createSignedInUser } from '../../src/support/company';
import { occupiedHome, vendorFor } from '../../src/support/scenarios';
import { until } from '../../src/support/builders';
import { cycleOn, firstOfMonth } from '../../src/support/journey';

/**
 * When things go wrong. Each test starts from a home somebody lives in and follows one thing that fails or is refused:
 * a declined card, a part payment, an autopay that bounces, an owner who says no to a repair, a refused signature,
 * a move-out where the deposit does not cover what is owed, and a link that is no good any more.
 * What matters in each is what the person sees and that nothing is lost or charged twice.
 */
test.describe('When things go wrong', () => {
  needsWrites();
  test.setTimeout(240_000);

  const my = async (api: Api) => (await api.get('/api/billing/my')).body;

  test('[JRNY-09] a declined card changes nothing, says so plainly, and a second card then works', async ({ pageWithToken }) => {
    const { resident } = await occupiedHome();
    const before = await my(resident.api);
    expect(Number(before.balance)).toBeGreaterThan(0);
    const page = await pageWithToken(resident.token, '/app/payments');

    await page.getByRole('button', { name: 'Add', exact: true }).click();
    let d = page.getByRole('dialog');
    await pick(page, d.getByRole('combobox').first(), /Debit or credit card/);
    await d.getByLabel('Last 4 digits').fill('0002');
    await d.getByRole('checkbox').last().check();
    await d.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('button', { name: /^Pay \$[\d,]+/ }).click();
    await expect(page.getByText(/payment was declined/i).first()).toBeVisible();

    const after = await my(resident.api);
    expect(Number(after.balance), 'nothing was charged').toBe(Number(before.balance));
    expect(after.payments.filter((p: any) => p.status === 'FAILED')).toHaveLength(1);
    expect(after.payments.filter((p: any) => p.status === 'SUCCEEDED')).toHaveLength(0);
    await expect(page.getByRole('row', { name: /Failed/i }).first()).toBeVisible();

    // A good card is added and chosen; this time the payment goes through.
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    d = page.getByRole('dialog');
    await pick(page, d.getByRole('combobox').first(), /Debit or credit card/);
    await d.getByLabel('Last 4 digits').fill('4242');
    await d.getByRole('checkbox').last().check();
    await d.getByRole('button', { name: 'Save' }).click();
    await pick(page, page.getByRole('combobox', { name: /Pay with/ }), /4242/);
    await page.getByRole('button', { name: /^Pay \$[\d,]+/ }).click();
    await expect(page.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
    const paid = await my(resident.api);
    expect(paid.payments.filter((p: any) => p.status === 'SUCCEEDED')).toHaveLength(1);
    expect(paid.payments.filter((p: any) => p.status === 'FAILED'), 'the failed attempt stays in the history').toHaveLength(1);
  });

  test('[JRNY-10] a part payment is applied, the rest stays due, and paying more than is owed is refused', async ({ pageWithToken }) => {
    const { resident } = await occupiedHome();
    await resident.api.post('/api/billing/my/methods', { type: 'ACH', last4: '6789', consent: true });
    const total = Number((await my(resident.api)).balance);
    const half = Math.floor(total / 2);
    const page = await pageWithToken(resident.token, '/app/payments');

    await page.getByLabel('Amount (USD)').fill(String(total + 500));
    await page.getByRole('button', { name: /^Pay \$/ }).click();
    await expect(page.getByText(/more than the \$/i).first()).toBeVisible();
    expect(Number((await my(resident.api)).balance), 'a refused payment changes nothing').toBe(total);

    await page.getByLabel('Amount (USD)').fill(String(half));
    await page.getByRole('button', { name: /^Pay \$/ }).click();
    await expect(page.getByText(/Payment received/i).first()).toBeVisible();
    const part = await my(resident.api);
    expect(Number(part.balance)).toBeCloseTo(total - half, 2);
    expect(part.invoices.some((i: any) => i.status === 'PARTIAL'), 'one charge is part paid').toBe(true);

    await page.getByLabel('Amount (USD)').fill('');
    await page.getByRole('button', { name: /^Pay \$/ }).click();
    await expect(page.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
    expect((await my(resident.api)).invoices.every((i: any) => i.status === 'PAID')).toBe(true);
  });

  test('[JRNY-11] an autopay that bounces is recorded, the rent stays due, and the accountant can see who to chase', async ({ pageWithToken }) => {
    const { c, p, resident, lease } = await occupiedHome();
    // The move-in charges are paid, so what is left to collect is the next rent.
    await resident.api.post('/api/billing/my/methods', { type: 'ACH', last4: '6789', consent: true });
    const settle = await resident.api.post('/api/billing/payments', { amount: Number((await my(resident.api)).balance), method: 'ACH', last4: '6789' });
    expect(settle.status, JSON.stringify(settle.body)).toBe(200);
    // An older charge is already past due, which is what Collections lists.
    const tenantId = (await c.api.get(`/api/leasing/leases/${lease.leaseId}`)).body.tenant_id;
    expect((await c.api.post('/api/billing/invoices', { tenantId, description: 'Parking fee', amount: 60, dueDate: firstOfMonth(new Date(), -2).toISOString().slice(0, 10) })).status).toBe(200);
    // Autopay is then switched on with an account that will be declined.
    const bad = await resident.api.post('/api/billing/my/methods', { type: 'ACH', last4: '0002', consent: true, autopay: true });
    expect(bad.status).toBe(200);

    const run = await cycleOn(c.api, firstOfMonth(new Date(), 2));
    expect(run.autopayCollected, 'the bank said no').toBe(0);
    const state = await my(resident.api);
    const failed = state.payments.filter((x: any) => x.status === 'FAILED');
    expect(failed.length).toBeGreaterThanOrEqual(1);
    expect(Number(state.balance), 'the rent is still owed').toBeGreaterThan(0);

    // The resident sees it on the page, and the accountant finds them in Collections.
    const mine = await pageWithToken(resident.token, '/app/payments');
    await expect(mine.getByRole('row', { name: /Failed/i }).first()).toBeVisible();
    const acct = await createSignedInUser(c.api, { email: `acct.${p.sfx}@e2e.test`, name: 'Alma Accountant', roleKey: 'ACCOUNTANT' });
    const page = await pageWithToken(acct.token, '/app/payments/collections');
    await expect(page.getByText('Rae Resident').first()).toBeVisible();
  });

  test('[JRNY-12] an owner says no to a repair and the resident sees why; a repair that was not fixed is reopened with a reason', async ({ pageWithToken }) => {
    const { c, p, resident } = await occupiedHome();
    const owner = await createSignedInUser(c.api, { email: `olivia.${p.sfx}@e2e.test`, name: 'Olivia Owner', roleKey: 'OWNER', partyType: 'OWNER', partyId: p.ownerId });
    const vendor = await vendorFor(c, p);
    await c.api.put(`/api/leasing/owners/${p.ownerId}`, { approvalLimit: 50 }); // anything costing more than this goes to the owner

    // 1. Costly work goes to the owner, who declines it on screen with a reason.
    const first = (await resident.api.post('/api/maintenance/work-orders', { title: 'Replace the water heater', description: 'It no longer heats.', permissionToEnter: true })).body.id;
    await until(() => c.api.get(`/api/maintenance/work-orders/${first}`), (r) => r.body.status !== 'SUBMITTED');
    if ((await c.api.get(`/api/maintenance/work-orders/${first}`)).body.status === 'TRIAGED') await c.api.post(`/api/maintenance/work-orders/${first}/actions/REQUEST_APPROVAL`, {});
    expect((await c.api.get(`/api/maintenance/work-orders/${first}`)).body.status).toBe('AWAITING_APPROVAL');
    const op = await pageWithToken(owner.token, `/app/maintenance/${first}`);
    await op.getByRole('button', { name: 'Decline', exact: true }).click();
    await op.getByRole('dialog').getByRole('textbox').fill('Too expensive this year. Repair it instead.');
    await op.getByRole('dialog').getByRole('button', { name: 'Decline', exact: true }).click();
    await expect.poll(async () => (await c.api.get(`/api/maintenance/work-orders/${first}`)).body.status).toBe('REJECTED');
    const rp = await pageWithToken(resident.token, `/app/maintenance/${first}`);
    await expect(rp.getByText(/Declined/).first()).toBeVisible();
    await expect(rp.getByText(/Too expensive this year/).first()).toBeVisible();
    expect((await owner.api.post(`/api/maintenance/work-orders/${first}/actions/ASSIGN`, { vendorId: vendor.id })).status, 'the owner cannot also assign it').toBeGreaterThanOrEqual(400);
    expect((await c.api.post(`/api/maintenance/work-orders/${first}/actions/START`, {})).status, 'a declined job cannot be started').toBe(409);

    // 2. A job that was done badly: the manager reopens it with a reason.
    const second = (await resident.api.post('/api/maintenance/work-orders', { title: 'Bathroom tap drips', description: 'Still dripping.', permissionToEnter: true })).body.id;
    await until(() => c.api.get(`/api/maintenance/work-orders/${second}`), (r) => r.body.status !== 'SUBMITTED');
    if ((await c.api.get(`/api/maintenance/work-orders/${second}`)).body.status === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${second}/actions/APPROVE`, {});
    await c.api.post(`/api/maintenance/work-orders/${second}/actions/ASSIGN`, { vendorId: vendor.id });
    await vendor.api.post(`/api/maintenance/work-orders/${second}/actions/START`, {});
    const done = await vendor.api.post(`/api/maintenance/work-orders/${second}/actions/COMPLETE`, { cost: 90, notes: 'Replaced the washer.' });
    expect(done.status).toBe(200);
    const mp = await pageWithToken(c.token, `/app/maintenance/${second}`);
    await mp.getByRole('button', { name: 'Reopen' }).click();
    await expect(mp.getByRole('dialog').getByRole('button', { name: 'Reopen' })).toBeDisabled();
    await mp.getByRole('dialog').getByRole('textbox').fill('The tap still drips after the visit.');
    await mp.getByRole('dialog').getByRole('button', { name: 'Reopen' }).click();
    await expect.poll(async () => (await c.api.get(`/api/maintenance/work-orders/${second}`)).body.status).toBe('ASSIGNED');
  });

  test('[JRNY-13] a signature that is declined closes the request, and a new request can still be signed', async ({ pageWithToken }) => {
    const { c, resident, lease } = await occupiedHome();
    const docs = rows(await c.api.get(`/api/documents?page=1&size=20&entityType=LEASE&entityId=${lease.leaseId}`));
    const target = docs.find((d: any) => /pdf/i.test(d.mime_type ?? '')) ?? docs[0];
    test.skip(!target, 'no lease document is filed for e-signature in this environment');
    const signer = (await c.api.get(`/api/documents/${target.id}/signers?q=Rae`)).body.rows.find((s: any) => s.name === 'Rae Resident');
    const send = () => c.api.post(`/api/documents/${target.id}/signature-requests`, { signers: [{ userId: signer.userId }], message: 'Please sign.' });

    const first = (await send()).body.id;
    const rp = await pageWithToken(resident.token, `/app/documents/signatures/${first}`);
    await rp.getByRole('button', { name: 'Decline', exact: true }).click();
    await expect(rp.getByRole('dialog').getByRole('button', { name: 'Decline', exact: true })).toBeDisabled();
    await rp.getByRole('dialog').getByRole('textbox').fill('The rent figure in this copy is wrong.');
    await rp.getByRole('dialog').getByRole('button', { name: 'Decline', exact: true }).click();
    await expect.poll(async () => (await c.api.get(`/api/documents/signatures/${first}`)).body.status).toBe('DECLINED');
    const late = await resident.api.post(`/api/documents/signatures/${first}/sign`, { signature: 'Rae Resident', consent: true });
    expect(late.status, 'a declined request cannot be signed afterwards').toBeGreaterThanOrEqual(400);
    expect(JSON.stringify((await c.api.get(`/api/documents/signatures/${first}`)).body)).toMatch(/rent figure in this copy is wrong/);

    const second = (await send()).body.id;
    const sp = await pageWithToken(resident.token, `/app/documents/signatures/${second}`);
    await sp.getByLabel(/Type your full legal name/).fill('Rae Resident');
    await sp.getByRole('checkbox').check();
    await sp.getByRole('button', { name: 'Sign document' }).click();
    await expect.poll(async () => (await c.api.get(`/api/documents/signatures/${second}`)).body.status).toBe('COMPLETED');
  });

  test('[JRNY-14] moving out: the deposit cannot be settled early or over-deducted, then the refund is what is left', async ({ pageWithToken }) => {
    const { c, resident, lease } = await occupiedHome();
    await resident.api.post('/api/billing/my/methods', { type: 'ACH', last4: '6789', consent: true });
    const settle = await resident.api.post('/api/billing/payments', { amount: Number((await my(resident.api)).balance), method: 'ACH', last4: '6789' });
    expect(settle.status, JSON.stringify(settle.body)).toBe(200);
    const deposit = () => c.api.get('/api/billing/deposits').then((r) => rows(r).find((x: any) => x.lease_id === lease.leaseId));
    const dep = await until(deposit, (x) => !!x);
    const held = Number(dep.held);
    expect(held).toBeGreaterThan(0);

    const early = await c.api.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [] });
    expect(early.status, 'the deposit waits for the move-out').toBe(409);
    expect(JSON.stringify(early.body)).toMatch(/after the tenant moves out/i);

    for (const a of ['GIVE_NOTICE', 'END']) expect((await c.api.post(`/api/leasing/leases/${lease.leaseId}/actions/${a}`, {})).status, a).toBe(200);
    await until(deposit, (x) => x?.status === 'REFUND_DUE');

    const tooMuch = await c.api.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [{ description: 'Repaint everything', amount: held + 1 }] });
    expect(tooMuch.status).toBe(400);
    expect(JSON.stringify(tooMuch.body)).toMatch(/exceed/i);
    expect((await c.api.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [{ description: '', amount: 10 }] })).status).toBe(400);
    expect((await c.api.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [{ description: 'Nothing', amount: 0 }] })).status).toBe(400);
    expect((await deposit()).status, 'refused attempts leave the deposit alone').toBe('REFUND_DUE');

    const page = await pageWithToken(c.token, '/app/payments/deposits');
    await expect(page.getByText('Rae Resident').first()).toBeVisible();
    const ok = await c.api.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [{ description: 'Carpet cleaning', amount: 85 }] });
    expect(ok.status).toBe(200);
    const after = await deposit();
    expect(after.status).toBe('SETTLED');
    expect(Number(after.refund)).toBeCloseTo(held - 85, 2);
    expect((await c.api.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [] })).status, 'settling twice is refused').toBe(409);
    await page.reload();
    await expect(page.getByText(/Settled/i).first()).toBeVisible();
  });

  test('[JRNY-15] a link that is wrong, changed or expired shows a clear page, not a blank one', async ({ page }) => {
    const { c, lease } = await occupiedHome();
    const link = (await c.api.post(`/api/leasing/applications/${lease.appId}/upload-link`, {})).body.path as string;
    const [path, query] = link.split('?');
    const token = new URLSearchParams(query).get('token')!;

    for (const bad of ['wrong', `${token}x`, `zz${token}`]) {
      await page.goto(`${path}?token=${bad}`);
      await expect(page.getByText(/isn.t valid|has expired/i).first(), `the link with token "${bad}"`).toBeVisible();
    }
  });
});
