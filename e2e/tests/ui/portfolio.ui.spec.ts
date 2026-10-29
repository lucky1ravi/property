import { test, expect, needsWrites, pick, watchErrors } from '../../src/support/fixtures';
import { onboardCompany } from '../../src/support/company';
import { rows } from '../../src/support/api';

/** Opens a row's edit dialog by clicking its last cell (the name cell is a link that opens a side panel instead). */
const openRow = (page: import('@playwright/test').Page, name: string | RegExp) => page.getByRole('row', { name }).getByRole('cell').last().click();

test.describe('Portfolio screens: owners, properties, units', () => {
  needsWrites();

  test('[PORT-06] add, edit and archive an owner, a property and a unit through the screens', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const page = await pageWithToken(c.token, '/app/leasing/owners');
    const problems = watchErrors(page);
    const owner = `Owner ${c.sfx}`, prop = `Property ${c.sfx}`;

    await expect(page.getByRole('heading', { name: 'Owners' }).first()).toBeVisible();
    await page.getByRole('button', { name: 'Add owner' }).first().click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByRole('button', { name: 'Save' })).toBeDisabled();
    await dlg.getByLabel('Owner or company name').fill(owner);
    await dlg.getByLabel('Repair approval limit ($)').fill('750');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: owner })).toBeVisible();
    expect(Number(rows(await c.api.get('/api/leasing/owners')).find((o: any) => o.name === owner)?.approval_limit)).toBe(750);

    await openRow(page, owner);
    await page.getByRole('dialog').getByLabel('Contact person').fill('Olive Owner');
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Olive Owner').first()).toBeVisible();

    await page.goto('/app/leasing/properties');
    await page.getByRole('button', { name: 'Add property' }).first().click();
    const pd = page.getByRole('dialog');
    await pick(page, pd.getByRole('combobox').first(), owner);
    await pd.getByLabel('Property name').fill(prop);
    await pd.getByLabel('Street address').fill('12 Test Street');
    await pd.getByLabel('City').fill('Austin');
    await pd.getByLabel('ZIP code').fill('78701');
    await pd.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: new RegExp(prop) })).toBeVisible();

    await page.goto('/app/leasing/units');
    await page.getByRole('button', { name: 'Add unit' }).first().click();
    const ud = page.getByRole('dialog');
    await expect(ud.getByRole('button', { name: 'Save' })).toBeDisabled();
    await pick(page, ud.getByRole('combobox').first(), prop);
    await ud.getByLabel('Unit number').fill('7Z');
    await ud.getByLabel(/Market rent/).fill('1850');
    await ud.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: '7Z' })).toBeVisible();

    await openRow(page, '7Z');
    await page.getByRole('dialog').getByLabel(/Market rent/).fill('1900');
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('$1,900').first()).toBeVisible();

    await openRow(page, '7Z');
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByRole('cell', { name: '7Z' })).toHaveCount(0);

    await page.goto('/app/leasing/properties');
    await openRow(page, new RegExp(prop));
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByRole('cell', { name: new RegExp(prop) })).toHaveCount(0);

    await page.goto('/app/leasing/owners');
    await openRow(page, owner);
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByRole('cell', { name: owner })).toHaveCount(0);
    expect(problems).toEqual([]);
  });

  test('[PORT-06] archived owners, properties and units can be found and restored from the screens', async ({ pageWithToken }) => {
    const c = await onboardCompany();
    const sfx = c.sfx;
    const ownerId = (await c.api.post('/api/leasing/owners', { name: `Gone Owner ${sfx}`, managementFeePct: 8, approvalLimit: 500 })).body.id;
    const propId = (await c.api.post('/api/leasing/properties', { ownerId, name: `Gone Court ${sfx}`, street: '1 Old St', city: 'Austin', state: 'TX', zip: '78701', type: 'MULTIFAMILY' })).body.id;
    const unitId = (await c.api.post(`/api/leasing/properties/${propId}/units`, { number: '9Q', beds: 1, baths: '1', sqft: 600, marketRent: 1200 })).body.id;
    expect((await c.api.del(`/api/leasing/units/${unitId}`)).status).toBe(200);
    expect((await c.api.del(`/api/leasing/properties/${propId}`)).status).toBe(200);
    expect((await c.api.del(`/api/leasing/owners/${ownerId}`)).status).toBe(200);
    const page = await pageWithToken(c.token, '/app/leasing/owners');
    await page.getByRole('button', { name: 'Show archived' }).click();
    await expect(page.getByText(`Gone Owner ${sfx}`)).toBeVisible();
    await page.getByRole('button', { name: 'Restore' }).click();
    await expect(page.getByRole('cell', { name: `Gone Owner ${sfx}` })).toBeVisible();
    await page.goto('/app/leasing/properties');
    await page.getByRole('button', { name: 'Show archived' }).click();
    await page.getByRole('button', { name: 'Restore' }).click();
    await expect(page.getByRole('cell', { name: new RegExp(`Gone Court ${sfx}`) })).toBeVisible();
    // The property came back with its units, so the unit is no longer archived.
    expect(rows(await c.api.get('/api/leasing/units')).some((u: any) => u.number === '9Q')).toBe(true);
  });

  test('[PORT-06] the list screens render with tables and export', async ({ pageAs }) => {
    const page = await pageAs('admin', '/app/leasing/properties');
    await expect(page.getByRole('heading', { name: 'Properties' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Export CSV' })).toBeVisible();
    for (const p of ['/app/leasing/units', '/app/leasing/owners']) {
      await page.goto(p);
      await expect(page.locator('main table').first()).toBeVisible();
    }
  });
});
