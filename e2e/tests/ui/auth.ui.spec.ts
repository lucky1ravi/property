import { test, expect, openUserMenu, signInWithPicker, watchErrors, pick } from '../../src/support/fixtures';
import { PERSONAS } from '../../src/support/personas';
import { DEMO_PASSWORD } from '../../src/support/env';

const token = (page: import('@playwright/test').Page) => page.evaluate(() => localStorage.getItem('pos.token'));

test.describe('Sign in and out', () => {
  test('[AUTH-10] the sign-in page offers demo personas that fill the form, and signing in opens the app', async ({ page }) => {
    await page.goto('/signin');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    const send = page.getByRole('button', { name: 'Sign in', exact: true });
    await expect(send).toBeDisabled();
    await pick(page, page.getByRole('combobox', { name: 'Fill in a demo user' }), 'Broker: Marcus Johnson');
    await expect(page.getByLabel('Work email')).toHaveValue(PERSONAS.broker.email);
    await expect(page.getByLabel('Password')).toHaveValue(DEMO_PASSWORD);
    await send.click();
    await page.waitForURL(/\/app$/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Marcus');
  });

  test('[AUTH-10] the persona list covers every role and a click signs that person in', async ({ page }) => {
    await page.goto('/signin');
    await page.getByRole('button', { name: 'Demo personas' }).click();
    for (const who of ['Marcus Johnson', 'Emily Rodriguez', 'David Chen', 'Sarah Mitchell', 'Robert Williams', 'Nathan Greene', 'Michael Brown', 'Ashley Davis', 'Carlos Ramirez', 'Jordan Lee', 'Priya Patel', 'Alex Morgan', 'Kevin Park']) {
      await expect(page.getByRole('button', { name: new RegExp(who) })).toBeVisible();
    }
    await page.getByRole('button', { name: /Priya Patel/ }).click();
    await page.waitForURL(/\/app$/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Priya');
  });

  test('[AUTH-02] a wrong password shows a friendly message and stays on the page', async ({ page }) => {
    await page.goto('/signin');
    await page.getByLabel('Work email').fill(PERSONAS.admin.email);
    await page.getByLabel('Password').fill('definitely-wrong-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText(/email or password|incorrect|isn.t right|try again/i);
    await expect(page).toHaveURL(/\/signin/);
    expect(await token(page)).toBeNull();
    await page.getByLabel('Work email').fill('nobody.here@e2e.test');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    const unknown = await page.getByRole('alert').innerText();
    await page.getByLabel('Work email').fill(PERSONAS.admin.email);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    expect(await page.getByRole('alert').innerText()).toBe(unknown); // an unknown email reads the same as a wrong password
  });

  test('[AUTH-11] a signed-in person who opens the sign-in page is sent to the app', async ({ pageAs }) => {
    const page = await pageAs('admin', '/signin');
    await page.waitForURL(/\/app/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Sarah');
  });

  test('[AUTH-12] a signed-out visitor to an app page is sent to sign-in, and a broken session ends cleanly', async ({ page }) => {
    await page.goto('/app/payments/invoices');
    await page.waitForURL(/\/signin/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await page.evaluate(() => localStorage.setItem('pos.token', 'not-a-real-token'));
    await page.goto('/app');
    await page.waitForURL(/\/signin/);
    await expect(page.getByText(/session ended|sign in again/i).first()).toBeVisible();
    expect(await token(page)).toBeNull();
  });

  test('[AUTH-13] signing out clears the session and protects the app', async ({ pageAs }) => {
    const page = await pageAs('accountant', '/app');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Priya');
    await openUserMenu(page, 'Priya Patel');
    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.waitForURL(/\/signin/);
    expect(await token(page)).toBeNull();
    await page.goto('/app/payments/invoices');
    await page.waitForURL(/\/signin/);
    await page.goto('/app');
    await page.waitForURL(/\/signin/);
  });

  test('[AUTH-14] the session survives a reload and a new tab', async ({ pageAs }) => {
    const page = await pageAs('manager', '/app/leasing/properties');
    await expect(page.getByRole('heading', { name: 'Properties' }).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Properties' }).first()).toBeVisible();
    expect(page.url()).toContain('/app/leasing/properties');
    const tab = await page.context().newPage();
    await tab.goto('/app/maintenance');
    await expect(tab.getByRole('heading', { name: 'Maintenance' }).first()).toBeVisible();
  });

  test('[PROF-06] the header shows the person\'s name and company', async ({ pageAs }) => {
    for (const k of ['admin', 'harbor', 'platform'] as const) {
      const page = await pageAs(k, '/app');
      const header = page.locator('header').first();
      await expect(header).toContainText(PERSONAS[k].name);
      await expect(header).toContainText(PERSONAS[k].company);
      await page.context().close();
    }
    const page = await pageAs('tenant', '/app');
    await expect(page.locator('header').first()).toContainText('Michael Brown');
    await expect(page.locator('header').first()).toContainText('Resident');
  });

  test('[AUTH-10] choosing a persona signs in through the picker helper for every role', async ({ page }) => {
    const problems = watchErrors(page);
    await signInWithPicker(page, 'Tenant: Michael Brown');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Michael');
    expect(problems).toEqual([]);
  });
});
