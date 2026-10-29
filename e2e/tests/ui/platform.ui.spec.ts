import { test, expect, needsWrites, watchErrors, pick } from '../../src/support/fixtures';
import { onboardCompany } from '../../src/support/company';
import { rows } from '../../src/support/api';

test.describe('Platform administrator screens', () => {
  test('[PLAT-20] overview, companies, product defaults, plans, website and audit render for the platform admin', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/platform');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Platform' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Onboard company' })).toBeVisible();
    await expect(page.getByText('Companies', { exact: true }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Details' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Features' }).first()).toBeVisible();
    await page.goto('/app/admin/defaults');
    await expect(page.getByRole('heading', { name: 'Product defaults' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save for all companies' })).toBeVisible();
    await page.goto('/app/admin/plans');
    await expect(page.getByRole('heading', { name: 'Plans' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'New plan' })).toBeVisible();
    for (const name of ['Starter', 'Growth', 'Enterprise']) await expect(page.getByText(name).first()).toBeVisible();
    await page.goto('/app/admin/website');
    await expect(page.getByRole('heading', { name: 'Website' }).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Enquiries' })).toBeVisible();
    await page.goto('/app/admin/audit');
    await expect(page.getByRole('heading', { name: 'Audit log' }).first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[PLAT-20] the company details open and the suspend dialog needs a reason', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/platform');
    await page.getByRole('button', { name: 'Details' }).first().click();
    await expect(page.getByRole('dialog').or(page.locator('aside[role=dialog], [aria-modal=true]')).first()).toBeVisible();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Suspend' }).first().click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Suspend company' })).toBeDisabled();
    await d.getByRole('button', { name: 'Cancel' }).click(); // never confirm on shared demo data
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test.describe('creates data', () => {
    needsWrites();
    test('[PLAT-20] onboard a company from the screen, then suspend it with a reason', async ({ pageAs, publicApi }) => {
      const page = await pageAs('platform', '/app/admin/platform');
      const sfx = Math.random().toString(36).slice(2, 8);
      const name = `E2E Screen Co ${sfx}`;
      const email = `boss.${sfx}@e2e.test`;
      await page.getByRole('button', { name: 'Onboard company' }).click();
      const d = page.getByRole('dialog');
      await expect(d.getByRole('button', { name: 'Create company' })).toBeDisabled();
      await d.getByLabel('Company name').fill(name);
      await d.getByLabel('Administrator name').fill('Bea Boss');
      await d.getByLabel('Administrator email').fill(email);
      await d.getByRole('button', { name: 'Create company' }).click();
      const given = page.getByRole('dialog');
      await expect(given.getByText('Give this to the administrator')).toBeVisible();
      await expect(given.getByText(email)).toBeVisible();
      const temp = (await given.locator('dd.font-mono').innerText()).trim();
      expect(temp.length).toBeGreaterThanOrEqual(10);
      await given.getByRole('button', { name: 'Done' }).click();
      const row = page.getByRole('row', { name: new RegExp(name) });
      await expect(row).toBeVisible();

      await row.getByRole('button', { name: 'Suspend' }).click();
      const s = page.getByRole('dialog');
      await expect(s.getByText(`Suspend ${name}?`)).toBeVisible();
      await s.getByLabel('Reason').fill('E2E: unpaid invoice');
      await s.getByRole('button', { name: 'Suspend company' }).click();
      await expect(page.getByRole('row', { name: new RegExp(name) })).toContainText(/Suspended/i);
      // The administrator can no longer sign in.
      const r = await (await publicApi()).post('/api/identity/public/login', { email, password: temp });
      expect(r.status).toBeGreaterThanOrEqual(400);
    });
  });
});

test.describe('Company configuration screen', () => {
  needsWrites();
  test('[CFG-07] every section opens and a changed feature switch saves and persists', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/admin/configuration');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Configuration' }).first()).toBeVisible();
    for (const s of ['Fees', 'Invoicing', 'Approvals', 'Screening', 'Service levels', 'Renewals', 'AI', 'Notifications', 'Branding', 'Payments and tax', 'Photo review', 'Files and documents', 'Deadline alerts', 'Messaging', 'State rules', 'Features']) {
      await page.locator('main').getByRole('button', { name: s, exact: true }).first().click();
      await expect(page.locator('main')).not.toContainText(/something went wrong/i);
    }
    await expect(page.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    const sw = page.getByRole('switch').first();
    const was = await sw.isChecked();
    await sw.click();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await page.reload();
    expect(await page.getByRole('switch').first().isChecked()).toBe(!was);
    expect(problems).toEqual([]);
  });
});
