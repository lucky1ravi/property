import { Api, rows } from './api';
import { onboardCompany, createSignedInUser, Company } from './company';
import { buildPortfolio, publishListing, activeLease, until, Portfolio } from './builders';

/**
 * A company with one home that somebody lives in: the starting point for tests about money, repairs and paperwork that
 * should not each redo the whole move-in. The resident has a real account.
 */
export async function occupiedHome() {
  const c = await onboardCompany();
  const p = await buildPortfolio(c);
  const listing = await publishListing(c.api, p.unitId);
  const email = `rae.${p.sfx}@e2e.test`;
  const lease = await activeLease(c, p, listing, email);
  const res = rows(await c.api.get('/api/leasing/residents')).find((x: any) => x.email === email);
  const resident = await createSignedInUser(c.api, { email, name: 'Rae Resident', roleKey: 'TENANT', partyType: 'TENANT', partyId: res.id });
  // The move-in charges are created a moment after the lease is activated, by the billing service reacting to the event.
  await until(() => resident.api.get('/api/billing/my'), (r) => Number(r.body.balance) > 0);
  return { c, p, listing, email, lease, resident };
}

/** A vendor with an account, for repairs. */
export async function vendorFor(c: Company, p: Portfolio, label = 'Pete') {
  const email = `${label.toLowerCase()}.${p.sfx}@e2e.test`;
  const id = (await c.api.post('/api/maintenance/vendors', { name: `${label} Plumbing ${p.sfx}`, contact: label, phone: '5125550123', email, trades: ['PLUMBING'], cities: ['Austin'] })).body.id;
  return { id, ...(await createSignedInUser(c.api, { email, name: `${label} Vendor`, roleKey: 'VENDOR', partyType: 'VENDOR', partyId: id })) };
}

export { Api };
