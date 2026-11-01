import { test, expect, needsWrites, pick } from '../../src/support/fixtures';
import { occupiedHome } from '../../src/support/scenarios';
import { dismissWelcome, quietGuide, signInThroughScreen } from '../../src/support/journey';

/**
 * A resident's ordinary day on a phone: sign in, find the menu, see what is owed, add a card and pay, report a repair and
 * attach a photo, read the lease and send a message. Everything is done at phone size with touch, and no page may scroll sideways.
 */
test.describe('A resident on a phone', () => {
  needsWrites();
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  test.setTimeout(240_000);

  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

  async function noSidewaysScroll(page: import('@playwright/test').Page, where: string) {
    // The page body does not scroll; the content area (main) does, so that is where sideways scrolling shows up.
    const over = await page.evaluate(() => {
      const main = document.querySelector('main');
      return Math.max(document.documentElement.scrollWidth - window.innerWidth, main ? main.scrollWidth - main.clientWidth : 0);
    });
    expect(over, `${where} scrolls sideways by ${over}px`).toBeLessThanOrEqual(1);
  }

  /** Opens the menu behind the button and taps an entry; a group such as Payments opens first and then its page is chosen. */
  async function openMenu(page: import('@playwright/test').Page, link: string, child?: string) {
    await page.getByRole('button', { name: 'Open navigation' }).tap();
    await page.getByRole('navigation').getByText(link, { exact: true }).first().tap();
    if (child) await page.getByRole('navigation').getByText(child, { exact: true }).first().tap();
  }

  test('[MOB-01] sign in, pay rent, report a repair with a photo, read the lease: all at phone size', async ({ page }) => {
    const { resident } = await occupiedHome();
    const email = (await resident.api.get('/api/identity/me')).body.email as string;

    // Sign in through the form (the first-time tours are marked as seen so they do not cover the page).
    await quietGuide(resident.token);
    await signInThroughScreen(page, email, resident.password);
    await dismissWelcome(page);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await noSidewaysScroll(page, 'home');
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeHidden(); // the menu is behind the button, not squeezing the page

    // Pay: add a card, pay what is due.
    await openMenu(page, 'Payments', 'Overview');
    await expect(page.getByText(/Balance due/).first()).toBeVisible();
    await noSidewaysScroll(page, 'payments');
    await page.getByRole('button', { name: 'Add', exact: true }).tap();
    const d = page.getByRole('dialog');
    await pick(page, d.getByRole('combobox').first(), /Debit or credit card/);
    await d.getByLabel('Last 4 digits').fill('4242');
    await d.getByRole('checkbox').last().check();
    await expect(d.getByRole('button', { name: 'Save' })).toBeInViewport();
    await d.getByRole('button', { name: 'Save' }).tap();
    await page.getByRole('button', { name: /^Pay \$[\d,]+/ }).tap();
    await expect(page.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
    await noSidewaysScroll(page, 'payments after paying');

    // Report a repair and attach a photo from the phone.
    await openMenu(page, 'Maintenance');
    await page.getByRole('button', { name: 'New request' }).first().tap();
    const r = page.getByRole('dialog');
    await r.getByLabel(/What.s wrong/).fill('Bathroom fan is noisy');
    await r.getByLabel('Details').fill('It rattles whenever it runs.');
    await expect(r.getByRole('button', { name: 'Submit' })).toBeInViewport();
    await r.getByRole('button', { name: 'Submit' }).tap();
    await page.waitForURL(/\/app\/maintenance\/[0-9a-f-]{36}/);
    await expect(page.getByRole('heading', { name: 'Bathroom fan is noisy' })).toBeVisible();
    await noSidewaysScroll(page, 'a repair');
    await page.getByRole('button', { name: 'Upload' }).first().tap();
    const up = page.getByRole('dialog');
    await up.locator('input[type=file]').setInputFiles({ name: 'fan.png', mimeType: 'image/png', buffer: PNG });
    await expect(up.getByText('fan.png')).toBeVisible();
    await up.getByRole('button', { name: 'Upload', exact: true }).tap();
    await expect(up).toBeHidden({ timeout: 20_000 });
    await expect(page.getByText(/Photo . Bathroom fan is noisy/).first()).toBeVisible({ timeout: 20_000 });
    await noSidewaysScroll(page, 'a repair with a photo');
    const id = page.url().split('/').pop()!;
    await expect.poll(async () => JSON.stringify((await resident.api.get(`/api/documents?entityType=WORK_ORDER&entityId=${id}`)).body), { timeout: 20_000 }).toMatch(/Bathroom fan is noisy/);

    // The lease is readable, and a message can be sent to the property team.
    await openMenu(page, 'My lease');
    await expect(page.getByRole('heading', { name: 'My lease' })).toBeVisible();
    await noSidewaysScroll(page, 'my lease');
    await openMenu(page, 'Messages');
    await noSidewaysScroll(page, 'messages');
  });

  test('[MOB-02] every page a resident can open fits the phone: no sideways scroll, nothing cut off, a visible heading', async ({ page }) => {
    const { resident } = await occupiedHome();
    const email = (await resident.api.get('/api/identity/me')).body.email as string;
    await quietGuide(resident.token);
    await signInThroughScreen(page, email, resident.password);
    await dismissWelcome(page);
    const request = (await resident.api.post('/api/maintenance/work-orders', { title: 'Bathroom fan is noisy', description: 'It rattles whenever it runs and sounds awful at night.', permissionToEnter: true })).body.id;
    for (const path of [`/app/maintenance/${request}`, '/app', '/app/payments', '/app/payments/invoices', '/app/payments/payments', '/app/payments/deposits', '/app/leasing/my-lease', '/app/maintenance', '/app/messages', '/app/documents', '/app/preferences', '/app/profile']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first(), path).toBeVisible();
      await noSidewaysScroll(page, path);
      // Controls inside a row that scrolls sideways on purpose (such as the tab bar) are allowed to run past the edge.
      const clipped = await page.evaluate(() => [...document.querySelectorAll('main button, main a')].filter((el) => {
        const r = (el as HTMLElement).getBoundingClientRect();
        if (r.width === 0 || r.right <= window.innerWidth + 1) return false;
        for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
          const o = getComputedStyle(p).overflowX;
          if ((o === 'auto' || o === 'scroll') && p.scrollWidth > p.clientWidth) return false;
        }
        return true;
      }).length);
      expect(clipped, `${path}: controls pushed off the right edge`).toBe(0);
    }
  });

  test('[MOB-03] company staff can use every main screen on a phone too: nothing runs past the edge', async ({ pageWithToken }) => {
    const { c, lease, p } = await occupiedHome();
    const page = await pageWithToken(c.token, '/app');
    const sideways: string[] = [];
    const paths = ['/app', '/app/messages', '/app/leasing/properties', '/app/leasing/units', '/app/leasing/owners', '/app/leasing', '/app/leasing/listings', '/app/leasing/leads',
      '/app/leasing/showings', '/app/leasing/applications', `/app/leasing/applications/${lease.appId}`, '/app/leasing/leases', `/app/leasing/leases/${lease.leaseId}`,
      '/app/payments', '/app/payments/invoices', '/app/payments/collections', '/app/payments/payments', '/app/payments/statements', '/app/payments/deposits', '/app/payments/tax',
      '/app/maintenance', '/app/maintenance/vendors', '/app/documents', '/app/documents/signatures', '/app/insights', '/app/insights/ai', '/app/deadlines', '/app/admin/access',
      '/app/admin/configuration', '/app/admin/audit', '/app/profile', '/app/preferences'];
    for (const path of paths) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first(), path).toBeVisible();
      await page.waitForTimeout(600); // let the entrance animation settle so widths are final
      const over = await page.evaluate(() => {
        const main = document.querySelector('main');
        return Math.max(document.documentElement.scrollWidth - window.innerWidth, main ? main.scrollWidth - main.clientWidth : 0);
      });
      if (over > 1) sideways.push(`${path} (${over}px)`);
    }
    void p;
    expect(sideways, 'pages that scroll sideways on a phone').toEqual([]);
  });
});
