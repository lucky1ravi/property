import { test, expect, needsWrites, pick, watchErrors } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, until } from '../../src/support/builders';
import { occupiedHome, vendorFor } from '../../src/support/scenarios';
import { readFileSync } from 'node:fs';

/**
 * What people see: a company's own colours and logo, a professional invoice, ratings, a richer listing page, the report centre,
 * and what companies pay PropertyOS.
 */
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

test.describe('Branding', () => {
  needsWrites();
  test.setTimeout(180_000);

  test('[BRAND-01] the company\'s primary colour becomes the product\'s colour, its logo shows in the menu, and a wrong colour or logo is refused', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/admin/configuration');
    const problems = watchErrors(page);
    const menu = page.locator('nav[data-tour="sidebar"]').first();
    const before = await menu.evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(before).toBe('rgb(15, 61, 62)'); // the PropertyOS default

    await page.getByRole('navigation', { name: 'Settings sections' }).getByRole('button', { name: 'Branding' }).click();
    await page.getByLabel('Primary colour code').fill('#7A1F3D');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect.poll(() => menu.evaluate((el) => getComputedStyle(el).backgroundColor), { timeout: 20_000 }).not.toBe(before);
    const after = await menu.evaluate((el) => getComputedStyle(el).backgroundColor);
    const [r, g, b] = after.match(/\d+/g)!.map(Number);
    expect(r, `a wine-red menu, not ${after}`).toBeGreaterThan(g + 15);
    expect(r).toBeGreaterThan(b);
    // The colour holds on every screen and after a reload.
    await page.goto('/app');
    expect(await page.locator('nav[data-tour="sidebar"]').first().evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(after);
    // A primary button is in the company's colour too.
    await page.goto('/app/leasing/properties');
    const button = page.getByRole('button', { name: 'Add property' }).first();
    expect(await button.evaluate((el) => getComputedStyle(el).backgroundColor)).not.toBe('rgb(15, 61, 62)');

    // The logo.
    await page.goto('/app/admin/configuration');
    await page.getByRole('navigation', { name: 'Settings sections' }).getByRole('button', { name: 'Branding' }).click();
    await page.getByLabel('Choose a logo').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: PNG });
    await expect(page.getByAltText('Company logo')).toBeVisible();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.locator('nav[data-tour="sidebar"] img[alt$="logo"]').first()).toBeVisible({ timeout: 20_000 });
    await page.getByLabel('Choose a logo').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('not a picture') });
    await expect(page.getByText('Choose a PNG or JPEG picture.')).toBeVisible();
    await page.getByLabel('Choose a logo').setInputFiles({ name: 'big.png', mimeType: 'image/png', buffer: Buffer.alloc(120 * 1024, 1) });
    await expect(page.getByText(/over 100 KB/)).toBeVisible();
    expect(problems).toEqual([]);

    // The server refuses what the screen would, too, and keeps the picture out of the history.
    const branding = (await c.api.get('/api/identity/config')).body.branding;
    expect((await c.api.put('/api/identity/config/branding', { ...branding, primaryColor: 'red' })).status).toBe(400);
    expect((await c.api.put('/api/identity/config/branding', { ...branding, logoDataUrl: 'data:text/html;base64,PGI+aGk8L2I+' })).status).toBe(400);
    expect((await c.api.put('/api/identity/config/branding', { ...branding, logoDataUrl: `data:image/png;base64,${'A'.repeat(150_000)}` })).status).toBe(400);
    const audit = JSON.stringify((await c.api.get('/api/identity/audit?page=1&size=50')).body);
    expect(audit).not.toContain('iVBORw0KGgo');
    // A company that has not set its own colours keeps the PropertyOS look, and the operator is never recoloured.
    const other = await pageWithToken((await onboardCompany()).token, '/app');
    expect(await other.locator('nav[data-tour="sidebar"]').first().evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(15, 61, 62)');
  });
});

test.describe('Invoices', () => {
  needsWrites();
  test.setTimeout(180_000);

  test('[INV-01] the invoice PDF carries the company\'s name and logo, who it is for, the amounts, the tax position and how to pay', async ({ pageWithToken }) => {
    const { c, resident } = await occupiedHome();
    const branding = (await c.api.get('/api/identity/config')).body.branding;
    const logo = `data:image/png;base64,${PNG.toString('base64')}`;
    expect((await c.api.put('/api/identity/config/branding', { ...branding, companyName: 'Willow Row Homes', primaryColor: '#7A1F3D', logoDataUrl: logo })).status).toBe(200);

    const page = await pageWithToken(c.token, '/app/payments/invoices');
    await page.getByRole('row').nth(2).click();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download PDF' }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/^invoice-.*\.pdf$/);
    const bytes = readFileSync(await file.path()!);
    expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
    expect(bytes.length).toBeGreaterThan(2500);
    const text = bytes.toString('latin1');
    for (const needle of ['INVOICE', 'BILLED TO', 'Rae Resident', 'AMOUNT DUE', 'DESCRIPTION', 'BALANCE DUE', 'HOW TO PAY', 'Willow Row Homes', 'Page 1 of 1']) expect(text, needle).toContain(needle);
    expect(text, 'the logo is drawn into the page').toMatch(/\/Subtype\s*\/Image/);
    expect(text, 'the tax position is stated').toMatch(/No sales tax applies|Sales tax of/);
    void resident;
  });

  test('[INV-02] a charge that carries tax shows the subtotal, the tax and the total, and a paid invoice is stamped paid', async ({ pageWithToken }) => {
    const { c, resident, lease } = await occupiedHome();
    const tenantId = (await c.api.get(`/api/leasing/leases/${lease.leaseId}`)).body.tenant_id;
    // Turn on sales tax for "other" charges at 8.25% (the company is in Texas), then charge a parking fee.
    const payments = (await c.api.get('/api/identity/config')).body.payments;
    expect((await c.api.put('/api/identity/config/payments', { ...payments, tax: { ...payments.tax, enabled: true, taxOther: true, defaultRatePercent: 8.25, rates: { ...payments.tax.rates, TX: 8.25 } } })).status).toBe(200);
    await new Promise((r) => setTimeout(r, 4000)); // the billing service picks up the changed setting within a few seconds
    const charge = await c.api.post('/api/billing/invoices', { tenantId, kind: 'OTHER', description: 'Reserved parking space 14', amount: 100 });
    expect(charge.status).toBe(200);
    const inv = (await c.api.get(`/api/billing/invoices/${charge.body.id}`)).body;
    expect(Number(inv.tax_amount)).toBeCloseTo(8.25, 2);
    expect(Number(inv.amount)).toBeCloseTo(108.25, 2);
    const taxed = true;

    const page = await pageWithToken(c.token, '/app/payments/invoices');
    await page.getByRole('row', { name: /Reserved parking space 14/ }).first().click();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download PDF' }).click();
    const text = readFileSync(await (await download).path()!).toString('latin1');
    expect(text).toContain('Reserved parking space 14');
    expect(text).toContain('Subtotal');
    expect(text).toContain(taxed ? 'Sales tax of' : 'No sales tax applies');
    if (taxed) expect(text).toMatch(/Tax \\?\(8\.25/); // PDF text escapes the bracket

    // Once it is paid the same invoice says so.
    await resident.api.post('/api/billing/my/methods', { type: 'ACH', last4: '6789', consent: true });
    expect((await resident.api.post('/api/billing/payments', { amount: Number(inv.amount), method: 'ACH', last4: '6789', invoiceId: charge.body.id })).status).toBe(200);
    await page.reload();
    await page.getByRole('row', { name: /Reserved parking space 14/ }).first().click();
    const again = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download PDF' }).click();
    const paid = readFileSync(await (await again).path()!).toString('latin1');
    expect(paid).toContain('PAID');
    expect(paid).toContain('PAYMENTS RECEIVED');
  });
});

test.describe('Ratings on screen', () => {
  needsWrites();
  test.setTimeout(240_000);

  test('[RATE-UI-01] a resident rates their home, and the manager sees the stars and the words on the property and unit lists', async ({ pageWithToken }) => {
    const { c, p, resident } = await occupiedHome();
    const page = await pageWithToken(resident.token, '/app/leasing/my-lease');
    const panel = page.getByText('Rate your home').first();
    await expect(panel).toBeVisible();
    await page.getByRole('radiogroup', { name: /Rate Court/ }).getByRole('radio', { name: '4 stars' }).click();
    await page.getByLabel(/Rate Court.*: comment/).fill('Quiet building, kind neighbours.');
    await page.getByRole('button', { name: 'Save rating' }).first().click();
    await expect(page.getByText('Thank you. Your rating is saved.')).toBeVisible();
    await page.getByRole('radiogroup', { name: /Rate Unit|Rate .*1A/ }).getByRole('radio', { name: '5 stars' }).click();
    await page.getByRole('button', { name: 'Save rating' }).first().click();
    await expect.poll(async () => Number((await resident.api.get('/api/leasing/reviews/mine')).body.unit?.review?.stars ?? 0)).toBe(5);

    const mp = await pageWithToken(c.token, '/app/leasing/properties');
    const open = mp.getByRole('row', { name: new RegExp(`Court ${p.sfx}`) }).getByRole('button', { name: 'Show resident ratings' });
    await expect(open).toContainText('4.0');
    await open.click();
    const drawer = mp.getByRole('dialog');
    await expect(drawer.getByText('Quiet building, kind neighbours.')).toBeVisible();
    await expect(drawer.getByText('Rae Resident')).toBeVisible();
    await expect(drawer.getByText('1 rating')).toBeVisible();
  });

  test('[RATE-UI-02] staff rate a vendor from the vendor list, and a resident rates the repair once it is finished', async ({ pageWithToken }) => {
    const { c, p, resident } = await occupiedHome();
    const vendor = await vendorFor(c, p);
    const page = await pageWithToken(c.token, '/app/maintenance/vendors');
    await page.getByRole('row', { name: new RegExp(`Pete Plumbing ${p.sfx}`) }).getByRole('button', { name: 'Show ratings' }).click();
    const drawer = page.getByRole('dialog');
    await expect(drawer.getByText('Nothing yet.')).toBeVisible();
    await drawer.getByRole('radio', { name: '5 stars' }).click();
    await drawer.getByLabel('Your rating: comment').fill('Fixed it the same day.');
    await drawer.getByRole('button', { name: 'Save rating' }).click();
    await expect(drawer.getByText('Fixed it the same day.')).toBeVisible({ timeout: 20_000 });
    await expect(drawer.getByText('1 rating')).toBeVisible();

    // The resident's repair, done by this vendor.
    const wo = (await resident.api.post('/api/maintenance/work-orders', { title: 'Closet door sticks', description: 'It will not close.', permissionToEnter: true })).body.id;
    await until(() => c.api.get(`/api/maintenance/work-orders/${wo}`), (r) => r.body.status !== 'SUBMITTED');
    if ((await c.api.get(`/api/maintenance/work-orders/${wo}`)).body.status === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${wo}/actions/APPROVE`, {});
    await c.api.post(`/api/maintenance/work-orders/${wo}/actions/ASSIGN`, { vendorId: vendor.id }).catch(() => undefined);
    const rp = await pageWithToken(resident.token, `/app/maintenance/${wo}`);
    await expect(rp.getByText('Rate this repair')).toHaveCount(0); // not finished yet
    await vendor.api.post(`/api/maintenance/work-orders/${wo}/actions/START`, {});
    await vendor.api.post(`/api/maintenance/work-orders/${wo}/actions/COMPLETE`, { cost: 40, notes: 'Planed the door.' });
    await rp.reload();
    await expect(rp.getByText('Rate this repair').first()).toBeVisible();
    await rp.getByRole('radiogroup', { name: 'Rate this repair' }).getByRole('radio', { name: '4 stars' }).click();
    await rp.getByRole('button', { name: 'Send rating' }).click();
    await expect(rp.getByText('Thank you. Your rating is saved.')).toBeVisible();
    expect((await c.api.get(`/api/maintenance/vendors/${vendor.id}/reviews`)).body.count).toBe(2);
  });
});

test.describe('The listing page', () => {
  needsWrites();
  test.setTimeout(180_000);

  test('[LIST-01] a home shows its facts with icons, its amenities with icons, the costs, and the resident rating', async ({ page }) => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    expect((await c.api.put(`/api/leasing/properties/${p.propertyId}`, { yearBuilt: 2008, amenities: ['Covered parking', 'Swimming pool', 'Fitness centre', 'Washer and dryer', 'Pets welcome', 'Central air', 'Private balcony', 'Secure gated entry', 'Rooftop lounge'] })).status).toBe(200);
    const listing = await publishListing(c.api, p.unitId);
    await page.goto(`/homes/${listing}`);
    const facts = page.getByTestId('home-facts');
    await expect(facts).toBeVisible();
    for (const label of ['Bedrooms', 'Bathrooms', 'Size', 'Property type', 'Built', 'Available', 'Unit', 'Security deposit', 'Rent per sq ft']) await expect(facts.getByText(label, { exact: true })).toBeVisible();
    await expect(facts.getByText('2 bedrooms')).toBeVisible();
    await expect(facts.getByText('2008')).toBeVisible();
    expect(await facts.locator('svg').count(), 'each fact has an icon').toBeGreaterThanOrEqual(9);
    await page.getByRole('button', { name: /^Amenities/ }).click();   // the amenities are a dropdown
    const amenities = page.getByTestId('amenities');
    await expect(amenities.locator('li')).toHaveCount(9);
    expect(await amenities.locator('li svg').count(), 'each amenity has an icon').toBe(9);
    await expect(amenities.getByText('Covered parking')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Costs' })).toBeVisible();
    await expect(page.getByText('Due to move in')).toBeVisible();
    await expect(page.getByText('rated by residents')).toHaveCount(0); // nobody has rated it yet
    // The page is usable on a phone.
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(facts).toBeVisible();
    const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(over).toBeLessThanOrEqual(1);
  });

  test('[LIST-02] amenities are chosen from a searchable list when a property is edited', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const page = await pageWithToken(c.token, '/app/leasing/properties');
    await page.getByRole('row', { name: new RegExp(`Court ${p.sfx}`) }).click();
    const d = page.getByRole('dialog');
    const search = d.getByPlaceholder(/Search amenities/);
    await search.fill('pool');
    await page.getByRole('option', { name: /Swimming pool/ }).click();
    await search.fill('pets');
    await page.getByRole('option', { name: /Pets welcome/ }).click();
    await d.getByRole('button', { name: 'Save' }).click();
    await expect.poll(async () => (await c.api.get(`/api/leasing/properties/${p.propertyId}`)).body.amenities).toEqual(['Swimming pool', 'Pets welcome']);
  });
});

test.describe('Reports on screen', () => {
  test.setTimeout(180_000);

  test('[REP-UI-01] the hub offers the reports for the role; a report shows its figures and rows, and a line opens what is behind it', async ({ pageAs }) => {
    const page = await pageAs('manager', '/app/insights');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Reports', level: 1 })).toBeVisible();
    for (const group of ['Portfolio', 'Leasing', 'Money', 'Repairs']) await expect(page.getByRole('heading', { name: group })).toBeVisible();
    for (const title of ['Rent roll', 'Occupancy and vacancy', 'Lease expirations', 'Who owes what', 'Income and expenses', 'Repair costs', 'Vendor scorecard']) await expect(page.getByRole('link', { name: new RegExp(title) })).toBeVisible();

    await page.getByRole('link', { name: /Occupancy and vacancy/ }).click();
    await expect(page.getByRole('heading', { name: 'Occupancy and vacancy' })).toBeVisible();
    for (const k of ['Units', 'Occupancy', 'Vacant', 'Lost each month']) await expect(page.getByText(k, { exact: true }).first()).toBeVisible();
    await expect(page.getByRole('row').nth(2)).toBeVisible();
    // Follow the first property with an empty unit down to those units.
    await page.getByRole('row', { name: /Empty units/ }).first().getByRole('button', { name: 'Empty units' }).click();
    await expect(page).toHaveURL(/\/app\/insights\/vacancies\?propertyId=/);
    await expect(page.getByRole('heading', { name: 'Vacant units' })).toBeVisible();
    await expect(page.getByText(/Barton|Capitol|Pacific|Domain|Emerald|Lonestar|Harbor/).first()).toBeVisible();
    await page.getByRole('button', { name: 'Back' }).click();
    await expect(page.getByRole('heading', { name: 'Occupancy and vacancy' })).toBeVisible();

    // A report with choices: change the window and the table follows.
    await page.goto('/app/insights/expirations');
    const wide = await page.getByText(/Showing 1.\d+ of (\d+)/).first().innerText();
    await pick(page, page.getByRole('combobox', { name: 'Ending within' }), '30 days');
    await expect(page.getByText(/Showing/).first()).not.toHaveText(wide);
    // Money, with the oldest debts first, and a resident's account behind a line.
    await page.goto('/app/insights/receivables');
    await expect(page.getByText('Outstanding', { exact: true }).first()).toBeVisible();
    await page.getByRole('row').nth(2).getByRole('button', { name: 'Account' }).click();
    await expect(page.getByRole('heading', { name: 'Transactions' })).toBeVisible();
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[REP-UI-02] an owner gets their own reports and not the books; residents have no reports menu', async ({ pageAs }) => {
    const owner = await pageAs('owner', '/app/insights');
    await expect(owner.getByRole('link', { name: /Rent roll/ })).toBeVisible();
    await expect(owner.getByRole('link', { name: /Income and expenses/ })).toBeVisible();
    await expect(owner.getByRole('link', { name: /Who owes what/ })).toHaveCount(0);
    await expect(owner.getByRole('link', { name: /Vendor scorecard/ })).toHaveCount(0);
    const tenant = await pageAs('tenant', '/app');
    await expect(tenant.getByRole('link', { name: 'Reports', exact: true })).toHaveCount(0);
    await tenant.goto('/app/insights/rent-roll');
    await expect(tenant.getByText(/don.t have access/i).first()).toBeVisible();
  });

  test('[REP-UI-03] a report can be searched, sorted and downloaded', async ({ pageAs }) => {
    const page = await pageAs('accountant', '/app/insights/rent-roll');
    await expect(page.getByRole('row').nth(2)).toBeVisible();
    await page.getByPlaceholder(/Search/).first().fill('Barton');
    await expect(page.getByText(/Nothing matches/)).toHaveCount(0);
    await expect(page.getByRole('row', { name: /Barton/ }).first()).toBeVisible();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Export CSV/ }).click();
    expect((await download).suggestedFilename()).toMatch(/rent-roll.*\.csv$/);
  });
});

test.describe('What companies pay PropertyOS, on screen', () => {
  needsWrites();
  test.setTimeout(240_000);

  test('[FEES-UI-01] the operator sees what is billed and owed, records a payment and a charge; the company pays its own invoice, and a declined card says so', async ({ pageAs, pageWithToken }) => {
    const platformApi = await apiAs('platform');
    const c = await onboardCompany();
    await platformApi.post('/api/identity/platform/billing/run', {});
    const inv = rows(await platformApi.get(`/api/identity/platform/billing/invoices?page=1&size=5&orgId=${c.orgId}`))[0];

    const op = await pageAs('platform', '/app/admin/payments');
    const problems = watchErrors(op);
    await expect(op.getByRole('heading', { name: 'Subscription payments' })).toBeVisible();
    for (const k of ['Billed this month', 'Collected this month', 'Outstanding', 'Overdue', 'Failed, 30 days']) await expect(op.getByText(k, { exact: true })).toBeVisible();
    await expect(op.getByText('Billed and collected')).toBeVisible();
    await op.getByPlaceholder(/Search/).first().fill(c.name);
    await op.getByRole('button', { name: inv.number }).click();
    const drawer = op.getByRole('dialog');
    await expect(drawer.getByText(`Invoice ${inv.number}`)).toBeVisible();
    await expect(drawer.getByText(String(inv.description).slice(0, 30))).toBeVisible();
    await drawer.getByLabel('Amount (USD)').fill('100');
    await drawer.getByLabel('Reference').fill('WIRE-7001');
    await drawer.getByRole('button', { name: 'Record payment' }).click();
    await expect(op.getByText('Payment recorded')).toBeVisible();
    await expect(drawer.getByText('WIRE-7001')).toBeVisible({ timeout: 20_000 });
    expect(problems).toEqual([]);

    // The company's own view.
    const cp = await pageWithToken(c.token, '/app/admin/subscription');
    await expect(cp.getByRole('heading', { name: 'Subscription' })).toBeVisible();
    for (const k of ['Plan', 'Units you manage', 'Balance due', 'Overdue']) await expect(cp.getByText(k, { exact: true })).toBeVisible();
    await cp.getByRole('row', { name: new RegExp(inv.number) }).getByRole('button', { name: 'Pay' }).click();
    const pay = cp.getByRole('dialog');
    await pay.getByLabel('Last 4 digits').fill('0002');
    await pay.getByRole('button', { name: /^Pay \$/ }).click();
    await expect(cp.getByText(/payment was declined/i).first()).toBeVisible();
    await pay.getByLabel('Last 4 digits').fill('4242');
    await pay.getByRole('button', { name: /^Pay \$/ }).click();
    await expect(cp.getByText(/Payment received/i).first()).toBeVisible();
    await expect.poll(async () => (await platformApi.get(`/api/identity/platform/billing/invoices/${inv.id}`)).body.status).toBe('PAID');

    // A resident cannot see the subscription page, and a company administrator cannot open the operator's.
    const tenant = await pageAs('tenant', '/app/admin/subscription');
    await expect(tenant.getByText(/don.t have access/i).first()).toBeVisible();
    const admin = await pageAs('admin', '/app/admin/payments');
    await expect(admin.getByText(/don.t have access/i).first()).toBeVisible();
  });
});

void [Api, createSignedInUser];
