import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, until } from '../../src/support/builders';

/**
 * Paid listing boosts. A company pays to lift a live listing in public search for a set time. The boost is an invoice on the company's PropertyOS
 * subscription, starts when it is paid, is labelled Sponsored, and the operator can set prices, make promo codes, give free boosts and end a boost.
 */
test.describe('Listing boosts', () => {
  needsWrites();
  test.setTimeout(180_000);

  async function setup() {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    return { c, p, listing, platform: await apiAs('platform'), pub: await Api.create() };
  }

  test('[BOOST-01] a boost is bought, paid, starts, shows as Sponsored, and the operator can end it', async () => {
    const { c, p, listing, platform, pub } = await setup();
    const options = (await c.api.get('/api/leasing/boosts/options')).body;
    expect(JSON.stringify(options)).toContain('7');
    expect((await c.api.get('/api/leasing/boosts/quote?days=7')).status).toBe(200);
    expect((await c.api.post(`/api/leasing/listings/${listing}/boosts`, { days: 5 })).status, 'a period that is not on offer').toBe(400);
    const resident = await createSignedInUser(c.api, { email: `r.${p.sfx}@e2e.test`, name: 'Rae Resident', roleKey: 'TENANT' });
    expect((await resident.api.post(`/api/leasing/listings/${listing}/boosts`, { days: 7 })).status, 'residents cannot buy').toBe(403);

    const bought = await c.api.post(`/api/leasing/listings/${listing}/boosts`, { days: 7 });
    expect(bought.status).toBe(200);
    const invoice = bought.body.invoice_id as string;
    expect(invoice).toBeTruthy();
    expect((await c.api.post(`/api/leasing/listings/${listing}/boosts`, { days: 14 })).status, 'one boost at a time for a listing').toBe(409);
    expect((await c.api.get(`/api/leasing/listings/${listing}/boosts`)).body[0].status).toBe('PENDING');
    expect((await pub.get(`/api/leasing/public/listings/${listing}`)).body.sponsored, 'not sponsored until it is paid').toBeFalsy();

    expect((await c.api.post(`/api/identity/billing/invoices/${invoice}/pay`, { method: 'CARD', last4: '0002' })).status, 'a declined card').toBeGreaterThanOrEqual(400);
    expect((await c.api.post(`/api/identity/billing/invoices/${invoice}/pay`, { method: 'CARD', last4: '4242' })).status).toBe(200);
    const live = await until(() => c.api.get(`/api/leasing/listings/${listing}/boosts`), (r) => r.body[0]?.status === 'ACTIVE');
    expect(live.body[0].status).toBe('ACTIVE');
    expect((await pub.get(`/api/leasing/public/listings/${listing}`)).body.sponsored, 'the listing is labelled Sponsored').toBe(true);
    expect((await c.api.get(`/api/leasing/listings/${listing}/ranking`)).status).toBe(200);
    expect(rows(await c.api.get('/api/leasing/listings/ranking')).length).toBeGreaterThanOrEqual(1);

    // The operator sees it and ends it; the company is told and the label goes away.
    const mine = rows(await platform.get('/api/leasing/platform/boosts')).find((b: any) => b.listing_id === listing);
    expect(mine, 'the operator lists it').toBeTruthy();
    expect((await platform.get('/api/leasing/platform/boosts/overview')).status).toBe(200);
    expect((await c.api.get('/api/leasing/platform/boosts/overview')).status, 'companies cannot see the operator view').toBe(403);
    expect((await platform.post(`/api/leasing/platform/boosts/${mine.id}/cancel`, { reason: 'end of e2e test' })).status).toBe(200);
    expect((await pub.get(`/api/leasing/public/listings/${listing}`)).body.sponsored).toBeFalsy();
  });

  test('[BOOST-02] the operator can agree a price with one company, make a promo code and give a free boost', async () => {
    const { c, p, listing, platform } = await setup();
    const code = `E2E${p.sfx}`.toUpperCase();
    expect((await c.api.put('/api/leasing/platform/boosts/prices', { orgId: c.orgId, days: 14, price: '10' })).status, 'companies cannot set prices').toBe(403);
    expect((await platform.put('/api/leasing/platform/boosts/prices', { orgId: c.orgId, days: 14, price: '75.00', note: 'agreed on a call' })).status).toBe(200);
    expect(JSON.stringify((await platform.get('/api/leasing/platform/boosts/prices')).body)).toContain('75');
    expect(JSON.stringify((await c.api.get('/api/leasing/boosts/quote?days=14')).body), 'the company sees its agreed price').toContain('75');
    expect((await platform.post('/api/leasing/platform/boosts/promos', { code, percentOff: 20, maxUses: 3 })).status).toBe(200);
    expect((await platform.post('/api/leasing/platform/boosts/promos', { code: `X${code}`, percentOff: 20, amountOff: 5 })).status, 'one kind of discount').toBe(400);
    expect((await c.api.get(`/api/leasing/boosts/quote?days=14&code=${code}`)).status).toBe(200);
    expect((await platform.put(`/api/leasing/platform/boosts/promos/${code}`, { active: false })).status).toBe(200);
    expect((await platform.del(`/api/leasing/platform/boosts/prices?orgId=${c.orgId}&days=14`)).status).toBe(200);

    expect((await platform.post('/api/leasing/platform/boosts/grant', { listingId: listing, days: 0, reason: 'x' })).status, 'days from 1 to 90').toBe(400);
    const gift = await platform.post('/api/leasing/platform/boosts/grant', { listingId: listing, days: 3, reason: 'launch gift' });
    expect(gift.status).toBe(200);
    const granted = await until(() => c.api.get(`/api/leasing/listings/${listing}/boosts`), (r) => r.body[0]?.status === 'ACTIVE');
    expect(granted.body[0].status).toBe('ACTIVE');
  });
});
