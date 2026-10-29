import { test, expect, watchErrors } from '../../src/support/fixtures';
import type { Page } from '@playwright/test';
import type { PersonaKey } from '../../src/support/personas';

/** Opens every collapsed menu group, then returns the labels of every link in the main sidebar. */
export async function menuLabels(page: Page): Promise<string[]> {
  const nav = page.locator('aside nav[aria-label="Main"]');
  await expect(nav.getByRole('link', { name: 'Home' })).toBeVisible();
  for (let i = 0; i < 12; i++) {
    const closed = nav.locator('button[aria-expanded="false"]');
    if (!(await closed.count())) break;
    await closed.first().click();
  }
  return (await nav.getByRole('link').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
}

const FILES = ['All files', 'Photos and review', 'E-signatures'];
const LEASING = ['Overview', 'Listings', 'Leads', 'Showings', 'Applications', 'Leases'];
const PORTFOLIO = ['Properties', 'Units', 'Owners'];
const MAINT = ['Work orders', 'Vendors', 'Preventive maintenance'];
const AUTOMATION = ['Automation rules', 'Workflow designer'];

/** What each role is meant to see in the sidebar (the product's menu policy). */
const MENU: Record<string, string[]> = {
  platform: ['Home', 'Administration', 'Platform', 'Product defaults', 'Plans', 'Website'],
  admin: ['Home', 'Messages', 'Broker cockpit', ...PORTFOLIO, ...LEASING, 'Overview', 'Invoices', 'Collections', 'Payments received', 'Owner statements', 'Deposits', 'Tax and compliance', ...MAINT, ...FILES, 'AI tools', 'Reports', 'Opportunities', 'Deadlines and alerts', ...AUTOMATION, 'Users and roles', 'Configuration', 'Audit log'],
  broker: ['Home', 'Messages', 'Broker cockpit', ...PORTFOLIO, ...LEASING, 'Overview', 'Invoices', 'Payments received', 'Owner statements', 'Deposits', ...MAINT, ...FILES, 'AI tools', 'Reports', 'Opportunities', 'Deadlines and alerts', ...AUTOMATION],
  agent: ['Home', 'Messages', ...PORTFOLIO, ...LEASING, ...FILES, 'AI tools'],
  accountant: ['Home', 'Messages', ...PORTFOLIO, 'Leasing', 'Overview', 'Invoices', 'Collections', 'Payments received', 'Owner statements', 'Deposits', 'Tax and compliance', ...MAINT, ...FILES, 'AI tools', 'Reports', 'Deadlines and alerts', 'Administration'],
  owner: ['Home', 'Messages', 'Properties', 'Units', 'Overview', 'Invoices', 'Payments received', 'Owner statements', 'Deposits', 'Work orders', 'Preventive maintenance', ...FILES, 'Reports', 'Opportunities'],
  tenant: ['Home', 'Messages', 'My lease', 'Overview', 'Invoices', 'Payments received', 'Deposits', 'Maintenance', ...FILES],
  vendor: ['Home', 'Messages', 'Work orders', 'Preventive maintenance', ...FILES],
};
MENU.manager = [...MENU.admin];
MENU.harbor = MENU.admin.filter((l) => !['Broker cockpit', 'Opportunities'].includes(l)); // that company's plan has no cockpit or opportunities
MENU.broker2 = MENU.broker;
MENU.owner2 = MENU.owner;
MENU.tenant2 = MENU.tenant;

const sorted = (a: string[]) => [...a].sort();

test.describe('Navigation by role', () => {
  for (const key of Object.keys(MENU) as PersonaKey[]) {
    test(`[RBAC-08] the ${key} persona sees exactly its own menu`, async ({ pageAs }) => {
      const page = await pageAs(key, '/app');
      const got = await menuLabels(page);
      expect(sorted(got)).toEqual(sorted(MENU[key]));
    });
  }

  test('[PLAT-19] the platform sidebar names the platform and offers only operator pages', async ({ pageAs }) => {
    const page = await pageAs('platform', '/app');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Platform overview');
    await expect(page.locator('header').first()).toContainText('PropertyOS Platform');
    const labels = await menuLabels(page);
    for (const company of ['Properties', 'Invoices', 'Work orders', 'Leads', 'Users and roles', 'Audit log']) expect(labels).not.toContain(company);
    for (const op of ['Platform', 'Product defaults', 'Plans', 'Website']) expect(labels).toContain(op);
  });

  test('[RBAC-11] a direct address for a page the role cannot use says so and shows no data or buttons', async ({ pageAs }) => {
    const blocked: Array<[PersonaKey, string]> = [
      ['tenant', '/app/admin/access'], ['tenant', '/app/payments/tax'], ['tenant', '/app/leasing/owners'],
      ['vendor', '/app/leasing/properties'], ['vendor', '/app/payments'], ['vendor', '/app/payments/invoices'],
      ['agent', '/app/payments/invoices'], ['agent', '/app/admin/audit'], ['owner', '/app/admin/audit'],
      ['broker', '/app/admin/access'], ['platform', '/app/leasing/properties'],
    ];
    for (const [who, path] of blocked) {
      const page = await pageAs(who, path);
      await expect(page.getByRole('alert').getByText('You don’t have access to this page'), `${who} ${path}`).toBeVisible();
      await expect(page.locator('main tbody tr')).toHaveCount(0);
      await expect(page.locator('main').getByRole('button', { name: /Run billing cycle|Add property|Add user|New charge/ })).toHaveCount(0);
      await page.context().close();
    }
  });

  test('[RBAC-11] the API refuses what the role may not use, whatever the screen shows', async ({ apiAs }) => {
    const tenant = await apiAs('tenant');
    expect((await tenant.get('/api/identity/users')).status).toBe(403);
    expect((await tenant.get('/api/identity/roles')).status).toBe(403);
  });
});

test.describe('Every reachable page opens cleanly', () => {
  const ROUTES: Record<string, string[]> = {
    admin: ['/app', '/app/messages', '/app/broker', '/app/broker/opportunities', '/app/leasing/properties', '/app/leasing/units', '/app/leasing/owners', '/app/leasing', '/app/leasing/listings', '/app/leasing/leads', '/app/leasing/showings', '/app/leasing/applications', '/app/leasing/leases', '/app/payments', '/app/payments/invoices', '/app/payments/collections', '/app/payments/payments', '/app/payments/statements', '/app/payments/deposits', '/app/payments/tax', '/app/maintenance', '/app/maintenance/vendors', '/app/maintenance/recurring', '/app/documents', '/app/documents/photos', '/app/documents/signatures', '/app/insights', '/app/insights/ai', '/app/deadlines', '/app/preferences', '/app/profile', '/app/admin/access', '/app/admin/configuration', '/app/admin/audit', '/app/admin/automations', '/app/admin/workflows'],
    broker: ['/app', '/app/broker', '/app/leasing/properties', '/app/leasing/listings', '/app/leasing/applications', '/app/payments/invoices', '/app/maintenance', '/app/documents', '/app/insights', '/app/broker/opportunities', '/app/deadlines'],
    agent: ['/app', '/app/leasing/properties', '/app/leasing/listings', '/app/leasing/leads', '/app/leasing/showings', '/app/leasing/applications', '/app/documents', '/app/insights/ai'],
    accountant: ['/app', '/app/payments', '/app/payments/invoices', '/app/payments/collections', '/app/payments/statements', '/app/payments/deposits', '/app/payments/tax', '/app/insights', '/app/deadlines', '/app/admin/audit'],
    owner: ['/app', '/app/leasing/properties', '/app/payments/statements', '/app/maintenance', '/app/insights', '/app/broker/opportunities'],
    tenant: ['/app', '/app/leasing/my-lease', '/app/payments/invoices', '/app/maintenance', '/app/documents'],
    vendor: ['/app', '/app/maintenance', '/app/documents'],
    platform: ['/app', '/app/admin/platform', '/app/admin/defaults', '/app/admin/plans', '/app/admin/website'],
  };
  for (const [who, routes] of Object.entries(ROUTES)) {
    test(`[RBAC-08] ${who}: ${routes.length} pages open without errors`, async ({ pageAs }) => {
      test.setTimeout(240_000);
      const page = await pageAs(who as PersonaKey, '/app');
      const problems = watchErrors(page);
      for (const r of routes) {
        await page.goto(r);
        await expect(page.locator('main')).toBeVisible();
        await expect(page.locator('main'), r).not.toContainText(/something went wrong|this page doesn.t exist/i);
        await page.waitForLoadState('networkidle').catch(() => {});
      }
      expect(problems).toEqual([]);
    });
  }
});
