import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE, SUFFIX } from '../../src/support/env';
import { onboardCompany, createSignedInUser, Company } from '../../src/support/company';
import { buildPortfolio, publishListing, activeLease, Portfolio } from '../../src/support/builders';

const ZERO = '00000000-0000-0000-0000-000000000000';
let counter = 0;
const pdf = (tag = '') => Buffer.from(`%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n% ${tag} ${SUFFIX()} ${counter++}\n`);
const PNG = () => Buffer.concat([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64'), Buffer.from(SUFFIX() + counter++)]);

async function setup() {
  const c = await onboardCompany();
  const p = await buildPortfolio(c);
  return { c, p };
}
async function upload(api: Api, p: Portfolio, fields: Record<string, string> = {}, buf = pdf('doc')) {
  return api.upload('/api/documents', { name: 'lease-addendum.pdf', mimeType: 'application/pdf', buffer: buf },
    { title: 'Pet addendum', category: 'PROPERTY', entityType: 'PROPERTY', entityId: p.propertyId, propertyId: p.propertyId, ...fields });
}
const doc = (c: Company, id: string) => c.api.get(`/api/documents/${id}`);

test.describe('Documents', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  test('[DOC-01] documents can be uploaded, classified, edited, reviewed, archived, restored, deleted and purged', async () => {
    const { c, p } = await setup();
    const meta = (await c.api.get('/api/documents/meta')).body;
    expect(meta.categories.map((x: any) => x.key)).toEqual(expect.arrayContaining(['LEASE', 'IDENTITY', 'TAX', 'INSURANCE', 'PROPERTY']));
    const cls = await c.api.post('/api/documents/classify', { fileName: 'W-9 Maple LLC.pdf', entityType: 'OWNER' });
    expect(cls.status).toBe(200);
    expect(JSON.stringify(cls.body)).toMatch(/TAX|W-9/);
    expect((await upload(c.api, p, { category: 'NOT_A_CATEGORY' })).status).toBe(400);
    expect((await upload(c.api, p, { entityId: '' })).status).toBe(400);
    const buf = pdf('dup');
    const up = await upload(c.api, p, {}, buf);
    expect(up.status).toBe(200);
    const id = up.body.id;
    expect(up.body.status).toBe('ACTIVE');
    expect((await upload(c.api, p, {}, buf)).status).toBe(409);
    expect((await upload(c.api, p, { allowDuplicate: 'true' }, buf)).status).toBe(200);
    expect((await c.api.put(`/api/documents/${id}`, { title: 'Pet addendum v2', shareOwner: false })).status).toBe(200);
    expect((await doc(c, id)).body.title).toBe('Pet addendum v2');
    expect((await doc(c, id)).body.share_owner).toBe(false);
    const v2buf = pdf('v2');
    const v2 = await c.api.upload(`/api/documents/${id}/versions`, { name: 'v2.pdf', mimeType: 'application/pdf', buffer: v2buf });
    expect(v2.status).toBe(200);
    expect((await doc(c, id)).body.current_version).toBe(2);
    expect((await c.api.upload(`/api/documents/${id}/versions`, { name: 'v2.pdf', mimeType: 'application/pdf', buffer: v2buf })).status).toBe(409); // same file again
    expect((await c.api.post(`/api/documents/${id}/review`, { decision: 'REJECTED' })).status).toBe(400);
    expect((await c.api.post(`/api/documents/${id}/review`, { decision: 'MAYBE' })).status).toBe(400);
    expect((await c.api.post(`/api/documents/${id}/review`, { decision: 'REJECTED', note: 'Unreadable scan' })).status).toBe(200);
    expect((await doc(c, id)).body.review_status).toBe('REJECTED');
    expect((await c.api.post(`/api/documents/${id}/review`, { decision: 'ACCEPTED' })).status).toBe(200);
    expect((await c.api.post(`/api/documents/${id}/actions/ARCHIVE`, {})).status).toBe(200);
    expect((await doc(c, id)).body.status).toBe('ARCHIVED');
    expect((await c.api.post(`/api/documents/${id}/actions/RESTORE`, {})).status).toBe(200);
    expect((await c.api.post(`/api/documents/${id}/actions/EXPLODE`, {})).status).toBe(400);
    expect((await c.api.post(`/api/documents/${id}/purge`, {})).status).toBe(409); // trash first
    expect((await c.api.post(`/api/documents/${id}/actions/DELETE`, {})).status).toBe(200);
    expect(rows(await c.api.get('/api/documents')).some((d: any) => d.id === id)).toBe(false);
    expect(rows(await c.api.get('/api/documents?status=DELETED')).some((d: any) => d.id === id)).toBe(true);
    const purge = await c.api.post(`/api/documents/${id}/purge`, { reason: 'Uploaded in error' });
    expect(purge.status).toBe(200);
    expect((await doc(c, id)).status).toBe(404);
    expect((await c.api.get(`/api/documents/${ZERO}`)).status).toBe(404);
    const sum = await c.api.get('/api/documents/summary');
    expect(sum.status).toBe(200);
    const zip = await c.api.post('/api/documents/export', { ids: [(await upload(c.api, p, {}, pdf('zip'))).body.id] });
    expect(zip.status).toBe(200);
    expect((await c.api.post('/api/documents/export', { ids: [] })).status).toBe(400);
  });

  test('[DOC-02] legal hold blocks deletion and purge until released', async () => {
    const { c, p } = await setup();
    const id = (await upload(c.api, p)).body.id;
    expect((await c.api.post(`/api/documents/${id}/legal-hold`, { on: true })).status).toBe(400);
    expect((await c.api.post(`/api/documents/${id}/legal-hold`, { on: true, reason: 'Claim 2026-114' })).status).toBe(200);
    expect((await doc(c, id)).body.legal_hold).toBe(true);
    const del = await c.api.post(`/api/documents/${id}/actions/DELETE`, {});
    expect(del.status).toBe(409);
    expect(String(del.body.detail)).toMatch(/legal hold/);
    expect((await c.api.post(`/api/documents/${id}/legal-hold`, { on: false, reason: 'Claim closed' })).status).toBe(200);
    expect((await c.api.post(`/api/documents/${id}/actions/DELETE`, {})).status).toBe(200);
    expect((await c.api.post(`/api/documents/${id}/legal-hold`, { on: true, reason: 'again' })).status).toBe(200);
    expect((await c.api.post(`/api/documents/${id}/purge`, { reason: 'x' })).status).toBe(409);
    const broker = await createSignedInUser(c.api, { email: `bk.${c.sfx}@e2e.test`, name: 'Bea Broker', roleKey: 'BROKER' });
    expect((await broker.api.post(`/api/documents/${id}/legal-hold`, { on: false, reason: 'x' })).status).toBe(403);
  });

  test('[DOC-03] requirements and gaps are reported per property and lease', async () => {
    const { c, p } = await setup();
    const req = await c.api.get(`/api/documents/requirements?entityType=PROPERTY&entityId=${p.propertyId}`);
    expect(req.status).toBe(200);
    expect(req.body).toBeTruthy();
    const live = await publishListing(c.api, p.unitId);
    const l = await activeLease(c, p, live, `req.${p.sfx}@e2e.test`);
    const lreq = await c.api.get(`/api/documents/requirements?entityType=LEASE&entityId=${l.leaseId}`);
    expect(lreq.status).toBe(200);
    const gaps = await c.api.get('/api/documents/requirements/gaps');
    expect(gaps.status).toBe(200);
    expect(Array.isArray(rows(gaps)) || Array.isArray(gaps.body)).toBe(true);
    expect((await c.api.get('/api/documents/requirements')).status).toBe(400);
    const tenantGaps = await (await apiAs('tenant')).get('/api/documents/requirements/gaps');
    expect(rows(tenantGaps)).toEqual([]);
  });

  test('[DOC-04] e-signature requests: signers, ordering, remind, sign, decline, cancel', async () => {
    const { c, p } = await setup();
    const sue = await createSignedInUser(c.api, { email: `sue.${c.sfx}@e2e.test`, name: 'Sue Signer', roleKey: 'ORG_ADMIN' });
    const sam = await createSignedInUser(c.api, { email: `sam.${c.sfx}@e2e.test`, name: 'Sam Second', roleKey: 'ORG_ADMIN' });
    const id = (await upload(c.api, p)).body.id;
    const cands = rows(await c.api.get(`/api/documents/${id}/signers`));
    const sueId = cands.find((x: any) => x.name === 'Sue Signer').userId;
    const samId = cands.find((x: any) => x.name === 'Sam Second').userId;
    expect((await c.api.post(`/api/documents/${id}/signature-requests`, { signers: [] })).status).toBe(400);
    expect((await c.api.post(`/api/documents/${id}/signature-requests`, { signers: [ZERO] })).status).toBe(400);
    const txt = (await c.api.upload('/api/documents', { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('notes ' + SUFFIX()) }, { title: 'Notes', category: 'PROPERTY', entityType: 'PROPERTY', entityId: p.propertyId, propertyId: p.propertyId })).body;
    if (txt?.id) expect((await c.api.post(`/api/documents/${txt.id}/signature-requests`, { signers: [sueId] })).status).toBe(400);
    const req = await c.api.post(`/api/documents/${id}/signature-requests`, { signers: [sueId, samId], sequential: true, message: 'Please sign.' });
    expect(req.status).toBe(200);
    const rid = req.body.id;
    expect((await doc(c, id)).body.signature_status).toBe('PENDING');
    expect((await c.api.post(`/api/documents/${id}/signature-requests`, { signers: [sueId] })).status).toBe(409);
    expect((await c.api.post(`/api/documents/signatures/${rid}/remind`, {})).status).toBe(200);
    expect(rows(await sue.api.get('/api/documents/signatures?mine=true')).some((r: any) => r.id === rid)).toBe(true);
    // second signer must wait their turn
    const early = await sam.api.post(`/api/documents/signatures/${rid}/sign`, { signature: 'Sam Second', consent: true });
    expect(early.status).toBe(409);
    expect((await sue.api.post(`/api/documents/signatures/${rid}/sign`, { signature: 'Wrong Name', consent: true })).status).toBe(400);
    expect((await sue.api.post(`/api/documents/signatures/${rid}/sign`, { signature: 'Sue Signer', consent: false })).status).toBe(400);
    expect((await sue.api.post(`/api/documents/signatures/${rid}/sign`, { signature: 'Sue Signer', consent: true })).status).toBe(200);
    expect((await sue.api.post(`/api/documents/signatures/${rid}/sign`, { signature: 'Sue Signer', consent: true })).status).toBe(409);
    expect((await doc(c, id)).body.signature_status).toBe('PARTIAL');
    expect((await c.api.upload(`/api/documents/${id}/versions`, { name: 'v2.pdf', mimeType: 'application/pdf', buffer: pdf('vv') })).status).toBe(409);
    expect((await sam.api.post(`/api/documents/signatures/${rid}/decline`, { reason: '' })).status).toBe(400);
    expect((await sam.api.post(`/api/documents/signatures/${rid}/sign`, { signature: 'Sam Second', consent: true })).status).toBe(200);
    expect((await doc(c, id)).body.signature_status).toBe('SIGNED');
    expect((await c.api.get(`/api/documents/signatures/${rid}`)).body.status).toBe('COMPLETED');
    // decline and cancel on fresh documents
    const d2 = (await upload(c.api, p, {}, pdf('d2'))).body.id;
    const r2 = (await c.api.post(`/api/documents/${d2}/signature-requests`, { signers: [sueId] })).body.id;
    expect((await sue.api.post(`/api/documents/signatures/${r2}/decline`, { reason: 'Terms are wrong' })).status).toBe(200);
    expect((await c.api.get(`/api/documents/signatures/${r2}`)).body.status).toMatch(/DECLINED/);
    const d3 = (await upload(c.api, p, {}, pdf('d3'))).body.id;
    const r3 = (await c.api.post(`/api/documents/${d3}/signature-requests`, { signers: [sueId] })).body.id;
    expect((await c.api.post(`/api/documents/signatures/${r3}/cancel`, { reason: 'Sent by mistake' })).status).toBe(200);
    expect((await c.api.get(`/api/documents/signatures/${r3}`)).body.status).toMatch(/CANCEL/);
    expect((await c.api.post(`/api/documents/signatures/${r3}/remind`, {})).status).toBe(409);
    expect(rows(await c.api.get('/api/documents/signatures?status=COMPLETED')).some((r: any) => r.id === rid)).toBe(true);
  });

  test('[DOC-05] photos can be uploaded, reviewed and removed', async () => {
    const { c, p } = await setup();
    expect((await c.api.get('/api/documents/media/settings')).status).toBe(200);
    expect((await c.api.upload('/api/documents/media', { name: 'x.png', mimeType: 'image/png', buffer: PNG() }, { kind: 'WEIRD' })).status).toBe(400);
    expect((await c.api.upload('/api/documents/media', { name: 'x.png', mimeType: 'image/png', buffer: PNG() }, { kind: 'PROPERTY' })).status).toBe(400);
    expect((await c.api.upload('/api/documents/media', { name: 'x.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('MZ' + SUFFIX()) }, { kind: 'PROPERTY', subjectId: p.propertyId })).status).toBe(400);
    const up = await c.api.upload('/api/documents/media', { name: 'front.png', mimeType: 'image/png', buffer: PNG() }, { kind: 'PROPERTY', subjectId: p.propertyId });
    expect(up.status).toBe(200);
    const id = up.body.id;
    const item = (await c.api.get(`/api/documents/media/${id}`)).body;
    expect(['APPROVED', 'PENDING', 'FLAGGED', 'PENDING_REVIEW', 'REJECTED']).toContain(item.status);
    expect((await c.api.get(`/api/documents/media/${id}/content`)).status).toBe(200);
    expect(rows(await c.api.get('/api/documents/media')).some((m: any) => m.id === id)).toBe(true);
    expect((await c.api.post(`/api/documents/media/${id}/decision`, { decision: 'MAYBE' })).status).toBe(400);
    const other = item.status === 'REJECTED' ? 'APPROVE' : 'REJECT';
    if (other === 'REJECT') expect((await c.api.post(`/api/documents/media/${id}/decision`, { decision: 'REJECT' })).status).toBe(400);
    expect((await c.api.post(`/api/documents/media/${id}/decision`, { decision: other, note: 'Out of focus' })).status).toBe(200);
    expect((await c.api.upload('/api/documents/media', { name: 'front.png', mimeType: 'image/png', buffer: PNG() }, { kind: 'PROPERTY', subjectId: p.propertyId })).status).toBe(200);
    expect((await c.api.del(`/api/documents/media/${id}`)).body.removed).toBe(true);
    expect((await c.api.get(`/api/documents/media/${id}`)).status).toBe(404);
  });

  test('[DOC-06] documents are only visible to permitted roles and people', async () => {
    const { c, p } = await setup();
    const id = (await upload(c.api, p, { shareOwner: 'false' })).body.id;
    const live = await publishListing(c.api, p.unitId);
    const email = `dv.${p.sfx}@e2e.test`;
    const l = await activeLease(c, p, live, email);
    const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
    const tenant = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
    expect(rows(await tenant.api.get('/api/documents')).some((d: any) => d.id === id)).toBe(false);
    expect([403, 404]).toContain((await tenant.api.get(`/api/documents/${id}`)).status);
    expect((await tenant.api.post(`/api/documents/${id}/actions/DELETE`, {})).status).toBeGreaterThanOrEqual(403);
    expect((await tenant.api.post(`/api/documents/${id}/legal-hold`, { on: true, reason: 'x' })).status).toBe(403);
    expect((await (await apiAs('harbor')).get(`/api/documents/${id}`)).status).toBe(404);
    expect((await (await apiAs('harbor')).get('/api/documents/media')).status).toBe(200);
    const vendor = await (await apiAs('vendor')).get('/api/documents');
    expect(rows(vendor).some((d: any) => d.id === id)).toBe(false);
    expect((await (await Api.create()).get('/api/documents')).status).toBe(401);
    expect(l.leaseId).toBeTruthy();
  });
});
