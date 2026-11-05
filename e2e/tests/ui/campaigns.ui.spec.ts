import { test, expect, needsWrites, watchErrors } from '../../src/support/fixtures';
import { Api } from '../../src/support/api';
import { API_URL } from '../../src/support/env';
import { onboardCompany } from '../../src/support/company';
import { buildPortfolio, publishListing, until } from '../../src/support/builders';

/** The Campaigns pages, and the small sender page that plays an ad platform posting a signed lead. */
test.describe('Campaigns pages', () => {
  needsWrites();
  test.setTimeout(150_000);

  test('[CAMP-05] the campaigns list and a campaign\'s page show Leads, Tours and Applications together', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const camp = (await c.api.post('/api/leasing/campaigns', { name: `Tours ${p.sfx}`, channel: 'ZILLOW' })).body;
    const pub = await Api.create();
    await pub.post(`/api/leasing/public/listings/${listing}/showings`, { name: 'Tina Tourist', email: `tina.${p.sfx}@example.com`, type: 'SELF_GUIDED', scheduledAt: new Date(Date.now() + 4 * 86400000).toISOString(), campaign: camp.tracking_code });
    await until(() => c.api.get(`/api/leasing/campaigns/${camp.id}`), (r) => Number(r.body.showings) === 1);

    const page = await pageWithToken(c.token, '/app/leasing/campaigns');
    const problems = watchErrors(page);
    await expect(page.getByRole('columnheader', { name: /^Tours/ })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: /^Applications/ })).toBeVisible();
    const row = page.getByRole('row').filter({ hasText: `Tours ${p.sfx}` });
    await expect(row).toBeVisible();
    await row.click();
    await expect(page.getByText('Tours', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('Applications', { exact: true }).first()).toBeVisible();
    // The tour is in the recent activity, labelled as a tour, and its person has a lead.
    await expect(page.getByRole('cell', { name: 'Tour', exact: true })).toBeVisible();
    await expect(page.getByText('Tina Tourist').first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[CAMP-06] the sender page signs a lead in the browser and posts it; a wrong secret is refused; the campaign counts it', async ({ page }) => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const camp = (await c.api.post('/api/leasing/campaigns', { name: `Sender ${p.sfx}`, channel: 'FACEBOOK_ADS' })).body;

    const homesList = (r: import('@playwright/test').Response) => r.url().includes('/api/leasing/public/listings?page=1&size=100');
    const firstLoad = page.waitForResponse(homesList);
    await page.goto('/campaign-sender.html');
    await expect(page).toHaveTitle(/Campaign sender/);
    await firstLoad;
    const secondLoad = page.waitForResponse(homesList);
    await page.locator('#api').fill(API_URL);
    await page.locator('#api').press('Tab');   // leaving the field is what makes the page reload its list of homes
    await secondLoad;
    await page.waitForFunction(() => document.querySelectorAll('#home option').length > 1 && !/Loading/.test(document.querySelector('#home option')!.textContent || ''));
    // The list of homes is every company's, so the home under test is added to it once the page has finished loading its own list.
    await page.evaluate((id) => { const s = document.getElementById('home') as HTMLSelectElement; s.add(new Option('Home under test', id)); s.value = id; }, listing);
    await page.locator('#key').fill(camp.public_key);
    await page.locator('#secret').fill(camp.secret);
    await page.getByRole('button', { name: 'Fill a sample person' }).click();

    await page.getByRole('button', { name: 'Send to PropertyOS' }).click();
    await expect(page.locator('#status')).toHaveText(/200/);
    await expect(page.locator('#meaning')).toContainText('Accepted');
    await expect(page.locator('#req')).toContainText('X-Signature: sha256=');
    await expect(page.locator('#res')).toContainText('"duplicate": false');

    await page.getByRole('button', { name: 'Send to PropertyOS' }).click();
    await expect(page.locator('#meaning')).toContainText('Already received');

    for (const [mode, label] of [['wrong', 'wrong secret'], ['tamper', 'changed message'], ['nosig', 'no signature']]) {
      await page.locator('#break').selectOption(mode);
      await page.getByRole('button', { name: 'Send to PropertyOS' }).click();
      await expect(page.locator('#status'), label).toHaveText(/401/);
      await expect(page.locator('#meaning'), label).toContainText('Refused');
    }

    await page.locator('#break').selectOption('none');
    await page.locator('#type').selectOption('application');
    await page.locator('#email').fill(`app.${p.sfx}@example.com`);
    await page.getByRole('button', { name: 'Send to PropertyOS' }).click();
    await expect(page.locator('#status')).toHaveText(/200/);

    const detail = (await until(() => c.api.get(`/api/leasing/campaigns/${camp.id}`), (r) => Number(r.body.applications) === 1)).body;
    expect(Number(detail.applications)).toBe(1);
    expect(Number(detail.leads)).toBeGreaterThanOrEqual(1);
  });
});
