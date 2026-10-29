import { test, expect, watchErrors } from '../../src/support/fixtures';
import { CAN_WRITE, SUFFIX } from '../../src/support/env';

const PAGES: Array<[string, RegExp]> = [
  ['/', /Run a bigger portfolio with the same team/],
  ['/products', /One platform, six jobs done/],
  ['/solutions', /A view built for every role/],
  ['/features', /Everything a property business runs on/],
  ['/plans', /Plans that grow with your portfolio/],
  ['/why-us', /Why property teams choose PropertyOS/],
  ['/security', /Built so one company can never see another/],
  ['/contact', /Talk to our team/],
];

test.describe('Public website', () => {
  test('[SITE-01] the marketing pages load with their headline and shared navigation', async ({ page }) => {
    const problems = watchErrors(page);
    for (const [path, heading] of PAGES) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toContainText(heading);
      const nav = page.locator('header');
      for (const label of ['Platform', 'Solutions', 'Features', 'Plans', 'Why us', 'Security', 'Sign in', 'Request a demo']) await expect(nav.getByRole('link', { name: label }).first()).toBeVisible();
      await expect(page.locator('main')).not.toContainText(/something went wrong/i);
    }
    expect(problems).toEqual([]);
    await page.goto('/');
    await page.getByRole('link', { name: 'Plans', exact: true }).first().click();
    await expect(page).toHaveURL(/\/plans$/);
    await page.getByRole('link', { name: 'Sign in' }).first().click();
    await expect(page).toHaveURL(/\/signin$/);
  });

  test('[SITE-01] the plans page shows every plan with a price from the platform', async ({ page, publicApi }) => {
    const site = (await (await publicApi()).get('/api/identity/public/site')).body;
    await page.goto('/plans');
    for (const plan of site.plans) await expect(page.getByRole('heading', { name: plan.name, exact: true }).first()).toBeVisible();
    await expect(page.locator('main')).toContainText(/\$\d/);
  });

  test('[SITE-02] the request-a-demo form validates and sends a message', async ({ page, apiAs }) => {
    await page.goto('/contact');
    const send = page.getByRole('button', { name: 'Send message' });
    await expect(send).toBeDisabled(); // nothing sent until the form is complete
    await page.getByLabel('Your name').fill('D');
    await page.getByLabel('Work email').fill('not-an-email');
    await page.getByLabel('Message').fill('too short');
    await expect(send).toBeDisabled();
    if (!CAN_WRITE) return;
    const sfx = SUFFIX();
    await page.getByLabel('Your name').fill(`Dana ${sfx}`);
    await page.getByLabel('Work email').fill(`dana.${sfx}@e2e.test`);
    await page.getByLabel('Message').fill('We manage 60 doors and want to see how rent collection works.');
    await expect(send).toBeEnabled();
    await send.click();
    await expect(page.locator('main')).toContainText(/thank|received|we.?ll be in touch/i);
    const platform = await apiAs('platform');
    const list = (await platform.get('/api/identity/platform/site/contact-requests')).body;
    const rows = Array.isArray(list) ? list : list.rows ?? list.items ?? [];
    expect(rows.some((r: any) => r.email === `dana.${sfx}@e2e.test`)).toBe(true);
  });

  test('[SITE-03] an unknown address shows a not-found page', async ({ page }) => {
    await page.goto('/this-page-does-not-exist');
    await expect(page.getByRole('heading', { name: /This page doesn.t exist/ })).toBeVisible();
    await page.goto('/app/not-a-real-screen');
    await expect(page.locator('body')).not.toContainText(/something went wrong/i);
  });

  test('[SITE-04] the public rental site opens without signing in and can be searched', async ({ page, publicApi }) => {
    await page.goto('/homes');
    await expect(page.getByRole('heading', { name: /Find a home you.ll want to stay in/ })).toBeVisible();
    const live = (await (await publicApi()).get('/api/leasing/public/listings')).body;
    const items = Array.isArray(live) ? live : live.rows ?? live.items ?? [];
    await page.getByRole('button', { name: 'Search' }).click();
    if (items.length) await expect(page.locator('main')).toContainText(items[0].property_name);
    else await expect(page.locator('main')).toContainText(/no homes|nothing|check back/i);
  });
});
