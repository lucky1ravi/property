import { test, expect, watchErrors } from '../../src/support/fixtures';

test.describe('AI screens', () => {
  test('[AI-08] the assistant panel answers a question and shows what it looked up', async ({ pageAs }) => {
    const page = await pageAs('manager', '/app');
    const problems = watchErrors(page);
    await page.getByRole('button', { name: 'Ask PropertyOS' }).click();
    const panel = page.getByRole('complementary', { name: 'PropertyOS assistant' });
    await expect(panel.getByText(/only records your role can open/i)).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Export chat' })).toBeDisabled();
    await panel.getByLabel('Message').fill('How many properties do we manage?');
    await panel.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(panel.getByText('How many properties do we manage?')).toBeVisible();
    await expect(panel.getByText(/Looked up:/)).toBeVisible({ timeout: 40_000 });
    await expect(panel.getByRole('button', { name: 'Export chat' })).toBeEnabled();
    await panel.getByRole('button', { name: 'Close assistant' }).click();
    await expect(panel).toHaveCount(0);
    expect(problems).toEqual([]);
  });

  test('[AI-08] the AI tools screen has every tool and the billing check runs', async ({ pageAs }) => {
    const page = await pageAs('admin', '/app/insights/ai');
    await expect(page.getByRole('heading', { name: 'AI tools' }).first()).toBeVisible();
    for (const t of ['Billing check', 'Resident outlook', 'Lease summary', 'Owner statement', 'Repair photo', 'Fair housing check'])
      await expect(page.getByRole('tab', { name: t })).toBeVisible();
    await page.getByRole('button', { name: 'Check my billing' }).click();
    await expect(page.getByText(/Checked \d+ invoices/)).toBeVisible({ timeout: 30_000 });
    await page.getByRole('tab', { name: 'Resident outlook' }).click();
    await expect(page.locator('main')).not.toContainText(/something went wrong/i);
    await page.getByRole('tab', { name: 'Fair housing check' }).click();
    await expect(page.locator('main')).not.toContainText(/something went wrong/i);
  });
});

test.describe('Files and documents screens', () => {
  test('[DOC-07] files, photos and e-signature screens render with their tabs', async ({ pageAs }) => {
    const page = await pageAs('admin', '/app/documents');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Files and documents' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Upload' }).first()).toBeVisible();
    for (const t of [/^All files/, /^Applicant files/, /^Leases and notices/, /^E-signed/, /^Archived/, /^Trash/]) {
      await page.getByRole('tab', { name: t }).click();
      await expect(page.locator('main')).not.toContainText(/something went wrong/i);
    }
    await page.getByRole('tab', { name: /^All files/ }).click();
    await expect(page.locator('main table tbody tr').first()).toBeVisible();
    await page.goto('/app/documents/photos');
    await expect(page.getByRole('heading', { name: 'Photos and review' }).first()).toBeVisible();
    for (const t of [/^Needs review/, 'Published', 'Rejected', 'All']) await page.getByRole('tab', { name: t }).click();
    await page.goto('/app/documents/signatures');
    await expect(page.getByRole('heading', { name: 'E-signatures' }).first()).toBeVisible();
    for (const t of ['Waiting', 'Completed', 'Declined or cancelled', 'All']) await page.getByRole('tab', { name: t }).click();
    expect(problems).toEqual([]);
  });

  test('[DOC-07] the upload dialog opens and a file can be chosen', async ({ pageAs }) => {
    const page = await pageAs('admin', '/app/documents');
    await page.getByRole('button', { name: 'Upload' }).first().click();
    const d = page.getByRole('dialog');
    await expect(d).toBeVisible();
    const input = d.locator('input[type=file]');
    await expect(input).toHaveCount(1);
    await input.setInputFiles({ name: 'e2e-note.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF') });
    await expect(d.getByText('e2e-note.pdf')).toBeVisible();
  });
});
