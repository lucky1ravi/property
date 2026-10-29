import { Api } from './api';
import { Company } from './company';
import { SUFFIX } from './env';

export async function until<T>(fn: () => Promise<T>, ok: (v: T) => boolean, timeoutMs = 45_000): Promise<T> {
  const end = Date.now() + timeoutMs;
  let v = await fn();
  while (!ok(v) && Date.now() < end) { await new Promise((r) => setTimeout(r, 1000)); v = await fn(); }
  return v;
}

export interface Portfolio { ownerId: string; propertyId: string; unitId: string; unit2Id: string; sfx: string }

/** An owner with one property and two units in the given company. */
export async function buildPortfolio(c: Company): Promise<Portfolio> {
  const sfx = SUFFIX();
  const ownerId = (await c.api.post('/api/leasing/owners', { name: `Maple ${sfx}`, contact: 'Olivia Owner', email: `olivia.${sfx}@e2e.test`, phone: '5125550101', managementFeePct: 7.5, approvalLimit: 750 })).body.id;
  const propertyId = (await c.api.post('/api/leasing/properties', { ownerId, name: `Court ${sfx}`, street: '10 Main St', city: 'Austin', state: 'TX', zip: '78701', type: 'MULTIFAMILY', yearBuilt: 2005 })).body.id;
  const unitId = (await c.api.post(`/api/leasing/properties/${propertyId}/units`, { number: '1A', beds: 2, baths: '1.5', sqft: 900, marketRent: 1800 })).body.id;
  const unit2Id = (await c.api.post(`/api/leasing/properties/${propertyId}/units`, { number: '2B', beds: 1, baths: '1', sqft: 650, marketRent: 1400 })).body.id;
  return { ownerId, propertyId, unitId, unit2Id, sfx };
}

/** Creates a listing for the unit, asks the AI to write it and submits it; waits until it is live. */
export async function publishListing(api: Api, unitId: string): Promise<string> {
  const id = (await api.post('/api/leasing/listings', { unitId })).body.id;
  await api.post(`/api/leasing/listings/${id}/ai-assist`, {});
  await api.post(`/api/leasing/listings/${id}/actions/SUBMIT`, {});
  const l = await until(() => api.get(`/api/leasing/listings/${id}`), (r) => r.body.status !== 'AI_REVIEW');
  if (l.body.status !== 'LIVE') throw new Error(`listing not live: ${l.body.status}`);
  return id;
}

export function moveIn(days = 14): string { return new Date(Date.now() + days * 86400000).toISOString().slice(0, 10); }

/** Applies publicly to a live listing. */
export async function apply(pub: Api, listingId: string, who: { first: string; last: string; email: string }, extra: Record<string, unknown> = {}) {
  return pub.post(`/api/leasing/public/listings/${listingId}/applications`, {
    firstName: who.first, lastName: who.last, email: who.email, phone: '5125550177', consent: true, monthlyIncome: 9000, employer: 'Acme',
    monthsEmployed: 30, occupants: 2, moveInDate: moveIn(), ...extra,
  });
}

/** Waits for automatic screening, then walks the application to a lease that is signed by both sides and active. */
export async function activeLease(c: Company, p: Portfolio, listingId: string, email: string, name = ['Rae', 'Resident']) {
  const pub = await Api.create();
  const applied = await apply(pub, listingId, { first: name[0], last: name[1], email });
  const appId = applied.body.id as string;
  await until(() => c.api.get(`/api/leasing/applications/${appId}`), (r) => ['SCREENED', 'APPROVED', 'OWNER_REVIEW'].includes(r.body.status));
  for (const a of ['APPROVE', 'SEND_TO_OWNER', 'APPROVE']) await c.api.post(`/api/leasing/applications/${appId}/actions/${a}`, { reason: 'ok' });
  const leaseId = (await c.api.post(`/api/leasing/applications/${appId}/lease`, { termMonths: 12, startDate: moveIn() })).body.id as string;
  await c.api.post(`/api/leasing/leases/${leaseId}/actions/SEND`, {});
  await c.api.post(`/api/leasing/leases/${leaseId}/actions/TENANT_SIGN`, { signature: `${name[0]} ${name[1]}` });
  await c.api.post(`/api/leasing/leases/${leaseId}/actions/COUNTERSIGN`, {});
  const lease = (await c.api.get(`/api/leasing/leases/${leaseId}`)).body;
  return { appId, leaseId, tenantId: lease.tenant_id as string, status: lease.status as string };
}
