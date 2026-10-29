import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease } from '../../src/support/builders';

test.describe('Portfolio', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  test('[PORT-01] owners can be created, edited and archived with validation', async () => {
    const c = await onboardCompany();
    expect((await c.api.post('/api/leasing/owners', { contact: 'No name' })).status).toBe(400);
    const id = (await c.api.post('/api/leasing/owners', { name: 'Birch LLC', email: 'b@e2e.test', managementFeePct: 8 })).body.id;
    expect((await c.api.put(`/api/leasing/owners/${id}`, { phone: '5125550199' })).status).toBe(200);
    const o = rows(await c.api.get('/api/leasing/owners')).find((x: any) => x.id === id);
    expect(o.phone).toBe('5125550199');
    expect(o.name).toBe('Birch LLC');
    expect((await c.api.del(`/api/leasing/owners/${id}`)).status).toBe(200);
    expect(rows(await c.api.get('/api/leasing/owners')).some((x: any) => x.id === id)).toBe(false);
    expect((await c.api.put('/api/leasing/owners/00000000-0000-0000-0000-000000000000', { phone: '1' })).status).toBe(404);
  });

  test('[PORT-02] properties can be created, edited and archived with validation', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    expect((await c.api.post('/api/leasing/properties', { ownerId: p.ownerId })).status).toBe(400);
    expect((await c.api.put(`/api/leasing/properties/${p.propertyId}`, { name: 'Renamed Court' })).status).toBe(200);
    expect((await c.api.get(`/api/leasing/properties/${p.propertyId}`)).body.name).toBe('Renamed Court');
    const spare = (await c.api.post('/api/leasing/properties', { ownerId: p.ownerId, name: 'Spare', street: '5 Elm', city: 'Austin', state: 'TX', zip: '78702' })).body.id;
    expect((await c.api.del(`/api/leasing/properties/${spare}`)).status).toBe(200);
    expect(rows(await c.api.get('/api/leasing/properties')).map((x: any) => x.id)).not.toContain(spare);
  });

  test('[PORT-03] units can be created, edited, repriced and archived with validation', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    expect((await c.api.put(`/api/leasing/units/${p.unitId}`, { marketRent: 1850, sqft: 920 })).status).toBe(200);
    expect((await c.api.put(`/api/leasing/units/${p.unitId}`, { marketRent: -1 })).status).toBe(400);
    expect((await c.api.post(`/api/leasing/properties/${p.propertyId}/units`, { number: '', marketRent: 100 })).status).toBe(400);
    expect((await c.api.put(`/api/leasing/units/${p.unit2Id}/market-rent`, { marketRent: 1500 })).status).toBe(200);
    const units = rows(await c.api.get('/api/leasing/units'));
    expect(Number(units.find((u: any) => u.id === p.unit2Id).market_rent)).toBe(1500);
    expect((await c.api.del(`/api/leasing/units/${p.unit2Id}`)).status).toBe(200);
    expect(rows(await c.api.get('/api/leasing/units')).length).toBe(1);
  });

  test('[PORT-04] archived records are hidden and restore children after parents', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const owner2 = (await c.api.post('/api/leasing/owners', { name: 'Spare Owner' })).body.id;
    const prop2 = (await c.api.post('/api/leasing/properties', { ownerId: owner2, name: 'Spare House', street: '5 Elm', city: 'Austin', state: 'TX', zip: '78702' })).body.id;
    const unit2 = (await c.api.post(`/api/leasing/properties/${prop2}/units`, { number: 'A', marketRent: 1300 })).body.id;
    expect((await c.api.del(`/api/leasing/units/${unit2}`)).status).toBe(200);
    expect((await c.api.del(`/api/leasing/properties/${prop2}`)).status).toBe(200);
    expect((await c.api.post(`/api/leasing/properties/${prop2}/units`, { number: 'C', marketRent: 1300 })).status).toBe(409);
    expect((await c.api.del(`/api/leasing/owners/${owner2}`)).status).toBe(200);
    expect((await c.api.post('/api/leasing/properties', { ownerId: owner2, name: 'X', street: '1', city: 'A', state: 'TX', zip: '78701' })).status).toBe(409);
    expect((await c.api.post(`/api/leasing/properties/${prop2}/restore`, {})).status).toBe(409);
    expect((await c.api.post(`/api/leasing/owners/${owner2}/restore`, {})).status).toBe(200);
    expect((await c.api.post(`/api/leasing/properties/${prop2}/restore`, {})).status).toBe(200);
    expect((await c.api.post(`/api/leasing/units/${unit2}/restore`, {})).status).toBe(200);
    expect(rows(await c.api.get('/api/leasing/owners')).length).toBe(2);
    expect(rows(await c.api.get('/api/leasing/properties')).length).toBe(2);
    expect(rows(await c.api.get('/api/leasing/units')).length).toBe(3);
    expect(p.ownerId).toBeTruthy();
  });

  test('[PORT-05] records in use cannot be archived', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    expect((await c.api.del(`/api/leasing/owners/${p.ownerId}`)).status).toBe(409);
    const draft = (await c.api.post('/api/leasing/listings', { unitId: p.unit2Id })).body.id;
    expect((await c.api.del(`/api/leasing/units/${p.unit2Id}`)).status).toBe(409);
    expect((await c.api.del(`/api/leasing/listings/${draft}`)).status).toBe(200);
    const listing = await publishListing(c.api, p.unitId);
    expect((await c.api.del(`/api/leasing/listings/${listing}`)).status).toBe(409);
    await activeLease(c, p, listing, `occ.${p.sfx}@e2e.test`);
    expect((await c.api.del(`/api/leasing/units/${p.unitId}`)).status).toBe(409);
    expect((await c.api.del(`/api/leasing/properties/${p.propertyId}`)).status).toBe(409);
    expect((await c.api.del(`/api/leasing/owners/${p.ownerId}`)).status).toBe(409);
    expect((await c.api.del('/api/leasing/units/00000000-0000-0000-0000-000000000000')).status).toBe(404);
  });

  test('[PORT-07] property and unit details can be read, and only by people who may see them', async () => {
    const mgr = await apiAs('manager');
    const props = rows(await mgr.get('/api/leasing/properties'));
    expect(props.length).toBeGreaterThan(0);
    const one = (await mgr.get(`/api/leasing/properties/${props[0].id}`)).body;
    expect(one.id).toBe(props[0].id);
    expect(rows(await mgr.get('/api/leasing/units')).length).toBeGreaterThan(0);
    expect((await (await apiAs('harbor')).get(`/api/leasing/properties/${props[0].id}`)).status).toBe(404);
    expect((await (await Api.create()).get('/api/leasing/properties')).status).toBe(401);
  });
});
