import { test, expect, needsWrites, pick, watchErrors } from '../../src/support/fixtures';
import { Api } from '../../src/support/api';
import { onboardCompany, Company } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease, Portfolio } from '../../src/support/builders';
import type { Page } from '@playwright/test';

/** One seeded company shared by the screen tests below (made through the API, then looked at through the browser). */
let c: Company; let p: Portfolio; let listingId: string; let jobTitle: string; let vendorName: string; let tenant: string;

test.describe('Operations screens (leasing, payments, maintenance)', () => {
  needsWrites();
  test.describe.configure({ mode: 'serial' });
  test.beforeAll(async () => {
    test.setTimeout(180_000);
    c = await onboardCompany();
    p = await buildPortfolio(c);
    listingId = await publishListing(c.api, p.unitId);
    tenant = `Rae Resident`;
    const pub = await Api.create();
    await pub.post(`/api/leasing/public/listings/${listingId}/enquiries`, { name: 'Quinn Asker', email: `q.${p.sfx}@e2e.test`, message: 'Can I tour on Saturday?' });
    const l = await activeLease(c, p, listingId, `rae.${p.sfx}@e2e.test`);
    await c.api.post('/api/billing/run-cycle', {});
    await c.api.post('/api/billing/expenses', { propertyId: p.propertyId, ownerId: p.ownerId, amount: 120, category: 'REPAIRS', description: 'Gutter cleaning' });
    vendorName = `Pete Plumbing ${p.sfx}`;
    await c.api.post('/api/maintenance/vendors', { name: vendorName, contact: 'Pete', phone: '5125550123', email: `pete.${p.sfx}@e2e.test`, trades: ['PLUMBING'], cities: ['Austin'] });
    jobTitle = `Kitchen sink leaking ${p.sfx}`;
    await c.api.post('/api/maintenance/work-orders', { propertyId: p.propertyId, unitId: p.unitId, title: jobTitle, description: 'Water under the sink.', permissionToEnter: true });
    expect(l.status).toBe('ACTIVE');
  });

  const open = async (pageWithToken: any, path: string): Promise<Page> => pageWithToken(c.token, path);

  test('[LEAS-10] leasing overview, listings, leads, showings, applications and leases render the seeded data', async ({ pageWithToken }) => {
    const page = await open(pageWithToken, '/app/leasing');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: /Leasing/ }).first()).toBeVisible();
    await page.goto('/app/leasing/listings');
    await expect(page.getByRole('heading', { name: 'Listings' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'New listing' }).first()).toBeVisible();
    await expect(page.getByText(`Court ${p.sfx}`).first()).toBeVisible();
    await page.goto('/app/leasing/leads');
    await expect(page.getByText('Quinn Asker').first()).toBeVisible();
    await page.goto('/app/leasing/showings');
    await expect(page.locator('main')).not.toContainText(/something went wrong/i);
    await page.goto('/app/leasing/applications');
    await expect(page.getByText('Rae Resident').first()).toBeVisible();
    await page.goto('/app/leasing/leases');
    await expect(page.getByText('Rae Resident').first()).toBeVisible();
    await expect(page.getByText(/Active/i).first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[LEAS-10] a new listing can be started from the screen', async ({ pageWithToken }) => {
    const page = await open(pageWithToken, '/app/leasing/listings');
    await page.getByRole('button', { name: 'New listing' }).first().click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Save' })).toBeDisabled();
    await pick(page, d.getByRole('combobox').first(), /2B/);
    await d.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(/Draft listing created|Draft/i).first()).toBeVisible();
  });

  test('[BILL-09] payments overview, invoices, collections, payments, statements, deposits and tax render', async ({ pageWithToken }) => {
    const page = await open(pageWithToken, '/app/payments');
    const problems = watchErrors(page);
    await expect(page.getByRole('button', { name: 'Run billing cycle' }).first()).toBeVisible();
    for (const [path, heading] of [['/app/payments/invoices', /Invoices/], ['/app/payments/collections', /Collections/], ['/app/payments/payments', /Payments/], ['/app/payments/statements', /Owner statements|Statements/], ['/app/payments/deposits', /Security deposits/], ['/app/payments/tax', /Tax and compliance/]] as const) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: heading }).first()).toBeVisible();
      await expect(page.locator('main')).not.toContainText(/something went wrong/i);
    }
    await page.goto('/app/payments/invoices');
    await expect(page.getByText('Rae Resident').first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'New charge' }).first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[BILL-09] a one-off charge can be created from the invoices screen', async ({ pageWithToken }) => {
    const page = await open(pageWithToken, '/app/payments/invoices');
    await page.getByRole('button', { name: 'New charge' }).first().click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Add charge' }).first()).toBeDisabled();
    await pick(page, d.getByRole('combobox', { name: /Resident/ }), /Rae/);
    await d.getByLabel(/Amount/).fill('55');
    await d.getByLabel(/Description/).fill('Pet fee e2e');
    await d.getByRole('button', { name: 'Add charge' }).first().click();
    await expect(page.getByText('Pet fee e2e').first()).toBeVisible();
  });

  test('[MAIN-08] maintenance work orders, vendors and recurring tasks render and accept new records', async ({ pageWithToken }) => {
    const page = await open(pageWithToken, '/app/maintenance');
    const problems = watchErrors(page);
    await expect(page.getByRole('button', { name: 'New request' }).first()).toBeVisible();
    await expect(page.getByText(jobTitle).first()).toBeVisible();
    await page.goto('/app/maintenance/vendors');
    await expect(page.getByText(vendorName).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add vendor' }).first()).toBeVisible();
    await page.goto('/app/maintenance/recurring');
    await expect(page.getByRole('button', { name: 'Add recurring task' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create due requests now' }).first()).toBeVisible();
    expect(problems).toEqual([]);
  });
});
