import { test, expect, needsWrites } from '../../src/support/fixtures';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { Api, rows } from '../../src/support/api';
import type { Browser } from '@playwright/test';

/**
 * Making the product easy for someone who has never seen it: a welcome tour the first time, an offer of a short tour on each
 * new page, a Help panel for the page you are on, search for anything, and a getting-started list that ticks itself off.
 * What a person has been shown is saved on their account, so it does not come back on the next sign-in.
 */
test.describe('Guidance for new people', () => {
  needsWrites();

  /** A browser that is signed in but has NOT been told to skip the tours (the other fixtures do that). */
  async function freshPage(browser: Browser, token: string, path = '/app') {
    const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    await ctx.addInitScript((t) => { try { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('pos.token', t as string); sessionStorage.setItem('seeded', '1'); } } catch { /* ignore */ } }, token);
    const page = await ctx.newPage();
    await page.goto(path);
    return { page, ctx };
  }

  test('[GUIDE-01] the first sign-in offers a welcome tour, and finishing it means it is never offered again', async ({ browser }) => {
    const c = await onboardCompany();
    const { page, ctx } = await freshPage(browser, c.token);
    await expect(page.getByRole('dialog', { name: /Welcome to PropertyOS/ })).toBeVisible();
    await page.getByRole('button', { name: /Show me around/ }).click();
    const tour = page.getByRole('dialog', { name: /Welcome tour: step 1 of/ });
    await expect(tour).toBeVisible();
    await expect(tour).toContainText('Welcome');
    // The arrow keys and the buttons both move through it; the spotlight points at the real menu.
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('dialog', { name: /step 2 of/ })).toContainText('Everything starts in this menu');
    await page.keyboard.press('ArrowLeft');
    await expect(page.getByRole('dialog', { name: /step 1 of/ })).toBeVisible();
    for (let i = 0; i < 12; i++) {
      const next = page.getByRole('button', { name: /^(Next|Done)$/ });
      if (!(await next.isVisible().catch(() => false))) break;
      const done = (await next.innerText()).startsWith('Done');
      await next.click();
      if (done) break;
    }
    await expect(page.getByRole('dialog', { name: /Welcome tour/ })).toBeHidden();
    // It was saved on the account, so a brand-new browser does not show it again.
    await expect.poll(async () => (await (await Api.create(c.token)).get('/api/identity/me')).body.uiState?.guide?.welcome).toBe(true);
    const again = await freshPage(browser, c.token);
    await again.page.waitForTimeout(2500);
    await expect(again.page.getByRole('dialog', { name: /Welcome to PropertyOS/ })).toHaveCount(0);
    await again.ctx.close();
    await ctx.close();
  });

  test('[GUIDE-02] Escape leaves a tour at once, and a person can decline the welcome', async ({ browser }) => {
    const c = await onboardCompany();
    const { page, ctx } = await freshPage(browser, c.token);
    await page.getByRole('button', { name: /explore on my own/ }).click();
    await expect(page.getByRole('dialog', { name: /Welcome to PropertyOS/ })).toBeHidden();
    await page.getByRole('button', { name: 'Help' }).click();
    await page.getByRole('button', { name: 'Tour this page' }).click();
    await expect(page.getByRole('dialog', { name: /tour: step 1 of/i })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: /tour: step/i })).toBeHidden();
    await ctx.close();
  });

  test('[GUIDE-03] the first visit to a page offers a short tour; "Not now" is remembered', async ({ browser }) => {
    const c = await onboardCompany();
    await (await Api.create(c.token)).put('/api/identity/me/ui-state', { guide: { welcome: true, checklistHidden: true } });
    const { page, ctx } = await freshPage(browser, c.token, '/app/leasing/owners');
    const offer = page.getByRole('region', { name: 'Page tour offer' });
    await expect(offer).toBeVisible({ timeout: 10_000 });
    await expect(offer).toContainText('New to Owners?');
    await offer.getByRole('button', { name: 'Show me' }).click();
    const tour = page.getByRole('dialog', { name: /Owners tour: step 1 of/ });
    await expect(tour).toContainText('The people or companies who own the properties');
    await page.keyboard.press('Escape');
    // Seen once: it is not offered again, here or in a new browser.
    await expect.poll(async () => Object.keys((await (await Api.create(c.token)).get('/api/identity/me')).body.uiState?.guide?.tours ?? {})).toContain('owners');
    const again = await freshPage(browser, c.token, '/app/leasing/owners');
    await again.page.waitForTimeout(3000);
    await expect(again.page.getByRole('region', { name: 'Page tour offer' })).toHaveCount(0);
    await again.ctx.close();
    await ctx.close();
  });

  test('[GUIDE-04] the question mark opens help for the page you are on, with plain-language steps and a way back to the tours', async ({ browser }) => {
    const c = await onboardCompany();
    await (await Api.create(c.token)).put('/api/identity/me/ui-state', { guide: { welcome: true, noCoach: true, checklistHidden: true } });
    const { page, ctx } = await freshPage(browser, c.token, '/app/leasing/listings');
    await expect(page.getByRole('heading', { name: 'Listings' })).toBeVisible();
    await page.keyboard.press('?');
    const help = page.getByRole('dialog', { name: 'Help' });
    await expect(help).toBeVisible();
    await expect(help).toContainText('On this page');
    await expect(help).toContainText('Homes you are advertising');
    await expect(help).toContainText('How do I');
    await expect(help).toContainText('Advertise a vacant unit');
    // Search the help for a word, get what the word means.
    await help.getByRole('textbox', { name: 'Search help' }).fill('deposit');
    await expect(help).toContainText('Money held at the start of a lease');
    await page.keyboard.press('Escape');
    await expect(help).toBeHidden();
    // Typing a question mark into a field does not open help.
    await page.getByRole('textbox', { name: 'Search' }).first().fill('what?');
    await expect(page.getByRole('dialog', { name: 'Help' })).toBeHidden();
    await ctx.close();
  });

  test('[GUIDE-05] Ctrl+K finds pages and records by typing, from the server, and opens them', async ({ browser }) => {
    const c = await onboardCompany();
    await (await Api.create(c.token)).put('/api/identity/me/ui-state', { guide: { welcome: true, noCoach: true, checklistHidden: true } });
    const name = `Searchable Owner ${c.sfx}`;
    expect((await c.api.post('/api/leasing/owners', { name, contact: 'Sam', email: `s.${c.sfx}@e2e.test` })).status).toBe(200);
    const { page, ctx } = await freshPage(browser, c.token);
    await expect(page.locator('[data-tour="header-search"]:visible').first()).toBeVisible();
    await page.keyboard.press('Control+K');
    const box = page.getByRole('dialog', { name: 'Search' });
    await expect(box).toBeVisible();
    await expect(box).toContainText('Pages');
    await page.getByRole('combobox', { name: 'Search' }).fill('invoice');
    await expect(box.getByRole('option', { name: /Invoices/ }).first()).toBeVisible();
    await page.keyboard.press('Enter');
    await page.waitForURL(/\/app\/payments\/invoices/);
    await page.keyboard.press('Control+K');
    await page.getByRole('combobox', { name: 'Search' }).fill(`Searchable Owner ${c.sfx}`);
    await expect(page.getByRole('option', { name: new RegExp(name) }).first()).toBeVisible();
    await page.getByRole('combobox', { name: 'Search' }).fill('zzzzqqqq');
    await expect(page.getByText(/Nothing matches/)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(box).toBeHidden();
    // The recent pages are remembered for next time.
    await page.keyboard.press('Control+K');
    await expect(page.getByRole('dialog', { name: 'Search' })).toContainText('Recent');
    await ctx.close();
  });

  test('[GUIDE-06] the getting-started list ticks itself off from what has really been done, and can be hidden for good', async ({ browser }) => {
    const c = await onboardCompany();
    await (await Api.create(c.token)).put('/api/identity/me/ui-state', { guide: { welcome: true, noCoach: true } });
    const { page, ctx } = await freshPage(browser, c.token, '/app');
    const list = page.getByRole('region', { name: 'Getting started' });
    await expect(list).toBeVisible();
    await expect(list).toContainText('0 of');
    await expect(list.getByText('Add an owner')).toBeVisible();
    expect((await c.api.post('/api/leasing/owners', { name: `First Owner ${c.sfx}` })).status).toBe(200);
    await page.reload();
    await expect(page.getByRole('region', { name: 'Getting started' })).toContainText('1 of');
    await page.getByRole('region', { name: 'Getting started' }).getByRole('button', { name: 'Hide this list' }).click();
    await expect(page.getByRole('region', { name: 'Getting started' })).toBeHidden();
    await page.reload();
    await expect(page.getByRole('region', { name: 'Getting started' })).toHaveCount(0);
    await ctx.close();
  });

  test('[GUIDE-07] a resident is shown where to start, with links for their role', async ({ browser }) => {
    const c = await onboardCompany();
    const t = await createSignedInUser(c.api, { email: `tenant.${c.sfx}@e2e.test`, name: 'Tess Tenant', roleKey: 'TENANT' });
    await (await Api.create(t.token)).put('/api/identity/me/ui-state', { guide: { welcome: true, noCoach: true } });
    const { page, ctx } = await freshPage(browser, t.token, '/app');
    const start = page.getByRole('region', { name: 'Quick start' });
    await expect(start).toBeVisible();
    await expect(start).toContainText('Where to start');
    await ctx.close();
  });
});

test.describe('Pages, pickers and messages in the browser', () => {
  needsWrites();

  test('[PAGE-01] a long list is shown 20 at a time, with the total, paging, a size choice and server-side search and sort', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    for (let i = 1; i <= 23; i++) await c.api.post('/api/leasing/owners', { name: `Owner ${String(i).padStart(2, '0')} ${c.sfx}`, email: `o${i}.${c.sfx}@e2e.test` });
    const page = await pageWithToken(c.token, '/app/leasing/owners');
    await expect(page.getByText('Showing 1–20 of 23')).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Rows per page' }).or(page.getByRole('button', { name: 'Rows per page' })).first()).toBeVisible();
    await page.getByRole('button', { name: 'Next page' }).click();
    await expect(page.getByText('Showing 21–23 of 23')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Next page' })).toBeDisabled();
    await page.getByRole('button', { name: 'First page' }).click();
    // The search reaches rows on the other page.
    await page.getByRole('textbox', { name: 'Search' }).fill(`Owner 23 ${c.sfx}`);
    await expect(page.getByText('Showing 1–1 of 1')).toBeVisible();
    await expect(page.getByRole('cell', { name: new RegExp(`Owner 23 ${c.sfx}`) })).toBeVisible();
    await page.getByRole('textbox', { name: 'Search' }).fill('');
    // Sorting orders every page, not just the one on screen.
    await page.locator('thead th button').first().click();
    await page.locator('thead th button').first().click();
    await expect(page.getByText('Showing 1–20 of 23')).toBeVisible();
    await expect(page.getByRole('cell', { name: new RegExp(`Owner 23 ${c.sfx}`) }).first()).toBeVisible();
  });

  test('[PAGE-02] picking a record is a search, not a long list', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    for (let i = 1; i <= 25; i++) await c.api.post('/api/leasing/owners', { name: `Pick Owner ${String(i).padStart(2, '0')} ${c.sfx}` });
    const page = await pageWithToken(c.token, '/app/leasing/properties');
    await page.getByRole('button', { name: 'Add property' }).first().click();
    const d = page.getByRole('dialog');
    const owner = d.getByRole('combobox').first();
    await owner.click();
    await expect(page.getByText('Showing 20 of 25')).toBeVisible();
    await owner.fill(`Pick Owner 17 ${c.sfx}`);
    await expect(page.getByRole('option')).toHaveCount(1);
    await page.getByRole('option').first().click();
    await expect(d).toContainText(`Pick Owner 17 ${c.sfx}`);
    await d.getByLabel('Property name').fill(`Searchable Court ${c.sfx}`);
  });

  test('[PAGE-03] messages: a person finds who to write to by typing, and the inbox can be searched', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const bea = await createSignedInUser(c.api, { email: `bea.${c.sfx}@e2e.test`, name: 'Bea Broker', roleKey: 'BROKER' });
    const page = await pageWithToken(c.token, '/app/messages');
    await page.getByRole('button', { name: 'New conversation' }).click();
    const d = page.getByRole('dialog');
    await d.getByLabel('Subject').fill('Welcome to the team');
    await d.getByPlaceholder(/Search people/).fill('Bea');
    await page.getByRole('option', { name: /Bea Broker/ }).click();
    await expect(d).toContainText('Bea Broker');
    await d.getByLabel('Message').fill('Glad you are here.');
    await d.getByRole('button', { name: 'Send' }).click();
    await expect(page.getByText('Welcome to the team').first()).toBeVisible();
    await page.getByPlaceholder('Search conversations').fill('zzzz');
    await expect(page.getByText(/Nothing matches/)).toBeVisible();
    await page.getByPlaceholder('Search conversations').fill('Welcome');
    await expect(page.getByText('Welcome to the team').first()).toBeVisible();
    expect(bea.userId).toBeTruthy();
  });

  test('[TOAST-01] messages come in at the top centre, green when it worked, yellow when it needs attention, red when something broke', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/leasing/owners');
    // green: a success
    await page.getByRole('button', { name: 'Add owner' }).first().click();
    await page.getByRole('dialog').getByLabel('Owner or company name').fill(`Toast Owner ${c.sfx}`);
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    const ok = page.getByRole('status').filter({ hasText: 'Owner added' });
    await expect(ok).toBeVisible();
    const box = (await ok.boundingBox())!;
    const view = page.viewportSize()!;
    expect(box.y).toBeLessThan(120);
    expect(Math.abs(box.x + box.width / 2 - view.width / 2)).toBeLessThan(40);
    await expect(ok).toHaveClass(/bg-good-100/);
    // yellow: the server refused something the person can fix
    await page.route('**/api/leasing/owners', async (route) => {
      if (route.request().method() === 'POST') await route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ status: 400, code: 'BAD_REQUEST', detail: 'Name is required.', correlationId: 'abc12345' }) });
      else await route.continue();
    });
    await page.getByRole('button', { name: 'Add owner' }).first().click();
    await page.getByRole('dialog').getByLabel('Owner or company name').fill('x');
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    const warn = page.getByRole('alert').filter({ hasText: 'Name is required.' });
    await expect(warn).toBeVisible();
    await expect(warn).toHaveClass(/bg-warn-100/);
    await expect(warn).toContainText('Please check what you entered');
    // red: something broke on our side, with a reference to give to support
    await page.unroute('**/api/leasing/owners');
    await page.route('**/api/leasing/owners', async (route) => {
      if (route.request().method() === 'POST') await route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL', detail: 'Something went wrong on our side and your request wasn’t completed.', correlationId: 'REF-98765' }) });
      else await route.continue();
    });
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    const bad = page.getByRole('alert').filter({ hasText: 'Something went wrong' });
    await expect(bad.first()).toBeVisible();
    await expect(bad.first()).toHaveClass(/bg-crit-100/);
    await expect(bad.first()).toContainText('REF-98765');
    await expect(bad.first().getByRole('button', { name: 'Copy' })).toBeVisible();
    // it can be closed by hand
    await bad.first().getByRole('button', { name: 'Dismiss message' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'REF-98765' })).toHaveCount(0);
  });

  test('[ROW-01] the row buttons say what they do and look different from each other', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/platform');
    const view = page.getByRole('button', { name: /^View / }).first();
    const edit = page.getByRole('button', { name: /^Edit / }).first();
    const del = page.getByRole('button', { name: /^Delete / }).first();
    await expect(view).toHaveAttribute('title', 'View');
    await expect(edit).toHaveAttribute('title', 'Edit');
    await expect(del).toHaveAttribute('title', 'Delete');
    const colours = await Promise.all([view, edit, del].map((b) => b.evaluate((e) => getComputedStyle(e).backgroundColor)));
    expect(new Set(colours).size).toBe(3);
  });
});
