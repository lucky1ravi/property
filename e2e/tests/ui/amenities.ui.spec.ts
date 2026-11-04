import { test, expect, needsWrites, watchErrors } from '../../src/support/fixtures';
import { onboardCompany } from '../../src/support/company';
import { buildPortfolio, publishListing } from '../../src/support/builders';
import { apiAs, rows } from '../../src/support/api';
import { SUFFIX } from '../../src/support/env';

test.describe('Amenities and the public homes search', () => {
  needsWrites();
  test.setTimeout(240_000);

  test('[AMEN-01] a company adds its own amenity with an icon, picks it on a property, and it shows with that icon on the listing', async ({ pageWithToken, page: visitor }) => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const page = await pageWithToken(c.token, '/app/leasing/amenities');
    const problems = watchErrors(page);
    await expect(page.getByRole('heading', { name: 'Amenities' }).first()).toBeVisible();
    // The platform's amenities are listed for reference and cannot be changed here.
    await expect(page.getByRole('region', { name: 'Offered by PropertyOS' })).toContainText('Covered parking');

    const mine = `Dog wash ${c.sfx}`;
    await page.getByRole('button', { name: 'Add an amenity' }).click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByRole('button', { name: 'Save' })).toBeDisabled();
    await dlg.getByLabel('Name').fill(mine);
    await dlg.getByLabel('Search icons').fill('paw');
    await dlg.getByRole('option', { name: 'paw print' }).click();
    await expect(dlg.getByRole('option', { name: 'paw print' })).toHaveAttribute('aria-selected', 'true');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: mine, exact: true })).toBeVisible();
    expect(rows(await c.api.get('/api/leasing/amenities/mine')).find((a: any) => a.name === mine)?.icon).toBe('PawPrint');

    // A repeated name is refused with a reason.
    await page.getByRole('button', { name: 'Add an amenity' }).click();
    await page.getByRole('dialog').getByLabel('Name').fill('Covered parking');
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(/already an amenity called/i).first()).toBeVisible();
    await page.keyboard.press('Escape');

    // The property form offers a search over the catalogue, with icons, and the new amenity is in it.
    await page.goto('/app/leasing/properties');
    await page.getByRole('cell', { name: new RegExp(`Court ${p.sfx}`) }).locator('xpath=..').getByRole('cell').last().click();
    const edit = page.getByRole('dialog');
    const picker = edit.getByPlaceholder(/Search amenities/);
    await picker.fill('dog');
    await expect(page.getByRole('option', { name: new RegExp(mine) })).toBeVisible();
    await page.getByRole('option', { name: new RegExp(mine) }).click();
    await picker.fill('covered');
    await page.getByRole('option', { name: /Covered parking/ }).click();
    await expect(edit.getByRole('list', { name: 'Chosen' })).toContainText(mine);
    await edit.getByRole('button', { name: 'Save' }).click();
    await expect(edit).toBeHidden();
    const saved = (await c.api.get(`/api/leasing/properties/${p.propertyId}`)).body;
    expect(saved.amenities).toEqual(expect.arrayContaining([mine, 'Covered parking']));

    // Visitors see each amenity with its icon, and can filter by it.
    const listing = await publishListing(c.api, p.unitId);
    await visitor.goto(`/homes/${listing}`);
    await visitor.getByRole('button', { name: /^Amenities/ }).click();   // the amenities are a dropdown
    const list = visitor.getByTestId('amenities');
    await expect(list.getByText(mine)).toBeVisible();
    expect(await list.locator('li svg').count()).toBe(2);
    await visitor.goto(`/homes?amenities=${encodeURIComponent(mine)}`);
    await expect(visitor.getByTestId('property-card')).toHaveCount(1);
    expect(problems).toEqual([]);
  });

  test('[AMEN-02] a resident or vendor cannot add amenities, and the platform administrator keeps the shared list', async ({ pageWithToken }) => {
    const sfx = SUFFIX();
    const platform = await apiAs('platform');
    const name = `Cold plunge ${sfx}`;
    const page = await pageWithToken(platform.token!, '/app/admin/amenities');
    await expect(page.getByRole('heading', { name: 'Amenities' }).first()).toBeVisible();
    await page.getByRole('button', { name: 'Add an amenity' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.getByLabel('Name').fill(name);
    await dlg.getByLabel('Search icons').fill('snow');
    await dlg.getByRole('option', { name: 'snowflake' }).click();
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name, exact: true })).toBeVisible();
    // Companies are offered it straight away.
    const admin = await apiAs('admin');
    expect(rows(await admin.get('/api/leasing/amenities')).some((a: any) => a.name === name)).toBe(true);
    // Switch it off, then remove it.
    await page.getByRole('switch', { name: `Offer ${name} on property forms` }).click();
    await expect.poll(async () => rows(await admin.get('/api/leasing/amenities')).some((a: any) => a.name === name)).toBe(false);
    await page.getByRole('row', { name: new RegExp(name) }).getByRole('button', { name: new RegExp(`Delete ${name}`) }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click();
    await expect(page.getByRole('cell', { name, exact: true })).toHaveCount(0);

    const tenant = await apiAs('tenant');
    expect((await tenant.post('/api/leasing/amenities', { name: `Resident idea ${sfx}`, icon: 'Dog' })).status).toBe(403);
  });

  test('[AMEN-03] the homes search lists properties with their units, pages the results, switches to one card per home, and names the company', async ({ page }) => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    await c.api.put('/api/identity/config/branding', { companyName: c.name, primaryColor: '#0F3D3E', accentColor: '#C8963E', logoDataUrl: '', portalDomain: '', emailSender: '', contactPhone: '(512) 555-0142', contactEmail: `hello.${c.sfx}@e2e.test`, website: 'https://example.com' });
    const first = await publishListing(c.api, p.unitId);
    await publishListing(c.api, p.unit2Id);
    const problems = watchErrors(page);

    await page.goto(`/homes?keyword=${encodeURIComponent(p.sfx)}`);
    const card = page.getByTestId('property-card');
    await expect(card).toHaveCount(1);
    await expect(card).toContainText(`Court ${p.sfx}`);
    await expect(card).toContainText(`Listed by ${c.name}`);
    await expect(card).toContainText('2 homes available');
    await expect(card.getByRole('list', { name: /Homes available at/ }).getByRole('link')).toHaveCount(2);
    await expect(page.getByTestId('result-count')).toContainText('1 property');
    // Photos flip in a carousel when there is more than one picture.
    await expect(card.locator('img, svg').first()).toBeVisible();

    // The same search, one card per home.
    await page.getByRole('button', { name: 'All homes' }).click();
    await expect(page).toHaveURL(/view=homes/);
    await expect(page.getByTestId('home-card')).toHaveCount(2);
    await page.getByLabel('Sort results').click();
    await page.getByRole('option', { name: 'Rent: low to high' }).click();
    await expect(page.getByTestId('home-card').first()).toContainText('$1,');

    // More filters narrow it and show as removable chips; the address keeps them.
    await page.getByRole('button', { name: /More filters/ }).click();
    await page.getByLabel('Smallest (sq ft)').fill('800');
    await expect(page.getByTestId('home-card')).toHaveCount(1);
    await expect(page).toHaveURL(/minSqft=800/);
    await page.getByRole('button', { name: /Remove filter 800\+ sq ft/ }).click();
    await expect(page.getByTestId('home-card')).toHaveCount(2);
    await page.reload();
    await expect(page.getByTestId('home-card')).toHaveCount(2);

    // The detail page shows who is renting it, the contact details, and the other home in the building.
    await page.goto(`/homes/${first}`);
    const by = page.getByTestId('listed-by');
    await expect(by).toContainText(c.name);
    await expect(by.getByRole('link', { name: '(512) 555-0142' })).toHaveAttribute('href', 'tel:5125550142');
    await expect(by.getByRole('link', { name: `hello.${c.sfx}@e2e.test` })).toHaveAttribute('href', `mailto:hello.${c.sfx}@e2e.test`);
    await expect(by.getByRole('link', { name: 'example.com' })).toHaveAttribute('target', '_blank');
    await expect(page.getByTestId('other-homes').getByRole('link')).toHaveCount(1);
    expect(problems).toEqual([]);
  });

  test('[AMEN-04] a long result list is paged, and a page past the end goes to the last one', async ({ page, publicApi }) => {
    const total = (await (await publicApi()).get('/api/leasing/public/facets')).body.homes;
    await page.goto('/homes?view=homes');
    await expect(page.getByTestId('result-count')).toContainText(/Showing 1–\d+ of/);
    const count = await page.getByTestId('home-card').count();
    expect(count).toBeLessThanOrEqual(12);
    if (total > 12) {
      await expect(page.getByRole('navigation', { name: 'Pages' })).toBeVisible();
      await page.getByRole('button', { name: 'Next' }).click();
      await expect(page).toHaveURL(/page=2/);
      await expect(page.getByTestId('result-count')).toContainText(/Showing 13–/);
      await page.getByRole('button', { name: 'Previous' }).click();
      await expect(page).not.toHaveURL(/page=/);
    } else {
      await expect(page.getByRole('navigation', { name: 'Pages' })).toHaveCount(0);
    }
    await page.goto('/homes?view=homes&page=9999');
    await expect(page.getByTestId('home-card').first()).toBeVisible();
    await expect(page).toHaveURL(/page=\d+/);
  });
});
