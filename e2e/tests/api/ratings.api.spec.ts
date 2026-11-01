import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { createSignedInUser } from '../../src/support/company';
import { occupiedHome, vendorFor } from '../../src/support/scenarios';
import { until } from '../../src/support/builders';

/**
 * Ratings. A resident rates the property and the unit they live in. Staff, owners and residents rate vendors: staff any vendor, an
 * owner a vendor who worked on their property, a resident the vendor who did their own repair once it is done. Each person has one
 * rating per job, can change it, and reads only what their role allows.
 */
test.describe('Ratings', () => {
  needsWrites();
  test.setTimeout(180_000);

  test('[RATE-01] a resident rates their property and unit, can change it, and nobody else can rate for them', async () => {
    const { c, p, resident } = await occupiedHome();
    const mine = (await resident.api.get('/api/leasing/reviews/mine')).body;
    expect(mine.property.id).toBe(p.propertyId);
    expect(mine.unit.id).toBe(p.unitId);
    expect(mine.property.review ?? null).toBeNull();

    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: p.propertyId, stars: 0 })).status).toBe(400);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: p.propertyId, stars: 6 })).status).toBe(400);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'GARDEN', subjectId: p.propertyId, stars: 3 })).status).toBe(400);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: p.propertyId, stars: 3, comment: 'x'.repeat(1001) })).status).toBe(400);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'UNIT', subjectId: p.unit2Id, stars: 5 })).status, 'a unit they do not live in').toBe(403);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: p.unitId, stars: 5 })).status, 'a unit id is not a property').toBe(403);

    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: p.propertyId, stars: 4, comment: 'Quiet and clean.' })).status).toBe(200);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'UNIT', subjectId: p.unitId, stars: 2, comment: 'The dishwasher keeps breaking.' })).status).toBe(200);
    expect((await resident.api.put('/api/leasing/reviews', { subjectType: 'UNIT', subjectId: p.unitId, stars: 3, comment: 'Dishwasher fixed, thank you.' })).status, 'changing your mind').toBe(200);
    const after = (await resident.api.get('/api/leasing/reviews/mine')).body;
    expect(after.property.review.stars).toBe(4);
    expect(after.unit.review.stars).toBe(3);
    expect(after.unit.review.comment).toMatch(/fixed/);

    // Staff read them, as numbers on the lists and as words in the drawer.
    const list = rows(await c.api.get('/api/leasing/properties?page=1&size=20')).find((x: any) => x.id === p.propertyId);
    expect(Number(list.rating_avg)).toBe(4);
    expect(Number(list.rating_count)).toBe(1);
    const units = rows(await c.api.get('/api/leasing/units?page=1&size=50')).find((x: any) => x.id === p.unitId);
    expect(Number(units.rating_avg)).toBe(3);
    const detail = (await c.api.get(`/api/leasing/reviews/PROPERTY/${p.propertyId}`)).body;
    expect(detail.count).toBe(1);
    expect(Number(detail.average)).toBe(4);
    expect(detail.distribution['4']).toBe(1);
    expect(detail.rows[0].rater_name).toBe('Rae Resident');
    expect(detail.rows[0].comment).toBe('Quiet and clean.');

    // Staff do not rate the home they run; a manager's own attempt is refused.
    expect((await c.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: p.propertyId, stars: 5 })).status).toBe(403);
    expect((await c.api.get('/api/leasing/reviews/mine')).status).toBe(403);
    // Taking it back removes it from the averages.
    expect((await resident.api.del(`/api/leasing/reviews/UNIT/${p.unitId}`)).status).toBe(200);
    expect(Number((await c.api.get(`/api/leasing/reviews/UNIT/${p.unitId}`)).body.count)).toBe(0);
  });

  test('[RATE-02] an owner reads the ratings of their own properties only, and a resident reads only their own rating', async () => {
    const a = await occupiedHome();
    const b = await occupiedHome();
    await a.resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: a.p.propertyId, stars: 5, comment: 'Best landlord.' });
    await b.resident.api.put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: b.p.propertyId, stars: 1, comment: 'Awful.' });
    const owner = await createSignedInUser(a.c.api, { email: `olivia.${a.p.sfx}@e2e.test`, name: 'Olivia Owner', roleKey: 'OWNER', partyType: 'OWNER', partyId: a.p.ownerId });
    const seen = (await owner.api.get(`/api/leasing/reviews/PROPERTY/${a.p.propertyId}`)).body;
    expect(seen.count).toBe(1);
    expect(seen.rows[0].comment).toBe('Best landlord.');
    expect((await a.c.api.get(`/api/leasing/reviews/PROPERTY/${b.p.propertyId}`)).body.count, 'another company\'s ratings').toBe(0);
    // A resident reads only what they wrote: the other company's resident sees nothing of company A's ratings.
    expect((await b.resident.api.get(`/api/leasing/reviews/PROPERTY/${a.p.propertyId}`)).body.count).toBe(0);
    expect((await (await Api.create()).get(`/api/leasing/reviews/PROPERTY/${a.p.propertyId}`)).status).toBe(401);
    expect((await (await apiAs('vendor')).put('/api/leasing/reviews', { subjectType: 'PROPERTY', subjectId: a.p.propertyId, stars: 1 })).status).toBe(403);
  });

  test('[RATE-03] vendors: staff can rate any vendor, an owner only one who worked on their property, a resident only the one who did their finished repair', async () => {
    const { c, p, resident } = await occupiedHome();
    const owner = await createSignedInUser(c.api, { email: `olivia.${p.sfx}@e2e.test`, name: 'Olivia Owner', roleKey: 'OWNER', partyType: 'OWNER', partyId: p.ownerId });
    const vendor = await vendorFor(c, p);
    const other = await vendorFor(c, p, 'Otto');
    const url = (id: string) => `/api/maintenance/vendors/${id}/reviews`;

    expect((await c.api.post(url(vendor.id), { stars: 0 })).status).toBe(400);
    expect((await c.api.post(url(vendor.id), { stars: 5, comment: 'x'.repeat(1001) })).status).toBe(400);
    expect((await c.api.post(url(vendor.id), { stars: 4, comment: 'Reliable.' })).status).toBe(200);
    expect((await c.api.post(url(vendor.id), { stars: 5, comment: 'Even better the second time.' })).status, 'the same person changing it').toBe(200);
    expect(Number((await c.api.get(url(vendor.id))).body.count)).toBe(1);
    expect((await vendor.api.post(url(vendor.id), { stars: 5 })).status, 'vendors do not rate themselves').toBe(403);
    expect((await (await apiAs('platform')).post(url(vendor.id), { stars: 5 })).status).toBe(403);

    // A repair done by this vendor.
    const wo = (await resident.api.post('/api/maintenance/work-orders', { title: 'Hall light flickers', description: 'It flickers all evening.', permissionToEnter: true })).body.id;
    await until(() => c.api.get(`/api/maintenance/work-orders/${wo}`), (r) => r.body.status !== 'SUBMITTED');
    if ((await c.api.get(`/api/maintenance/work-orders/${wo}`)).body.status === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${wo}/actions/APPROVE`, {});
    if ((await c.api.get(`/api/maintenance/work-orders/${wo}`)).body.status !== 'ASSIGNED') await c.api.post(`/api/maintenance/work-orders/${wo}/actions/ASSIGN`, { vendorId: vendor.id });
    await c.api.post(`/api/maintenance/work-orders/${wo}/actions/ASSIGN`, { vendorId: vendor.id }).catch(() => undefined);

    expect((await resident.api.post(url(vendor.id), { stars: 5 })).status, 'a resident must say which repair').toBe(400);
    expect((await resident.api.post(url(vendor.id), { stars: 5, workOrderId: wo })).status, 'not finished yet').toBe(409);
    expect((await owner.api.post(url(other.id), { stars: 1 })).status, 'a vendor who never worked on their property').toBe(403);

    await vendor.api.post(`/api/maintenance/work-orders/${wo}/actions/START`, {});
    expect((await vendor.api.post(`/api/maintenance/work-orders/${wo}/actions/COMPLETE`, { cost: 60, notes: 'New bulb and ballast.' })).status).toBe(200);
    expect((await resident.api.post(url(other.id), { stars: 5, workOrderId: wo })).status, 'not their vendor').toBe(400);
    expect((await resident.api.post(url(vendor.id), { stars: 3, comment: 'Came late, work was fine.', workOrderId: wo })).status).toBe(200);
    expect((await owner.api.post(url(vendor.id), { stars: 4, comment: 'Fair price.' })).status, 'worked on their property').toBe(200);

    // Staff see all three; the resident sees only theirs; the vendor sees what is said about them; the owner only theirs.
    const staff = (await c.api.get(url(vendor.id))).body;
    expect(staff.count).toBe(3);
    expect(staff.rows.map((r: any) => r.rater_role).sort()).toEqual(['OWNER', 'RESIDENT', 'STAFF']);
    expect(Number(staff.average)).toBeCloseTo((5 + 3 + 4) / 3, 1);
    const mineOnly = (await resident.api.get(url(vendor.id))).body;
    expect(mineOnly.rows).toHaveLength(1);
    expect(mineOnly.rows[0].rater_role).toBe('RESIDENT');
    expect((await owner.api.get(url(vendor.id))).body.rows).toHaveLength(1);
    expect((await vendor.api.get(url(vendor.id))).body.count, 'the vendor reads all of it').toBe(3);
    // The vendor list shows the combined rating and how many ratings it rests on.
    const listed = rows(await c.api.get('/api/maintenance/vendors?page=1&size=50')).find((v: any) => v.id === vendor.id);
    expect(Number(listed.rating_overall)).toBeCloseTo((5 + 3 + 4) / 3, 1);
    expect(Number(listed.review_count)).toBe(3);
  });

  test('[RATE-04] closing a repair with a rating also files it with the vendor\'s other ratings', async () => {
    const { c, p, resident } = await occupiedHome();
    const vendor = await vendorFor(c, p);
    const wo = (await resident.api.post('/api/maintenance/work-orders', { title: 'Garbage disposal jams', description: 'It hums and stops.', permissionToEnter: true })).body.id;
    await until(() => c.api.get(`/api/maintenance/work-orders/${wo}`), (r) => r.body.status !== 'SUBMITTED');
    if ((await c.api.get(`/api/maintenance/work-orders/${wo}`)).body.status === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${wo}/actions/APPROVE`, {});
    await c.api.post(`/api/maintenance/work-orders/${wo}/actions/ASSIGN`, { vendorId: vendor.id }).catch(() => undefined);
    await vendor.api.post(`/api/maintenance/work-orders/${wo}/actions/START`, {});
    await vendor.api.post(`/api/maintenance/work-orders/${wo}/actions/COMPLETE`, { cost: 85, notes: 'Cleared the jam.' });
    expect((await c.api.post(`/api/maintenance/work-orders/${wo}/actions/VERIFY`, { rating: 5, comment: 'Quick fix.' })).status).toBe(200);
    const reviews = (await c.api.get(`/api/maintenance/vendors/${vendor.id}/reviews`)).body;
    expect(reviews.count).toBe(1);
    expect(reviews.rows[0].comment).toBe('Quick fix.');
    expect(reviews.rows[0].rater_role).toBe('STAFF');
    // What staff wrote is not shown to the resident whose repair it was.
    expect((await resident.api.get(`/api/maintenance/vendors/${vendor.id}/reviews`)).body.rows).toHaveLength(0);
  });
});
