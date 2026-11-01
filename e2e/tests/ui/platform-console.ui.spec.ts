import { test, expect, watchErrors, pick } from '../../src/support/fixtures';

/** What the platform operator sees: the home with what needs attention, service health, company usage and the delivery log. */
test.describe('Platform operator console screens', () => {
  test('[CONSOLE-04] the platform home shows what needs attention, including whether every service is up', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Platform overview' })).toBeVisible();
    await expect(page.getByText('Services', { exact: true })).toBeVisible();
    await expect(page.getByText('8 of 8 up')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('New enquiries').first()).toBeVisible();
    await expect(page.getByText('Recently onboarded')).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[CONSOLE-05] service health lists every part of the product as running and can be checked again', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/health');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Service health' })).toBeVisible();
    await expect(page.getByText('All running')).toBeVisible({ timeout: 20_000 });
    for (const part of ['Sign-in and companies', 'Leasing', 'Payments', 'Maintenance', 'Notifications and automation', 'AI assistant', 'Documents and e-signatures', 'Database'])
      await expect(page.getByRole('cell', { name: part })).toBeVisible();
    await expect(page.getByText('Running', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('8 of 8', { exact: true })).toBeVisible();
    const again = page.waitForResponse((r) => r.url().includes('/api/identity/platform/health') && r.status() === 200);
    await page.getByRole('button', { name: 'Check now' }).click();
    await again;
    expect(problems).toEqual([]);
  });

  test('[CONSOLE-06] company usage shows each company with how active it is, and the filter narrows the list', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/usage');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Company usage' })).toBeVisible();
    for (const label of ['Companies', 'Gone quiet', 'Not started']) await expect(page.getByText(label, { exact: true }).first()).toBeVisible();
    for (const h of ['Company', 'Use', 'People', 'Last sign-in']) await expect(page.getByRole('columnheader', { name: new RegExp(h) }).first()).toBeVisible();
    await expect(page.getByRole('row').nth(2)).toBeVisible();
    await pick(page, page.getByRole('combobox', { name: 'Filter by use' }), 'Not started');
    await expect(page.getByRole('cell', { name: 'Active', exact: true })).toHaveCount(0);
    await pick(page, page.getByRole('combobox', { name: 'Filter by use' }), 'All companies');
    await expect(page.getByText(/Showing 1/).first()).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('[CONSOLE-07] the delivery log lists messages with the company, channel and outcome, and can be filtered', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/deliveries');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Delivery log' })).toBeVisible();
    for (const label of ['Sent, 7 days', 'Emails', 'Text messages', 'Held for quiet hours']) await expect(page.getByText(label, { exact: true })).toBeVisible();
    for (const h of ['When', 'Company', 'Channel', 'To', 'Outcome']) await expect(page.getByRole('columnheader', { name: new RegExp(h) }).first()).toBeVisible();
    await expect(page.getByRole('row').nth(2)).toBeVisible({ timeout: 20_000 });
    await pick(page, page.getByRole('combobox', { name: 'Filter by outcome' }), 'Sent');
    await expect(page.getByText('Held', { exact: true })).toHaveCount(0);
    expect(problems).toEqual([]);
  });

  test('[CONSOLE-08] none of the operator pages open for a company administrator', async ({ pageAs }) => {
    const page = await pageAs('admin', '/app');
    for (const path of ['/app/admin/health', '/app/admin/usage', '/app/admin/deliveries', '/app/admin/enquiries', '/app/admin/features']) {
      await page.goto(path);
      await expect(page.getByText(/don.t have access/i).first(), path).toBeVisible();
    }
  });
  test('[CONSOLE-10] Details asks why, then shows the whole recipient and the subject, and says it was recorded', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app/admin/deliveries');
    const first = page.getByRole('row').filter({ hasText: 'Email' }).first();
    await expect(first).toBeVisible({ timeout: 20_000 });
    const masked = await first.getByRole('cell').nth(3).innerText();
    expect(masked).toContain('*');
    await first.getByRole('button', { name: /Show details/ }).click();
    const ask = page.getByRole('dialog');
    await expect(ask.getByText('Why do you need to see this?')).toBeVisible();
    await expect(ask.getByText(/recorded in that company.s audit log/)).toBeVisible();
    const go = ask.getByRole('button', { name: 'Show details' });
    await expect(go).toBeDisabled();
    await ask.getByLabel('Reason').fill('too short');
    await expect(go).toBeDisabled();
    await ask.getByLabel('Reason').fill('Support request 5120: a reminder did not arrive');
    await go.click();
    const shown = page.getByRole('dialog');
    await expect(shown.getByText('Message details')).toBeVisible();
    await expect(shown.getByText('The message text is not shown.')).toBeVisible();
    const to = shown.locator('dd, [class*="font-semibold"]').filter({ hasText: '@' }).first();
    await expect(to).not.toContainText('*');
    await shown.getByRole('button', { name: 'Close', exact: true }).last().click();
    // The list itself is still masked.
    await expect(page.getByRole('row').filter({ hasText: 'Email' }).first().getByRole('cell').nth(3)).toContainText('*');
  });
});
