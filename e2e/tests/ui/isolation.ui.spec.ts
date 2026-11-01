import { test, expect, needsWrites } from '../../src/support/fixtures';
import { apiAs } from '../../src/support/api';
import { occupiedHome } from '../../src/support/scenarios';

/**
 * Two companies on one platform. Nothing of company B may reach anyone in company A through the screens: not in a list,
 * not through a pasted link, not in a search or picker, and not even in the data the browser downloads while a page is open.
 * The API layer has its own isolation tests; this is the same promise kept at the level of what a person can see and reach.
 */
test.describe('Two companies side by side', () => {
  needsWrites();
  test.setTimeout(240_000);

  test('[ISO-UI-01] a company never sees another company in lists, links, searches or the data behind its pages', async ({ pageWithToken }) => {
    const a = await occupiedHome();
    const b = await occupiedHome();
    // Things in company B that company A must never meet.
    const secrets = [`Court ${b.p.sfx}`, `Maple ${b.p.sfx}`, b.email];
    const workOrder = (await b.resident.api.post('/api/maintenance/work-orders', { title: `Secret leak ${b.p.sfx}`, description: 'Only company B may see this.', permissionToEnter: true })).body.id;
    secrets.push(`Secret leak ${b.p.sfx}`);
    const bLease = b.lease.leaseId, bApp = b.lease.appId, bProperty = b.p.propertyId, bUnit = b.p.unitId;

    const page = await pageWithToken(a.c.token, '/app/leasing/properties');
    const seen: string[] = [];
    page.on('response', async (r) => {
      const type = r.headers()['content-type'] ?? '';
      if (r.url().includes('/api/') && type.includes('json')) seen.push(await r.text().catch(() => ''));
    });

    // Company A's own data is there; company B's is not, in any list.
    await expect(page.getByText(`Court ${a.p.sfx}`).first()).toBeVisible();
    for (const path of ['/app/leasing/properties', '/app/leasing/units', '/app/leasing/owners', '/app/leasing/listings', '/app/leasing/leads', '/app/leasing/applications',
      '/app/leasing/leases', '/app/payments/invoices', '/app/payments/payments', '/app/maintenance', '/app/documents', '/app/messages', '/app/admin/access']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      const text = await page.locator('main').innerText();
      for (const s of secrets) expect(text, `${path} must not show ${s}`).not.toContain(s);
    }

    // Searching for company B by name finds nothing, in the table search and in the global search.
    await page.goto('/app/leasing/properties');
    await page.getByPlaceholder(/Search/).first().fill(`Court ${b.p.sfx}`);
    await expect(page.getByText(/Nothing matches|No (properties|results)/i).first()).toBeVisible();
    await page.keyboard.press('Control+k');
    await page.getByRole('combobox', { name: 'Search' }).fill(`Court ${b.p.sfx}`);
    await expect(page.getByText(/Nothing matches/)).toBeVisible();
    await expect(page.getByRole('option', { name: new RegExp(`Court ${b.p.sfx}`) })).toHaveCount(0);
    await page.keyboard.press('Escape');

    // The pickers search the company's own records only.
    await page.goto('/app/leasing/properties');
    await page.getByRole('button', { name: 'Add property' }).first().click();
    const d = page.getByRole('dialog');
    await d.getByRole('combobox').first().click();
    await page.getByPlaceholder(/Search/).last().fill(`Maple ${b.p.sfx}`);
    await expect(page.getByRole('option', { name: new RegExp(`Maple ${b.p.sfx}`) })).toHaveCount(0);
    await page.keyboard.press('Escape');

    // Pasting company B's links shows a plain "not found" (or "no access"), never company B's data.
    const links = [`/app/leasing/properties/${bProperty}`, `/app/leasing/units/${bUnit}`, `/app/leasing/leases/${bLease}`, `/app/leasing/applications/${bApp}`, `/app/maintenance/${workOrder}`];
    for (const link of links) {
      await page.goto(link);
      await expect(page.getByText(/not found|no .{0,25} found|don.t have access|couldn.t find/i).first(), link).toBeVisible();
      const text = await page.locator('body').innerText();
      for (const s of secrets) expect(text, `${link} must not show ${s}`).not.toContain(s);
    }

    // And the browser was never even sent company B's data while all of that happened.
    const everything = seen.join('\n');
    for (const s of secrets) expect(everything.includes(s), `no response may contain ${s}`).toBe(false);
    expect(seen.length).toBeGreaterThan(20);
  });

  test('[ISO-UI-02] a resident sees their own home only, and cannot open another company\'s or another resident\'s records by link', async ({ pageWithToken }) => {
    const a = await occupiedHome();
    const b = await occupiedHome();
    const page = await pageWithToken(a.resident.token, '/app/payments');
    await expect(page.getByText(/Balance due/).first()).toBeVisible();
    const seen: string[] = [];
    page.on('response', async (r) => {
      if (r.url().includes('/api/') && (r.headers()['content-type'] ?? '').includes('json')) seen.push(await r.text().catch(() => ''));
    });
    const bInvoices = (await b.c.api.get('/api/billing/invoices')).body;
    const bInvoice = (bInvoices.rows ?? bInvoices)[0].id as string;

    for (const link of [`/app/payments/invoices/${bInvoice}`, `/app/leasing/leases/${b.lease.leaseId}`, `/app/leasing/my-lease`, '/app/maintenance', '/app/messages']) {
      await page.goto(link);
      await expect(page.locator('body')).not.toContainText(b.email);
      await expect(page.locator('body')).not.toContainText(`Court ${b.p.sfx}`);
    }
    // The resident has no staff screens at all.
    for (const staff of ['/app/admin/access', '/app/leasing/owners', '/app/payments/collections', '/app/payments/tax']) {
      await page.goto(staff);
      await expect(page.getByText(/don.t have access/i).first(), staff).toBeVisible();
    }
    const everything = seen.join('\n');
    expect(everything.includes(b.email)).toBe(false);
    expect(everything.includes(`Court ${b.p.sfx}`)).toBe(false);
  });

  test('[ISO-UI-03] a suspended company is locked out while the other company carries on', async ({ pageWithToken }) => {
    const a = await occupiedHome();
    const b = await occupiedHome();
    const platform = await apiAs('platform');
    expect((await platform.put(`/api/identity/platform/orgs/${a.c.orgId}`, { status: 'SUSPENDED', reason: 'Isolation test' })).status).toBe(200);
    const locked = await pageWithToken(a.resident.token, '/app');
    await expect.poll(async () => { await locked.goto('/app'); await locked.waitForTimeout(500); return locked.url(); }, { timeout: 40_000 }).toMatch(/\/signin/);
    const other = await pageWithToken(b.resident.token, '/app/payments');
    await expect(other.getByText(/Balance due/).first()).toBeVisible();
  });
});
