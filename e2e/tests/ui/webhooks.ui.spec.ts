import { test, expect, needsWrites, watchErrors } from '../../src/support/fixtures';
import { onboardCompany } from '../../src/support/company';
import { startReceiver } from '../../src/support/receiver';

/** The Webhooks page: endpoints in one tidy row each, with the rarely used actions in a menu. */
test.describe('Webhooks page', () => {
  needsWrites();
  test.setTimeout(120_000);

  test('[HOOK-06] each endpoint is one clean row: its events in a chip, last-day counts, Test and Edit up front and the rest in a menu', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const rx = await startReceiver();
    try {
      const events = (await c.api.get('/api/workflow/webhooks/events')).body.map((e: any) => e.key);
      await c.api.post('/api/workflow/webhooks', { name: 'Slack channel', url: rx.url(), events });
      await c.api.post('/api/workflow/webhooks', { name: 'CRM sync', url: rx.url('/crm'), events: events.slice(0, 3) });
      const page = await pageWithToken(c.token, '/app/admin/webhooks');
      const problems = watchErrors(page);
      await expect(page.getByRole('heading', { name: 'Webhooks', exact: true })).toBeVisible();

      const slack = page.getByRole('row').filter({ hasText: 'Slack channel' });
      const crm = page.getByRole('row').filter({ hasText: 'CRM sync' });
      await expect(slack.getByText(`All ${events.length} events`)).toBeVisible();
      await expect(crm.getByText('+1 more')).toBeVisible();
      await expect(slack.getByText('No activity')).toBeVisible();

      // Test and the menu button sit on the same line (nothing wraps into a second row).
      const testBox = await slack.getByRole('button', { name: /Send a test event to Slack channel/ }).boundingBox();
      const moreBox = await slack.getByRole('button', { name: /More actions for Slack channel/ }).boundingBox();
      expect(Math.abs(testBox!.y - moreBox!.y)).toBeLessThan(12);
      await expect(slack.getByRole('button', { name: /Edit Slack channel/ })).toBeVisible();

      // The menu holds the rarely used actions, closes with Escape, and works.
      await slack.getByRole('button', { name: /More actions for Slack channel/ }).click();
      await expect(page.getByRole('menuitem')).toHaveText(['Switch off', 'Rotate secret', 'Delete']);
      await page.keyboard.press('Escape');
      await expect(page.getByRole('menuitem')).toHaveCount(0);
      await slack.getByRole('button', { name: /More actions for Slack channel/ }).click();
      await page.getByRole('menuitem', { name: 'Switch off' }).click();
      await expect(slack.getByText('Switched off', { exact: true })).toBeVisible();
      await slack.getByRole('button', { name: /More actions for Slack channel/ }).click();
      await expect(page.getByRole('menuitem').first()).toHaveText('Switch on');
      await page.keyboard.press('Escape');

      expect(problems).toEqual([]);
    } finally { await rx.close(); }
  });
});
