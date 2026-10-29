import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany, createSignedInUser, Company } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease, until, Portfolio } from '../../src/support/builders';

const future = (d = 2) => new Date(Date.now() + d * 86400000).toISOString();

async function occupied() {
  const c = await onboardCompany();
  const p = await buildPortfolio(c);
  const live = await publishListing(c.api, p.unitId);
  const email = `m.${p.sfx}@e2e.test`;
  const l = await activeLease(c, p, live, email);
  return { c, p, email, tenantId: l.tenantId };
}
async function vendor(c: Company, name = 'Austin Plumbing') {
  const r = await c.api.post('/api/maintenance/vendors', { name, contact: 'Pete', phone: '5125550123', email: `pete.${c.sfx}@e2e.test`, trades: ['PLUMBING', 'GENERAL'], cities: ['Austin'] });
  return r.body.id as string;
}
async function newJob(c: Company, p: Portfolio) {
  const r = await c.api.post('/api/maintenance/work-orders', { propertyId: p.propertyId, unitId: p.unitId, title: 'Kitchen sink leaking', description: 'Water under the sink and the cabinet floor is wet.', permissionToEnter: true });
  return r.body.id as string;
}
const job = (c: Company, id: string) => c.api.get(`/api/maintenance/work-orders/${id}`);

test.describe('Maintenance', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  test('[MAIN-01] a repair request can be created by staff and by residents', async () => {
    const { c, p, email, tenantId } = await occupied();
    expect((await c.api.post('/api/maintenance/work-orders', { propertyId: p.propertyId })).status).toBe(400);
    expect((await c.api.post('/api/maintenance/work-orders', { propertyId: '00000000-0000-0000-0000-000000000000', title: 'x' })).status).toBe(404);
    const staff = await newJob(c, p);
    expect((await job(c, staff)).body.title).toBe('Kitchen sink leaking');
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const t = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    const mine = await t.api.post('/api/maintenance/work-orders', { title: 'Heater not working', description: 'No heat since last night.' });
    expect(mine.status).toBe(200);
    const seen = (await t.api.get(`/api/maintenance/work-orders/${mine.body.id}`)).body;
    expect(seen.tenant_id).toBe(tenantId);
    expect(rows(await t.api.get('/api/maintenance/work-orders')).every((w: any) => w.tenant_id === tenantId)).toBe(true);
    const triaged = await until(() => job(c, mine.body.id), (r) => r.body.status !== 'SUBMITTED');
    expect(triaged.body.status).not.toBe('SUBMITTED');
    expect(triaged.body.priority ?? triaged.body.category).toBeTruthy();
  });

  test('[MAIN-02] a work order moves through assign, schedule, work, complete and verify', async () => {
    const { c, p } = await occupied();
    const vendorId = await vendor(c);
    const id = await newJob(c, p);
    await until(() => job(c, id), (r) => r.body.status !== 'SUBMITTED');
    let w = (await job(c, id)).body;
    if (w.status === 'AWAITING_APPROVAL') { await c.api.post(`/api/maintenance/work-orders/${id}/actions/APPROVE`, { amount: 300 }); w = (await job(c, id)).body; }
    if (w.status === 'TRIAGED') { expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/ASSIGN`, { vendorId })).status).toBe(200); }
    expect((await job(c, id)).body.status).toBe('ASSIGNED');
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/SCHEDULE`, { scheduledFor: new Date(Date.now() - 86400000).toISOString() })).status).toBe(400);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/VERIFY`, {})).status).toBe(409);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/SCHEDULE`, { scheduledFor: future() })).status).toBe(200);
    expect((await job(c, id)).body.status).toBe('SCHEDULED');
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/START`, {})).status).toBe(200);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/COMPLETE`, {})).status).toBe(400);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/COMPLETE`, { cost: 240, notes: 'Replaced trap' })).status).toBe(200);
    expect(Number((await job(c, id)).body.final_cost)).toBe(240);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/VERIFY`, { rating: 9 })).status).toBe(400);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/actions/VERIFY`, { rating: 5, comment: 'Great' })).status).toBe(200);
    const done = (await job(c, id)).body;
    expect(done.status).toBe('CLOSED');
    expect(Number(done.rating)).toBe(5);
    expect(rows(await c.api.get('/api/maintenance/vendors')).find((v: any) => v.id === vendorId).jobs_completed).toBe(1);
  });

  test('[MAIN-03] comments can be added to a work order, internal ones hidden from residents', async () => {
    const { c, p, email } = await occupied();
    const id = await newJob(c, p);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/comments`, { body: '   ' })).status).toBe(400);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/comments`, { body: 'Tenant has a dog.', internal: true })).status).toBe(200);
    expect((await c.api.post(`/api/maintenance/work-orders/${id}/comments`, { body: 'We will visit Tuesday.' })).status).toBe(200);
    const staffView = JSON.stringify((await job(c, id)).body);
    expect(staffView).toContain('Tenant has a dog.');
    expect(staffView).toContain('We will visit Tuesday.');
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const t = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    const own = await c.api.post('/api/maintenance/work-orders', { tenantId: res.id, title: 'Door sticks' });
    await c.api.post(`/api/maintenance/work-orders/${own.body.id}/comments`, { body: 'Private note', internal: true });
    await c.api.post(`/api/maintenance/work-orders/${own.body.id}/comments`, { body: 'Public note' });
    const tenantView = JSON.stringify((await t.api.get(`/api/maintenance/work-orders/${own.body.id}`)).body);
    expect(tenantView).toContain('Public note');
    expect(tenantView).not.toContain('Private note');
  });

  test('[MAIN-04] vendors can be created, edited, archived and reactivated', async () => {
    const { c, p } = await occupied();
    expect((await c.api.post('/api/maintenance/vendors', { name: 'No trades' })).status).toBe(400);
    expect((await c.api.post('/api/maintenance/vendors', { trades: ['GENERAL'] })).status).toBe(400);
    expect((await c.api.post('/api/maintenance/vendors', { name: 'Bad email', trades: ['GENERAL'], email: 'nope' })).status).toBe(400);
    const id = await vendor(c);
    expect((await c.api.put(`/api/maintenance/vendors/${id}`, { phone: '5125550124' })).status).toBe(200);
    expect((await c.api.put(`/api/maintenance/vendors/${id}`, { email: 'bad' })).status).toBe(400);
    expect(rows(await c.api.get('/api/maintenance/vendors')).find((v: any) => v.id === id).phone).toBe('5125550124');
    const job1 = await newJob(c, p);
    await until(() => job(c, job1), (r) => r.body.status !== 'SUBMITTED');
    const s = (await job(c, job1)).body.status;
    if (s === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${job1}/actions/APPROVE`, { amount: 300 });
    if ((await job(c, job1)).body.status === 'TRIAGED') await c.api.post(`/api/maintenance/work-orders/${job1}/actions/ASSIGN`, { vendorId: id });
    const w = (await job(c, job1)).body;
    if (w.vendor_id === id) expect((await c.api.put(`/api/maintenance/vendors/${id}`, { active: false })).status).toBe(409);
    const spare = await vendor(c, 'Spare vendor');
    expect((await c.api.put(`/api/maintenance/vendors/${spare}`, { active: false })).status).toBe(200);
    expect(rows(await c.api.get('/api/maintenance/vendors')).find((v: any) => v.id === spare).active).toBe(false);
    expect((await c.api.put(`/api/maintenance/vendors/${spare}`, { active: true })).status).toBe(200);
    expect(rows(await c.api.get('/api/maintenance/vendors')).find((v: any) => v.id === spare).active).toBe(true);
  });

  test('[MAIN-05] recurring maintenance tasks can be added and run', async () => {
    const { c, p } = await occupied();
    expect((await c.api.post('/api/maintenance/recurring', { propertyId: p.propertyId })).status).toBe(400);
    const nextDue = new Date().toISOString().slice(0, 10);
    expect((await c.api.post('/api/maintenance/recurring', { propertyId: p.propertyId, title: 'Quarterly filter change', category: 'HVAC', everyMonths: 3, nextDue })).status).toBe(200);
    expect(rows(await c.api.get('/api/maintenance/recurring')).length).toBe(1);
    const before = rows(await c.api.get('/api/maintenance/work-orders')).length;
    const run = await c.api.post('/api/maintenance/recurring/run', {});
    expect(run.status).toBe(200);
    expect(rows(await c.api.get('/api/maintenance/work-orders')).length).toBeGreaterThan(before);
    const after = await c.api.post('/api/maintenance/recurring/run', {});
    expect(rows(await c.api.get('/api/maintenance/work-orders')).length).toBe(before + 1);
    expect(after.status).toBe(200);
    expect(new Date(rows(await c.api.get('/api/maintenance/recurring'))[0].next_due).getTime()).toBeGreaterThan(Date.now());
  });

  test('[MAIN-06] the SLA check and stats work', async () => {
    const { c, p } = await occupied();
    await newJob(c, p);
    const sla = await c.api.post('/api/maintenance/sla/check', {});
    expect(sla.status).toBe(200);
    expect(typeof sla.body.escalated).toBe('number');
    const stats = (await c.api.get('/api/maintenance/stats')).body;
    expect(JSON.stringify(stats)).toMatch(/open|total|vendor/i);
    expect((await (await apiAs('tenant')).post('/api/maintenance/sla/check', {})).status).toBe(403);
  });

  test('[MAIN-07] a vendor sees and updates only their own jobs', async () => {
    const { c, p } = await occupied();
    const vendorA = await vendor(c, 'Vendor A');
    const vendorB = await vendor(c, 'Vendor B');
    const a = await createSignedInUser(c.api, { email: `va.${c.sfx}@e2e.test`, name: 'Vee A', roleKey: 'VENDOR', partyType: 'VENDOR', partyId: vendorA });
    const jobId = await newJob(c, p);
    await until(() => job(c, jobId), (r) => r.body.status !== 'SUBMITTED');
    if ((await job(c, jobId)).body.status === 'AWAITING_APPROVAL') await c.api.post(`/api/maintenance/work-orders/${jobId}/actions/APPROVE`, { amount: 300 });
    if ((await job(c, jobId)).body.status === 'TRIAGED') await c.api.post(`/api/maintenance/work-orders/${jobId}/actions/ASSIGN`, { vendorId: vendorA });
    expect((await job(c, jobId)).body.vendor_id).toBeTruthy();
    const wasA = (await job(c, jobId)).body.vendor_id === vendorA;
    if (!wasA) { await c.api.post(`/api/maintenance/work-orders/${jobId}/actions/REASSIGN`, { vendorId: vendorA }); }
    const aList = rows(await a.api.get('/api/maintenance/work-orders'));
    if (wasA) expect(aList.some((w: any) => w.id === jobId)).toBe(true);
    expect(aList.every((w: any) => w.vendor_id === vendorA)).toBe(true);
    const b = await createSignedInUser(c.api, { email: `vb.${c.sfx}@e2e.test`, name: 'Vee B', roleKey: 'VENDOR', partyType: 'VENDOR', partyId: vendorB });
    expect(rows(await b.api.get('/api/maintenance/work-orders')).some((w: any) => w.id === jobId)).toBe(false);
    expect((await b.api.get(`/api/maintenance/work-orders/${jobId}`)).status).toBe(404);
    expect((await a.api.post('/api/maintenance/work-orders', { propertyId: p.propertyId, title: 'x' })).status).toBe(403);
    expect((await a.api.get('/api/maintenance/vendors')).status).toBe(403);
  });
});
