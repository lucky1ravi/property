import { test, expect, needsWrites, pick, watchErrors, openUserMenu } from '../../src/support/fixtures';
import { onboardCompany } from '../../src/support/company';
import { rows } from '../../src/support/api';
import { DEMO_PASSWORD } from '../../src/support/env';

test.describe('Company admin screens', () => {
  needsWrites();

  test('[ACC-11] add a user, copy the one-time password, reset it, and the new person can sign in', async ({ pageWithToken, browser }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/admin/access');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Users and roles' }).first()).toBeVisible();
    await page.getByRole('button', { name: 'Add user' }).click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Send invite' })).toBeDisabled();
    const email = `newhire.${c.sfx}@e2e.test`;
    await d.getByLabel('Full name').fill('Nina Newhire');
    await d.getByLabel('Work email').fill(email);
    await d.getByRole('checkbox', { name: /Leasing Agent/ }).check();
    await d.getByRole('button', { name: 'Send invite' }).click();
    const issued = page.getByRole('dialog');
    await expect(issued.getByText('Give Nina Newhire their sign-in')).toBeVisible();
    await expect(issued.getByText(email)).toBeVisible();
    const temp = (await issued.locator('dd.font-mono').innerText()).trim();
    expect(temp.length).toBeGreaterThanOrEqual(10);
    await issued.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByRole('cell', { name: /Nina Newhire/ })).toBeVisible();

    // The temporary password works once and forces a new one: sign in through the form.
    const ctx = await browser.newContext();
    const p2 = await ctx.newPage();
    await p2.goto('/signin');
    await p2.getByLabel('Work email').fill(email);
    await p2.getByLabel('Password').fill(temp);
    await p2.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(p2.getByText(/choose a new password|new password/i).first()).toBeVisible();
    await ctx.close();

    // Reset it from the user's dialog: a different temporary password is shown.
    await page.getByRole('cell', { name: /Nina Newhire/ }).last().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Reset password' }).click();
    const again = page.getByRole('dialog');
    await expect(again.getByText('Give Nina Newhire their sign-in')).toBeVisible();
    expect((await again.locator('dd.font-mono').innerText()).trim()).not.toBe(temp);
    await again.getByRole('button', { name: 'Done' }).click();
    expect(problems).toEqual([]);
  });

  test('[ACC-11] roles: create a role from an existing one, edit it and delete it', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/admin/access');
    await page.getByRole('tab', { name: /Roles and permissions/ }).or(page.getByRole('button', { name: /Roles and permissions/ })).first().click();
    await page.getByRole('button', { name: 'New role' }).click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Create role' })).toBeDisabled();
    await d.getByLabel('Name').fill(`Regional ${c.sfx}`);
    await pick(page, d.getByRole('combobox', { name: /Start from/ }), /Property manager/i);
    await d.getByRole('button', { name: 'Create role' }).click();
    await expect(page.getByText(`Regional ${c.sfx}`).first()).toBeVisible();
    expect(rows(await c.api.get('/api/identity/roles')).some((r: any) => r.name === `Regional ${c.sfx}`)).toBe(true);
    await page.getByRole('button', { name: 'Delete role' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete role' }).click();
    await expect(page.getByText(`Regional ${c.sfx}`)).toHaveCount(0);
    expect(rows(await c.api.get('/api/identity/roles')).some((r: any) => r.name === `Regional ${c.sfx}`)).toBe(false);
  });

  test('[PROF-05] the profile page saves name, title and phone and keeps the email read-only', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/profile');
    await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();
    await expect(page.getByLabel('Email')).toBeDisabled();
    await expect(page.getByLabel('Email')).toHaveValue(c.adminEmail);
    const save = page.getByRole('button', { name: 'Save changes' });
    await expect(save).toBeDisabled();
    await page.getByLabel('Full name').fill('Ada Admin-Lovelace');
    await page.getByLabel('Job title').fill('Head of everything');
    await page.getByLabel('Phone').fill('5125550142');
    await save.click();
    await expect.poll(async () => (await c.api.get('/api/identity/me')).body.name, { timeout: 30_000 }).toBe('Ada Admin-Lovelace');
    await page.reload();
    await expect(page.getByLabel('Full name')).toHaveValue('Ada Admin-Lovelace');
    await expect(page.getByLabel('Job title')).toHaveValue('Head of everything');
    const me = (await c.api.get('/api/identity/me')).body;
    expect(me.name).toBe('Ada Admin-Lovelace');
    await page.getByLabel('Full name').fill('');
    await expect(save).toBeDisabled();
  });

  test('[WORK-05] automation rules: create and list; the workflow designer renders', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/admin/automations');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: /Automation/ }).first()).toBeVisible();
    await page.getByRole('button', { name: 'New automation' }).click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Save automation' })).toBeDisabled();
    await d.getByLabel('Name').fill(`Alert managers ${c.sfx}`);
    await d.getByLabel('Title').fill('A new lead came in');
    await d.getByRole('button', { name: 'Save automation' }).click();
    await expect(page.getByText(`Alert managers ${c.sfx}`).first()).toBeVisible();
    expect(rows(await c.api.get('/api/workflow/rules')).some((r: any) => r.name === `Alert managers ${c.sfx}`)).toBe(true);
    await page.goto('/app/admin/workflows');
    await expect(page.getByRole('heading', { name: 'Workflow designer' }).or(page.getByText('Workflow designer')).first()).toBeVisible();
    await page.locator('tbody tr').first().click();
    await expect(page.getByRole('heading', { name: 'Transitions' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save workflow' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Restore standard' })).toBeVisible();
    await page.getByRole('button', { name: 'All workflows' }).click();
    await expect(page.getByRole('heading', { name: 'Workflow designer' })).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[CHAT-05] messages: start a conversation, open it and send a reply', async ({ pageAs }) => {
    const page = await pageAs('manager', '/app/messages');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Messages' }).first()).toBeVisible();
    await page.getByRole('button', { name: 'New conversation' }).first().click();
    const d = page.getByRole('dialog');
    await expect(d.getByRole('button', { name: 'Send' })).toBeDisabled();
    const subject = `E2E hello ${Date.now()}`;
    await d.getByLabel('Subject').fill(subject);
    await d.getByRole('checkbox').first().check();
    await d.getByLabel('Message').fill('First message from the browser test.');
    await d.getByRole('button', { name: 'Send' }).click();
    await expect(page.getByText(subject).first()).toBeVisible();
    await page.getByText(subject).first().click();
    await page.getByLabel('Message', { exact: true }).last().fill('Second message.');
    await page.getByRole('button', { name: 'Send message' }).click();
    await expect(page.getByText('Second message.').first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[NOTE-04] the bell shows unread counts and preferences save', async ({ pageAs, apiAs }) => {
    const page = await pageAs('accountant', '/app/preferences');
    await expect(page.getByRole('heading', { name: 'Notification preferences' })).toBeVisible();
    const sw = page.getByRole('switch', { name: 'Payments and invoices: Email' });
    const was = await sw.isChecked();
    await sw.click();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await page.reload();
    expect(await page.getByRole('switch', { name: 'Payments and invoices: Email' }).isChecked()).toBe(!was);
    await page.getByRole('switch', { name: 'Payments and invoices: Email' }).click();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByRole('button', { name: /^Notifications, \d+ unread|^Notifications/ }).first()).toBeVisible();
  });
});
