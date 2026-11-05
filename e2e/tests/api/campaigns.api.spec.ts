import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, rows } from '../../src/support/api';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, apply, until } from '../../src/support/builders';
import { bodySignature } from '../../src/support/receiver';

/**
 * Marketing campaigns. A company makes one per ad or listing site. Leads and applications reach it by a signed post from the ad platform, or by the
 * tracking link on the public site, and the campaign counts leads, tours and applications. Anything that is not properly signed is refused.
 */
test.describe('Campaigns', () => {
  needsWrites();
  test.setTimeout(180_000);

  async function setup() {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const pub = await Api.create();
    return { c, p, listing, pub };
  }

  const post = (pub: Api, key: string, secret: string | null, body: object, tamper = false) => {
    const text = JSON.stringify(body);
    const headers: Record<string, string> = {};
    if (secret) headers['X-Signature'] = bodySignature(secret, text);
    return pub.call('POST', `/api/leasing/public/inbound/${key}`, tamper ? JSON.parse(text.replace('Dana', 'Eve')) : body, headers);
  };

  test('[CAMP-01] a campaign is made with a name and channel, its secret is shown once, and residents cannot make one', async () => {
    const { c, p } = await setup();
    const resident = await createSignedInUser(c.api, { email: `r.${p.sfx}@e2e.test`, name: 'Rae Resident', roleKey: 'TENANT' });
    expect((await resident.api.post('/api/leasing/campaigns', { name: 'Nope', channel: 'EMAIL' })).status).toBe(403);
    expect((await c.api.post('/api/leasing/campaigns', { name: 'x', channel: 'TWITTER' })).status, 'an unknown channel').toBe(400);
    expect((await c.api.post('/api/leasing/campaigns', { name: '', channel: 'EMAIL' })).status, 'a name is needed').toBe(400);
    const made = await c.api.post('/api/leasing/campaigns', { name: `Spring push ${p.sfx}`, channel: 'FACEBOOK_ADS', spend: 500 });
    expect(made.status).toBe(200);
    expect(made.body.secret).toMatch(/^whsec_/);
    expect(made.body.public_key).toMatch(/^cmp_/);
    const listed = await c.api.get('/api/leasing/campaigns');
    expect(JSON.stringify(listed.body)).not.toContain('whsec_');
    expect(rows(listed).find((x: any) => x.id === made.body.id).channel).toBe('FACEBOOK_ADS');
    const other = await onboardCompany();
    expect((await other.api.get(`/api/leasing/campaigns/${made.body.id}`)).status, 'another company cannot open it').toBe(404);
  });

  test('[CAMP-02] a signed lead or application is accepted and counted; a wrong, missing or altered signature is refused; a repeat is not entered twice', async () => {
    const { c, p, listing, pub } = await setup();
    const camp = (await c.api.post('/api/leasing/campaigns', { name: `Zillow ${p.sfx}`, channel: 'ZILLOW' })).body;
    const lead = { type: 'lead', name: 'Dana Prospect', email: `dana.${p.sfx}@example.com`, phone: '5125550101', listingId: listing, message: 'Is it available? I would like a tour.' };
    expect((await post(pub, camp.public_key, camp.secret, lead)).status).toBe(200);
    const again = await post(pub, camp.public_key, camp.secret, lead);
    expect(again.status).toBe(200);
    expect(again.body.duplicate, 'the same person asking about the same home again').toBe(true);
    expect([401, 403]).toContain((await post(pub, camp.public_key, 'not-the-secret', lead)).status);
    expect([401, 403]).toContain((await post(pub, camp.public_key, null, lead)).status);
    expect([401, 403]).toContain((await post(pub, camp.public_key, camp.secret, lead, true)).status);
    expect((await post(pub, 'cmp_doesnotexist', camp.secret, lead)).status).toBe(404);
    expect((await post(pub, camp.public_key, camp.secret, { type: 'lead', name: 'No Home', email: 'x@example.com' })).status, 'a home is needed').toBeGreaterThanOrEqual(400);
    const app = { type: 'application', name: 'Ann Applicant', email: `ann.${p.sfx}@example.com`, listingId: listing, consent: true, monthlyIncome: 7000 };
    expect((await post(pub, camp.public_key, camp.secret, { ...app, consent: false })).status, 'consent is needed').toBeGreaterThanOrEqual(400);
    expect((await post(pub, camp.public_key, camp.secret, app)).status).toBe(200);
    const detail = (await c.api.get(`/api/leasing/campaigns/${camp.id}`)).body;
    expect(Number(detail.applications)).toBe(1);
    expect(Number(detail.leads), 'one lead from the post and one made for the applicant').toBe(2);
    expect(detail.activity.map((a: any) => a.kind).sort()).toEqual(['APPLICATION', 'LEAD', 'LEAD']);
  });

  test('[CAMP-03] a paused campaign takes nothing, and rotating the secret stops the old one at once', async () => {
    const { c, p, listing, pub } = await setup();
    const camp = (await c.api.post('/api/leasing/campaigns', { name: `Email ${p.sfx}`, channel: 'EMAIL' })).body;
    const lead = (n: string) => ({ type: 'lead', name: n, email: `${n.toLowerCase()}.${p.sfx}@example.com`, listingId: listing, message: 'Hello' });
    expect((await c.api.post(`/api/leasing/campaigns/${camp.id}/pause`, {})).body.state).toBe('PAUSED');
    expect((await post(pub, camp.public_key, camp.secret, lead('Late'))).status).toBeGreaterThanOrEqual(400);
    await c.api.post(`/api/leasing/campaigns/${camp.id}/resume`, {});
    expect((await post(pub, camp.public_key, camp.secret, lead('Early'))).status).toBe(200);
    const fresh = (await c.api.post(`/api/leasing/campaigns/${camp.id}/rotate-key`, {})).body.secret as string;
    expect(fresh).toMatch(/^whsec_/);
    expect(fresh).not.toBe(camp.secret);
    expect((await post(pub, camp.public_key, camp.secret, lead('Old'))).status, 'the old secret').toBeGreaterThanOrEqual(400);
    expect((await post(pub, camp.public_key, fresh, lead('New'))).status).toBe(200);
  });

  test('[CAMP-04] a visitor who arrives by the tracking link and asks, tours or applies is counted for the campaign, and each makes a lead', async () => {
    const { c, p, listing, pub } = await setup();
    const camp = (await c.api.post('/api/leasing/campaigns', { name: `Tracked ${p.sfx}`, channel: 'GOOGLE_ADS' })).body;
    const code = camp.tracking_code as string;
    const enquirer = `ask.${p.sfx}@example.com`, tourist = `tour.${p.sfx}@example.com`, applicant = `apply.${p.sfx}@example.com`;
    expect((await pub.post(`/api/leasing/public/listings/${listing}/enquiries`, { name: 'Ask Person', email: enquirer, message: 'Hello', campaign: code })).status).toBe(200);
    const when = new Date(Date.now() + 5 * 86400000).toISOString();
    expect((await pub.post(`/api/leasing/public/listings/${listing}/showings`, { name: 'Tina Tourist', email: tourist, type: 'SELF_GUIDED', scheduledAt: when, campaign: code })).status).toBe(200);
    expect((await apply(pub, listing, { first: 'Apple', last: 'Applicant', email: applicant }, { campaign: code })).status).toBe(200);
    const detail = (await until(() => c.api.get(`/api/leasing/campaigns/${camp.id}`), (r) => Number(r.body.showings) >= 1)).body;
    expect(Number(detail.showings)).toBe(1);
    expect(Number(detail.applications)).toBe(1);
    expect(Number(detail.leads), 'the enquiry, plus a lead for the tour and one for the application').toBe(3);
    expect(detail.activity.map((a: any) => a.kind).sort()).toEqual(['APPLICATION', 'LEAD', 'LEAD', 'LEAD', 'SHOWING']);
    const emails = rows(await c.api.get('/api/leasing/leads')).map((l: any) => l.email);
    expect(emails).toEqual(expect.arrayContaining([enquirer, tourist, applicant]));
    // A tour or application from someone who already has a lead for the home does not add another.
    expect((await pub.post(`/api/leasing/public/listings/${listing}/showings`, { name: 'Tina Tourist', email: tourist, type: 'SELF_GUIDED', scheduledAt: new Date(Date.now() + 6 * 86400000).toISOString(), campaign: code })).status).toBe(200);
    expect(Number((await c.api.get(`/api/leasing/campaigns/${camp.id}`)).body.leads)).toBe(3);
    expect(rows(await c.api.get('/api/leasing/leads')).filter((l: any) => l.email === tourist).length).toBe(1);
  });
});
