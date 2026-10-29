import { test as base, expect, type Page } from '@playwright/test';
import { tokenFor, apiAs, publicApi, type Api } from './api';
import { PERSONAS, type PersonaKey } from './personas';
import { CAN_WRITE } from './env';

type Fixtures = {
  /** Opens a page already signed in as the given demo user. */
  pageAs: (key: PersonaKey, path?: string) => Promise<Page>;
  /** Opens a page already signed in with a token (for people created during the test). */
  pageWithToken: (token: string, path?: string) => Promise<Page>;
  apiAs: (key: PersonaKey) => Promise<Api>;
  publicApi: () => Promise<Api>;
};

export const test = base.extend<Fixtures>({
  pageAs: async ({ browser }, use) => {
    const contexts: any[] = [];
    await use(async (key, path = '/app') => {
      const token = await tokenFor(key);
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
      contexts.push(ctx);
      // Seed the session once per tab, so signing out in a test really signs the tab out.
      await ctx.addInitScript((t) => { try { if (!sessionStorage.getItem('e2e-seeded')) { localStorage.setItem('pos.token', t as string); sessionStorage.setItem('e2e-seeded', '1'); } } catch { /* ignore */ } }, token);
      const page = await ctx.newPage();
      await page.goto(path);
      return page;
    });
    for (const c of contexts) await c.close();
  },
  pageWithToken: async ({ browser }, use) => {
    const contexts: any[] = [];
    await use(async (token, path = '/app') => {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
      contexts.push(ctx);
      await ctx.addInitScript((t) => { try { if (!sessionStorage.getItem('e2e-seeded')) { localStorage.setItem('pos.token', t as string); sessionStorage.setItem('e2e-seeded', '1'); } } catch { /* ignore */ } }, token);
      const page = await ctx.newPage();
      await page.goto(path);
      return page;
    });
    for (const c of contexts) await c.close();
  },
  apiAs: async ({}, use) => { await use((k) => apiAs(k)); },
  publicApi: async ({}, use) => { await use(() => publicApi()); },
});

export { expect };
export { PERSONAS };

/** Skips a test unless this run may create companies, users and data (local mode). */
export const needsWrites = () => base.skip(!CAN_WRITE, 'creates data or accounts: only runs against a local stack (E2E_MODE=local)');

/** Waits for the page's main area to render something and asserts there is no error screen. */
export async function expectHealthyPage(page: Page, heading?: string | RegExp) {
  const main = page.locator('main');
  await expect(main).toBeVisible();
  await expect(main).not.toContainText(/something went wrong|page not found/i);
  if (heading) await expect(page.getByRole('heading', { name: heading }).first()).toBeVisible();
}

/** Opens the account menu in the top bar (the button that shows the person's name). */
export async function openUserMenu(page: Page, name: string) {
  await page.locator('header').first().getByRole('button', { name: new RegExp(name) }).click();
}

/** Signs in through the sign-in form with the demo-persona picker, the way a person would. */
export async function signInWithPicker(page: Page, label: string) {
  await page.goto('/signin');
  await pick(page, page.getByRole('combobox', { name: 'Fill in a demo user' }), label);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(/\/app/);
}

/** Collects console errors and failed API calls while a page is used. */
export function watchErrors(page: Page) {
  const problems: string[] = [];
  page.on('pageerror', (e) => problems.push(`script error: ${e.message}`));
  page.on('response', (r) => { if (r.url().includes('/api/') && r.status() >= 500) problems.push(`${r.status()} ${r.url()}`); });
  return problems;
}

/** Chooses an option in the product's dropdown (a combobox button that opens a searchable list). */
export async function pick(page: Page, trigger: import('@playwright/test').Locator, option: string | RegExp) {
  await trigger.click();
  await page.getByRole('option', { name: option }).first().click();
}
