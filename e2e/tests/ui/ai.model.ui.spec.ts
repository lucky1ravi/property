import { test, expect, watchErrors } from '../../src/support/fixtures';

test.describe('AI screens with a model connected', () => {
  test.skip(!process.env.E2E_FAKE_AI, 'needs the stand-in model: start the stack with E2E_FAKE_AI=1');

  test('[AIM-07] a manager asks about vacant homes: the answer, what was looked up and a way to export it', async ({ pageAs }) => {
    const page = await pageAs('manager', '/app');
    const problems = watchErrors(page);
    await page.getByRole('button', { name: 'Ask PropertyOS' }).click();
    const panel = page.getByRole('complementary', { name: 'PropertyOS assistant' });
    await panel.getByLabel('Message').fill('Which units are vacant?');
    await panel.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(panel.getByText(/Here is what I found/)).toBeVisible({ timeout: 40_000 });
    await expect(panel.getByText(/Looked up:/)).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Export chat' })).toBeEnabled();
    expect(problems).toEqual([]);
  });

  test('[AIM-08] the repair-photo tool shows the triage the model produced', async ({ pageAs }) => {
    const page = await pageAs('admin', '/app/insights/ai');
    await page.getByRole('tab', { name: 'Repair photo' }).click();
    await page.locator('input[type=file]').setInputFiles({
      name: 'leak.png', mimeType: 'image/png',
      buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64'),
    });
    await page.getByRole('button', { name: /Analy[sz]e|Check|Triage/i }).first().click();
    await expect(page.getByText(/plumbing/i).first()).toBeVisible({ timeout: 30_000 });
  });
});
