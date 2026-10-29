import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany, roleId, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease, apply, until, moveIn } from '../../src/support/builders';

test.describe('Leasing', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  test('[LEAS-01] a unit can be listed, the AI helps write it, and submitting leads to publish', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const id = (await c.api.post('/api/leasing/listings', { unitId: p.unitId })).body.id;
    expect((await c.api.get(`/api/leasing/listings/${id}`)).body.status).toBe('DRAFT');
    expect((await c.api.post('/api/leasing/listings', { unitId: '00000000-0000-0000-0000-000000000000' })).status).toBe(404);
    const ai = await c.api.post(`/api/leasing/listings/${id}/ai-assist`, {});
    expect(ai.status).toBe(200);
    const drafted = (await c.api.get(`/api/leasing/listings/${id}`)).body;
    expect(String(drafted.title ?? drafted.headline ?? drafted.description).length).toBeGreaterThan(5);
    expect((await c.api.put(`/api/leasing/listings/${id}`, { title: 'Bright 2 bed near downtown' })).status).toBe(200);
    expect((await c.api.post(`/api/leasing/listings/${id}/actions/SUBMIT`, {})).status).toBe(200);
    const live = await until(() => c.api.get(`/api/leasing/listings/${id}`), (r) => r.body.status !== 'AI_REVIEW');
    expect(live.body.status).toBe('LIVE');
    expect(live.body.title).toBe('Bright 2 bed near downtown');
  });

  test('[LEAS-02] a published listing appears on the public site and a draft can be deleted', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const pub = await Api.create();
    const draft = (await c.api.post('/api/leasing/listings', { unitId: p.unit2Id })).body.id;
    expect(rows(await pub.get('/api/leasing/public/listings')).some((l: any) => l.id === draft)).toBe(false);
    expect((await pub.get(`/api/leasing/public/listings/${draft}`)).status).toBe(404);
    const live = await publishListing(c.api, p.unitId);
    const list = rows(await pub.get('/api/leasing/public/listings'));
    const mine = list.find((l: any) => l.id === live);
    expect(mine).toBeTruthy();
    expect(JSON.stringify(mine)).not.toMatch(/owner_id|org_id|password/i);
    expect((await pub.get(`/api/leasing/public/listings/${live}`)).status).toBe(200);
    expect(rows(await pub.get('/api/leasing/public/listings?city=Austin')).some((l: any) => l.id === live)).toBe(true);
    expect(rows(await pub.get('/api/leasing/public/listings?city=Nowhereville')).some((l: any) => l.id === live)).toBe(false);
    expect(rows(await pub.get('/api/leasing/public/listings?beds=2')).some((l: any) => l.id === live)).toBe(true);
    expect(rows(await pub.get('/api/leasing/public/listings?maxRent=100')).some((l: any) => l.id === live)).toBe(false);
    expect((await c.api.del(`/api/leasing/listings/${draft}`)).status).toBe(200);
    expect((await c.api.get(`/api/leasing/listings/${draft}`)).status).toBe(404);
  });

  test('[LEAS-03] enquiries create leads that staff can answer', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const pub = await Api.create();
    expect((await pub.post(`/api/leasing/public/listings/${live}/enquiries`, { name: 'Q', email: 'bad' })).status).toBe(400);
    const e = await pub.post(`/api/leasing/public/listings/${live}/enquiries`, { name: 'Quinn Asker', email: `q.${p.sfx}@e2e.test`, message: 'Can I tour on Saturday?' });
    expect(e.status).toBe(200);
    const lead = rows(await c.api.get('/api/leasing/leads')).find((l: any) => l.email === `q.${p.sfx}@e2e.test`);
    expect(lead).toBeTruthy();
    expect(String(lead.ai_intent)).toMatch(/BOOK_SHOWING|tour/i);
    expect(Number(lead.ai_score)).toBeGreaterThanOrEqual(40);
    expect((await c.api.post(`/api/leasing/leads/${lead.id}/respond`, { reply: 'Saturday 10am works.' })).status).toBe(200);
    const after = rows(await c.api.get('/api/leasing/leads')).find((l: any) => l.id === lead.id);
    expect(after.status).not.toBe(lead.status);
    expect((await pub.post('/api/leasing/public/listings/00000000-0000-0000-0000-000000000000/enquiries', { name: 'Quinn', email: 'q@e2e.test' })).status).toBe(404);
  });

  test('[LEAS-04] showings can be requested publicly and managed by staff', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const pub = await Api.create();
    const at = new Date(Date.now() + 3 * 86400000).toISOString();
    const body = { name: 'Sam Visitor', email: `sv.${p.sfx}@e2e.test`, scheduledAt: at };
    expect((await pub.post(`/api/leasing/public/listings/${live}/showings`, { ...body, scheduledAt: new Date(Date.now() - 86400000).toISOString() })).status).toBe(400);
    const made = await pub.post(`/api/leasing/public/listings/${live}/showings`, body);
    expect(made.status).toBe(200);
    expect((await pub.post(`/api/leasing/public/listings/${live}/showings`, { ...body, email: 'other@e2e.test' })).status).toBe(409);
    const s = rows(await c.api.get('/api/leasing/showings')).find((x: any) => x.id === made.body.id);
    expect(s.status).toBe('REQUESTED');
    expect((await c.api.post(`/api/leasing/showings/${s.id}/actions/CONFIRM`, {})).status).toBe(200);
    const confirmed = rows(await c.api.get('/api/leasing/showings')).find((x: any) => x.id === s.id);
    expect(confirmed.status).toBe('CONFIRMED');
    expect(confirmed.broker_name).toBe('Ada Admin');
    expect((await c.api.post(`/api/leasing/showings/${s.id}/actions/CANCEL`, { reason: 'Visitor asked' })).status).toBe(200);
    expect((await c.api.post(`/api/leasing/showings/${s.id}/actions/CONFIRM`, {})).status).toBe(409);
  });

  test('[LEAS-05] an applicant applies publicly and is screened automatically', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const pub = await Api.create();
    const noConsent = await apply(pub, live, { first: 'No', last: 'Consent', email: 'n@e2e.test' }, { consent: false });
    expect(noConsent.status).toBe(400);
    const r = await apply(pub, live, { first: 'Rae', last: 'Resident', email: `rae.${p.sfx}@e2e.test` });
    expect(r.status).toBe(200);
    expect(r.body.status).toBe('SUBMITTED');
    expect(r.body.uploadToken).toBeTruthy();
    const app = await until(() => c.api.get(`/api/leasing/applications/${r.body.id}`), (x) => x.body.status !== 'SUBMITTED' && x.body.status !== 'SCREENING');
    expect(['SCREENED', 'APPROVED', 'OWNER_REVIEW']).toContain(app.body.status);
    expect(JSON.stringify(app.body)).toMatch(/score|screen/i);
    expect(rows(await c.api.get('/api/leasing/applications')).some((a: any) => a.id === r.body.id)).toBe(true);
    expect(rows(await c.api.get('/api/leasing/applications?status=REJECTED')).some((a: any) => a.id === r.body.id)).toBe(false);
  });

  test('[LEAS-06] applications go through approval, owner review and lease creation', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const r = await apply(await Api.create(), live, { first: 'Ola', last: 'Applicant', email: `ola.${p.sfx}@e2e.test` });
    const id = r.body.id;
    await until(() => c.api.get(`/api/leasing/applications/${id}`), (x) => ['SCREENED', 'APPROVED', 'OWNER_REVIEW'].includes(x.body.status));
    expect((await c.api.post(`/api/leasing/applications/${id}/lease`, { termMonths: 12, startDate: moveIn() })).status).toBe(409); // not approved yet
    for (const a of ['APPROVE', 'SEND_TO_OWNER', 'APPROVE']) await c.api.post(`/api/leasing/applications/${id}/actions/${a}`, { reason: 'ok' });
    expect((await c.api.get(`/api/leasing/applications/${id}`)).body.status).toBe('APPROVED');
    const lease = await c.api.post(`/api/leasing/applications/${id}/lease`, { termMonths: 12, startDate: moveIn() });
    expect(lease.status).toBe(200);
    expect((await c.api.get(`/api/leasing/leases/${lease.body.id}`)).body.status).toBe('DRAFT');
    expect(Number((await c.api.get(`/api/leasing/leases/${lease.body.id}`)).body.rent)).toBeGreaterThan(0);
    const r2 = await apply(await Api.create(), live, { first: 'Rex', last: 'Rejected', email: `rex.${p.sfx}@e2e.test` });
    await until(() => c.api.get(`/api/leasing/applications/${r2.body.id}`), (x) => x.body.status !== 'SUBMITTED' && x.body.status !== 'SCREENING');
    expect((await c.api.post(`/api/leasing/applications/${r2.body.id}/actions/REJECT`, { reason: 'Income too low' })).status).toBe(200);
    expect((await c.api.get(`/api/leasing/applications/${r2.body.id}`)).body.status).toBe('REJECTED');
  });

  test('[LEAS-07] a lease is sent, signed by the resident, countersigned and becomes active', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const email = `res.${p.sfx}@e2e.test`;
    const r = await apply(await Api.create(), live, { first: 'Rae', last: 'Resident', email });
    await until(() => c.api.get(`/api/leasing/applications/${r.body.id}`), (x) => ['SCREENED', 'APPROVED', 'OWNER_REVIEW'].includes(x.body.status));
    for (const a of ['APPROVE', 'SEND_TO_OWNER', 'APPROVE']) await c.api.post(`/api/leasing/applications/${r.body.id}/actions/${a}`, { reason: 'ok' });
    const leaseId = (await c.api.post(`/api/leasing/applications/${r.body.id}/lease`, { termMonths: 12, startDate: moveIn() })).body.id;
    const st = async () => (await c.api.get(`/api/leasing/leases/${leaseId}`)).body.status;
    expect((await c.api.post(`/api/leasing/leases/${leaseId}/actions/COUNTERSIGN`, {})).status).toBe(409);
    await c.api.post(`/api/leasing/leases/${leaseId}/actions/SEND`, {});
    expect(await st()).toBe('SENT');
    await c.api.post(`/api/leasing/leases/${leaseId}/actions/TENANT_SIGN`, { signature: 'Rae Resident' });
    expect(await st()).toBe('TENANT_SIGNED');
    await c.api.post(`/api/leasing/leases/${leaseId}/actions/COUNTERSIGN`, {});
    expect(await st()).toBe('ACTIVE');
    const unit = rows(await c.api.get('/api/leasing/units')).find((u: any) => u.id === p.unitId);
    expect(String(unit.status).toUpperCase()).toMatch(/OCCUPIED|LEASED/);
    expect(rows(await c.api.get('/api/leasing/leases?status=ACTIVE')).some((l: any) => l.id === leaseId)).toBe(true);
    expect(rows(await c.api.get('/api/leasing/residents')).some((x: any) => x.email === email)).toBe(true);
  });

  test('[LEAS-08] lease renewal can be started and respects the maximum increase', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const l = await activeLease(c, p, live, `ren.${p.sfx}@e2e.test`);
    const bad = await c.api.post(`/api/leasing/leases/${l.leaseId}/renewal`, { rent: 99999 });
    expect(bad.status).toBe(400);
    expect(String(bad.body.detail)).toMatch(/maximum/);
    const ok = await c.api.post(`/api/leasing/leases/${l.leaseId}/renewal`, { termMonths: 12 });
    expect(ok.status).toBe(200);
    const lease = (await c.api.get(`/api/leasing/leases/${l.leaseId}`)).body;
    expect(lease.status).toMatch(/RENEWAL/);
    expect(lease.renewal ?? lease.renewal_offer).toBeTruthy();
  });

  test('[LEAS-09] a resident sees their own lease and nobody else\'s', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const email = `mine.${p.sfx}@e2e.test`;
    const l = await activeLease(c, p, live, email);
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const t = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    expect((await t.api.get('/api/leasing/leases/mine')).body.id).toBe(l.leaseId);
    expect(rows(await t.api.get('/api/leasing/leases')).every((x: any) => x.id === l.leaseId)).toBe(true);
    expect((await t.api.get('/api/leasing/properties')).status).toBe(403);
    expect((await t.api.post('/api/leasing/owners', { name: 'x' })).status).toBe(403);
    const other = await apiAs('tenant');
    const demoLeases = rows(await other.get('/api/leasing/leases'));
    expect(demoLeases.some((x: any) => x.id === l.leaseId)).toBe(false);
  });

  test('[LEAS-11] the applicant document upload link works for one application only', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const pub = await Api.create();
    const a = await apply(pub, live, { first: 'Una', last: 'Uploader', email: `u.${p.sfx}@e2e.test` });
    const b = await apply(pub, live, { first: 'Bo', last: 'Other', email: `b.${p.sfx}@e2e.test` });
    const link = await c.api.post(`/api/leasing/applications/${a.body.id}/upload-link`);
    expect(link.status).toBe(200);
    expect(link.body.path).toContain(`/homes/applications/${a.body.id}/documents?token=`);
    const token = new URL('http://x' + link.body.path).searchParams.get('token')!;
    const files = (id: string, t: string) => pub.get(`/api/documents/public/applications/${id}/files?token=${encodeURIComponent(t)}`);
    expect((await files(a.body.id, token)).status).toBe(200);
    expect((await files(b.body.id, token)).status).toBe(403);
    expect((await files(a.body.id, 'garbage')).status).toBe(403);
    expect((await files(a.body.id, a.body.uploadToken)).status).toBe(200);
  });
});
