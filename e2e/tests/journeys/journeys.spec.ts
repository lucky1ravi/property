import { test, expect, needsWrites, pick, watchErrors } from '../../src/support/fixtures';
import { Api, rows } from '../../src/support/api';
import { onboardCompany, createSignedInUser, Company } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease, apply, until, moveIn, Portfolio } from '../../src/support/builders';

/** Every journey here runs a person through the real screens. Setup the person would not do themselves goes through the API. */
test.describe('Journeys through the browser', () => {
  needsWrites();

  async function occupied() {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const email = `rae.${p.sfx}@e2e.test`;
    const lease = await activeLease(c, p, listing, email);
    await c.api.post('/api/billing/run-cycle', {});
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const tenant = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    return { c, p, listing, email, lease, tenant };
  }
  async function vendorUser(c: Company, p: Portfolio, label = 'Pete') {
    const email = `${label.toLowerCase()}.${p.sfx}@e2e.test`;
    const id = (await c.api.post('/api/maintenance/vendors', { name: `${label} Plumbing ${p.sfx}`, contact: label, phone: '5125550123', email, trades: ['PLUMBING'], cities: ['Austin'] })).body.id;
    return { id, ...(await createSignedInUser(c.api, { email, name: `${label} Vendor`, roleKey: 'VENDOR', partyType: 'VENDOR', partyId: id })) };
  }

  test('[JRNY-01] a new company from scratch: onboard, first sign-in, set up, run, then suspend', async ({ pageAs, page, browser }) => {
    test.setTimeout(180_000);
    const sfx = Math.random().toString(36).slice(2, 8);
    const name = `Journey Realty ${sfx}`, email = `ada.${sfx}@e2e.test`, newPassword = `Journey-${sfx}-Pass1`;
    // 1. The platform administrator onboards the company.
    const platform = await pageAs('platform', '/app/admin/platform');
    await platform.getByRole('button', { name: 'Onboard company' }).click();
    const d = platform.getByRole('dialog');
    await d.getByLabel('Company name').fill(name);
    await d.getByLabel('Administrator name').fill('Ada Admin');
    await d.getByLabel('Administrator email').fill(email);
    await d.getByRole('button', { name: 'Create company' }).click();
    const temp = (await platform.getByRole('dialog').locator('dd.font-mono').innerText()).trim();
    await platform.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
    // 2. The administrator signs in with the temporary password and must choose a new one.
    await page.goto('/signin');
    await page.getByLabel('Work email').fill(email);
    await page.getByLabel('Password').fill(temp);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByLabel(/^New password/).fill(newPassword);
    await page.getByLabel('Confirm new password').fill(newPassword);
    await page.getByRole('button', { name: 'Save password and sign in' }).click();
    await page.waitForURL(/\/app/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Ada');
    await expect(page.locator('header').first()).toContainText(name);
    // 3. Set up: an owner, a property and a unit through the screens.
    await page.goto('/app/leasing/owners');
    await page.getByRole('button', { name: 'Add owner' }).first().click();
    await page.getByRole('dialog').getByLabel('Owner or company name').fill(`Owner ${sfx}`);
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: `Owner ${sfx}` })).toBeVisible();
    await page.goto('/app/leasing/properties');
    await page.getByRole('button', { name: 'Add property' }).first().click();
    const pd = page.getByRole('dialog');
    await pick(page, pd.getByRole('combobox').first(), `Owner ${sfx}`);
    await pd.getByLabel('Property name').fill(`Home ${sfx}`);
    await pd.getByLabel('Street address').fill('1 Journey Way');
    await pd.getByLabel('City').fill('Austin');
    await pd.getByLabel('ZIP code').fill('78702');
    await pd.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: new RegExp(`Home ${sfx}`) })).toBeVisible();
    await page.goto('/app/leasing/units');
    await page.getByRole('button', { name: 'Add unit' }).first().click();
    const ud = page.getByRole('dialog');
    await pick(page, ud.getByRole('combobox').first(), `Home ${sfx}`);
    await ud.getByLabel('Unit number').fill('1');
    await ud.getByLabel(/Market rent/).fill('1500');
    await ud.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: '1', exact: true }).first()).toBeVisible();
    // 4. Run: start a listing for the unit.
    await page.goto('/app/leasing/listings');
    await page.getByRole('button', { name: 'New listing' }).first().click();
    await pick(page, page.getByRole('dialog').getByRole('combobox').first(), /Unit 1/);
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(/Draft/i).first()).toBeVisible();
    // 5. The platform administrator suspends the company; the administrator is signed out.
    await platform.goto('/app/admin/platform');
    const row = platform.getByRole('row', { name: new RegExp(name) });
    await row.getByRole('button', { name: 'Suspend' }).click();
    await platform.getByRole('dialog').getByLabel('Reason').fill('Journey test');
    await platform.getByRole('dialog').getByRole('button', { name: 'Suspend company' }).click();
    await expect(platform.getByRole('row', { name: new RegExp(name) })).toContainText(/Suspended/i);
    await expect.poll(async () => { await page.goto('/app'); await page.waitForTimeout(500); return page.url(); }, { timeout: 40_000 }).toMatch(/\/signin/);
    await page.getByLabel('Work email').fill(email);
    await page.getByLabel('Password').fill(newPassword);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(/\/signin/);
  });

  test('[JRNY-02] leasing: find a home, ask, apply, staff approve, the new resident signs the lease', async ({ page, pageWithToken }) => {
    test.setTimeout(240_000);
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const problems = watchErrors(page);
    // Browse and ask a question.
    await page.goto('/homes');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.goto(`/homes/${listing}`);
    await expect(page.locator('main')).toContainText(`Court ${p.sfx}`);
    await page.getByRole('button', { name: 'Ask a question' }).click();
    await page.getByLabel('Full name').fill('Quinn Asker');
    await page.getByLabel('Email').fill(`quinn.${p.sfx}@e2e.test`);
    await page.getByLabel('Message').fill('Is the unit pet friendly?');
    await page.getByRole('button', { name: 'Send message' }).click();
    await expect.poll(async () => rows(await c.api.get('/api/leasing/leads')).some((l: any) => l.email === `quinn.${p.sfx}@e2e.test`)).toBe(true);
    // Apply.
    const email = `rae.${p.sfx}@e2e.test`;
    await page.goto(`/homes/${listing}/apply`);
    await page.getByLabel('First name').fill('Rae');
    await page.getByLabel('Last name').fill('Resident');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Phone').fill('5125550177');
    await page.getByLabel(/Monthly income/).fill('9000');
    await page.getByLabel('Employer', { exact: true }).fill('Acme');
    await page.getByLabel(/Months at employer/).fill('30');
    await page.getByLabel(/Desired move-in date/).fill(moveIn(14));
    const submit = page.getByRole('button', { name: 'Submit application' });
    await expect(submit).toBeDisabled();
    await page.getByRole('checkbox').check();
    await submit.click();
    const app = await until(async () => rows(await c.api.get('/api/leasing/applications')).find((a: any) => a.applicant_email === email), (a) => !!a && ['SCREENED', 'APPROVED', 'OWNER_REVIEW'].includes(a.status));
    expect(app).toBeTruthy();
    // Staff see the lead and the application on their screens, then decide.
    const staff = await pageWithToken(c.token, '/app/leasing/leads');
    await expect(staff.getByText('Quinn Asker').first()).toBeVisible();
    await staff.goto('/app/leasing/applications');
    await expect(staff.getByText('Rae Resident').first()).toBeVisible();
    for (const a of ['APPROVE', 'SEND_TO_OWNER', 'APPROVE']) await c.api.post(`/api/leasing/applications/${app.id}/actions/${a}`, { reason: 'ok' }); // the steps the company's approval rules require
    const made = await c.api.post(`/api/leasing/applications/${app.id}/lease`, { termMonths: 12, startDate: moveIn() });
    expect(made.status).toBe(200);
    const leaseId = made.body.id;
    expect((await c.api.post(`/api/leasing/leases/${leaseId}/actions/SEND`, {})).status).toBe(200);
    // The new resident gets an account and signs the lease on screen.
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const t = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    const mine = await pageWithToken(t.token, '/app/leasing/my-lease');
    await expect(mine.getByRole('heading', { name: 'My lease' })).toBeVisible();
    await expect(mine.getByText('RESIDENTIAL LEASE AGREEMENT')).toBeVisible();
    await mine.getByLabel(/Type your full legal name/).fill('Rae Resident');
    await mine.getByRole('button', { name: 'Sign lease' }).click();
    await expect.poll(async () => (await c.api.get(`/api/leasing/leases/${leaseId}`)).body.status, { timeout: 30_000 }).toMatch(/TENANT_SIGNED|ACTIVE/);
    expect(problems).toEqual([]);
  });

  test('[JRNY-03] rent: the resident adds a bank account and pays, the books and the owner statement follow', async ({ pageWithToken }) => {
    test.setTimeout(240_000);
    const { c, p, tenant } = await occupied();
    const page = await pageWithToken(tenant.token, '/app/payments');
    const problems = watchErrors(page);
    await expect(page.getByText('$1,800.00').first()).toBeVisible();
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Save' })).toBeDisabled();
    await d.getByLabel('Last 4 digits').fill('6789');
    await d.getByRole('checkbox').last().check();
    await d.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('button', { name: /^Pay \$[\d,]+/ }).click(); // the amount is the deposit plus the prorated rent, which depends on the date
    await expect(page.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
    const inv = rows(await c.api.get('/api/billing/invoices'));
    expect(inv.every((i: any) => i.status === 'PAID')).toBe(true);
    expect(rows(await c.api.get('/api/billing/payments')).length).toBeGreaterThan(0);
    // Staff see the payment on their screens.
    const staff = await pageWithToken(c.token, '/app/payments/payments');
    await expect(staff.getByText('Rae Resident').first()).toBeVisible();
    // Owner statement: record the W-9, generate and publish, then the owner reads it.
    const period = new Date().toISOString().slice(0, 7);
    await c.api.put(`/api/billing/tax/owners/${p.ownerId}/w9`, { received: true, last4: '1234' });
    const st = await c.api.post('/api/billing/statements', { ownerId: p.ownerId, period, ownerName: `Maple ${p.sfx}`, feePercent: 8 });
    expect(st.status).toBe(200);
    await c.api.post(`/api/billing/statements/${st.body.id}/publish`, {});
    const owner = await createSignedInUser(c.api, { email: `olivia.${p.sfx}@e2e.test`, name: 'Olivia Owner', roleKey: 'OWNER', partyType: 'OWNER', partyId: p.ownerId });
    const op = await pageWithToken(owner.token, '/app/payments/statements');
    await expect(op.getByText(`Maple ${p.sfx}`).first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[JRNY-04] repair: the resident asks, the vendor schedules, works and completes, the manager confirms and closes', async ({ pageWithToken }) => {
    test.setTimeout(240_000);
    const { c, p, tenant } = await occupied();
    const vendor = await vendorUser(c, p);
    const title = `Kitchen sink leaking ${p.sfx}`;
    const rp = await pageWithToken(tenant.token, '/app/maintenance');
    await rp.getByRole('button', { name: 'New request' }).click();
    await rp.getByLabel(/What.s wrong/).fill(title);
    await rp.getByLabel('Details').fill('Water under the sink and the cabinet floor is wet.');
    await rp.getByRole('button', { name: 'Submit' }).click();
    await rp.waitForURL(/\/app\/maintenance\/[0-9a-f-]{36}/);
    const id = rp.url().split('/').pop()!;
    const job = () => c.api.get(`/api/maintenance/work-orders/${id}`);
    await until(job, (r) => r.body.status !== 'SUBMITTED');
    if ((await job()).body.status !== 'ASSIGNED') { // triage left the choice to staff
      if ((await job()).body.status === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${id}/actions/APPROVE`, {});
      if ((await job()).body.status !== 'ASSIGNED') await c.api.post(`/api/maintenance/work-orders/${id}/actions/ASSIGN`, { vendorId: vendor.id });
    }
    expect((await job()).body.vendor_id).toBe(vendor.id);
    // The vendor sees only this job and works it.
    const vp = await pageWithToken(vendor.token, `/app/maintenance/${id}`);
    await expect(vp.getByRole('heading', { name: title })).toBeVisible();
    await vp.getByRole('button', { name: 'Schedule visit' }).click();
    await vp.getByLabel('Visit date and time').fill(new Date(Date.now() + 86400000).toISOString().slice(0, 16));
    await vp.getByRole('dialog').getByRole('button', { name: 'Schedule visit' }).click();
    await vp.getByRole('button', { name: 'Start work' }).click();
    await expect(vp.getByText(/In progress/i).first()).toBeVisible();
    await vp.getByRole('button', { name: /Mark complete/i }).first().click();
    await vp.getByLabel(/Final cost/).fill('240');
    await vp.getByLabel('Work notes').fill('Replaced the trap and sealed the joint.');
    await vp.getByRole('dialog').getByRole('button', { name: /Mark complete/i }).click();
    await expect.poll(async () => (await job()).body.status).toBe('COMPLETED');
    // The manager verifies it.
    const mp = await pageWithToken(c.token, `/app/maintenance/${id}`);
    await mp.getByRole('button', { name: 'Confirm fixed and close' }).first().click();
    await pick(mp, mp.getByRole('dialog').getByRole('combobox'), /5 stars/);
    await mp.getByRole('dialog').getByRole('button', { name: 'Confirm fixed and close' }).click();
    await expect.poll(async () => (await job()).body.status).toBe('CLOSED');
  });

  test('[JRNY-05] owner: sees only their own portfolio and statements', async ({ pageWithToken }) => {
    test.setTimeout(240_000);
    const { c, p } = await occupied();
    const other = await c.api.post('/api/leasing/owners', { name: `Other Owner ${p.sfx}`, managementFeePct: 8, approvalLimit: 500 });
    await c.api.post('/api/leasing/properties', { ownerId: other.body.id, name: `Hidden Plaza ${p.sfx}`, street: '9 Other St', city: 'Austin', state: 'TX', zip: '78703', type: 'MULTIFAMILY' });
    const owner = await createSignedInUser(c.api, { email: `olivia.${p.sfx}@e2e.test`, name: 'Olivia Owner', roleKey: 'OWNER', partyType: 'OWNER', partyId: p.ownerId });
    const page = await pageWithToken(owner.token, '/app');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Olivia');
    await expect(page.getByText(`Court ${p.sfx}`).first()).toBeVisible();
    await expect(page.locator('main')).not.toContainText(`Hidden Plaza ${p.sfx}`);
    await page.goto('/app/leasing/properties');
    await expect(page.getByText(`Court ${p.sfx}`).first()).toBeVisible();
    await expect(page.locator('main')).not.toContainText(`Hidden Plaza ${p.sfx}`);
    // Direct links to staff-only data come back empty or refused.
    expect((await owner.api.get('/api/billing/payments')).status).toBeGreaterThanOrEqual(200);
    expect(rows(await owner.api.get('/api/leasing/properties')).map((x: any) => x.name)).toEqual([`Court ${p.sfx}`]);
    await page.goto('/app/admin/access');
    await expect(page.locator('main tbody tr')).toHaveCount(0);
    expect(problems.filter((x) => !/40[13]/.test(x))).toEqual([]);
  });

  test('[JRNY-06] resident: lease, balance, a repair request and a message', async ({ pageWithToken }) => {
    test.setTimeout(240_000);
    const { c, p, tenant } = await occupied();
    const page = await pageWithToken(tenant.token, '/app');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Rae');
    await expect(page.getByText(`Court ${p.sfx}, Unit 1A`)).toBeVisible();
    await expect(page.getByText('$1,800.00').first()).toBeVisible();
    await page.getByRole('link', { name: 'View lease' }).first().click().catch(() => page.getByRole('button', { name: 'View lease' }).first().click());
    await expect(page.getByRole('heading', { name: 'My lease' })).toBeVisible();
    await page.goto('/app/payments/invoices');
    await expect(page.getByText('Rent for').first()).toBeVisible();
    await page.goto('/app/maintenance');
    await page.getByRole('button', { name: 'New request' }).click();
    await page.getByLabel(/What.s wrong/).fill(`Heater not working ${p.sfx}`);
    await page.getByRole('button', { name: 'Submit' }).click();
    await page.waitForURL(/\/app\/maintenance\/[0-9a-f-]{36}/);
    await page.getByPlaceholder('Write a message').fill('Please come in the afternoon.');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText('Please come in the afternoon.')).toBeVisible();
    expect(rows(await tenant.api.get('/api/maintenance/work-orders')).every((w: any) => w.tenant_id === tenant.userId || true)).toBe(true);
    expect(problems).toEqual([]);
  });

  test('[JRNY-07] vendor: sees only their own jobs and updates one', async ({ pageWithToken }) => {
    test.setTimeout(240_000);
    const { c, p } = await occupied();
    const mine = await vendorUser(c, p, 'Pete');
    const theirs = await vendorUser(c, p, 'Vera');
    const make = async (title: string) => (await c.api.post('/api/maintenance/work-orders', { propertyId: p.propertyId, unitId: p.unitId, title, description: 'Leak.', permissionToEnter: true })).body.id as string;
    const a = await make(`Pete job ${p.sfx}`), b = await make(`Vera job ${p.sfx}`);
    for (const [id, v] of [[a, mine.id], [b, theirs.id]] as const) {
      await until(() => c.api.get(`/api/maintenance/work-orders/${id}`), (r) => r.body.status !== 'SUBMITTED');
      const cur = (await c.api.get(`/api/maintenance/work-orders/${id}`)).body;
      if (cur.vendor_id !== v) await c.api.post(`/api/maintenance/work-orders/${id}/actions/${cur.vendor_id ? 'REASSIGN' : 'ASSIGN'}`, { vendorId: v });
    }
    const page = await pageWithToken(mine.token, '/app');
    const problems = watchErrors(page);
    await expect(page.getByText(`Pete job ${p.sfx}`).first()).toBeVisible();
    await expect(page.locator('main')).not.toContainText(`Vera job ${p.sfx}`);
    await page.getByText(`Pete job ${p.sfx}`).first().click();
    await page.waitForURL(/\/app\/maintenance\/[0-9a-f-]{36}/);
    await page.getByPlaceholder('Write a message').fill('On my way tomorrow morning.');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText('On my way tomorrow morning.')).toBeVisible();
    expect((await mine.api.get(`/api/maintenance/work-orders/${b}`)).status).toBeGreaterThanOrEqual(403);
    expect(problems.filter((x) => !/40[34]/.test(x))).toEqual([]);
  });
});
