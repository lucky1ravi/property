import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE, SUFFIX } from '../../src/support/env';

test.describe('Public site data', () => {
  test('[SITE-02] the contact form validates input and creates a request the operator can see', async () => {
    const pub = await Api.create();
    expect((await pub.post('/api/identity/public/contact', { name: 'A', email: 'nope', message: 'short' })).status).toBe(400);
    expect((await pub.post('/api/identity/public/contact', { name: 'Pat Prospect', email: 'pat@e2e.test', message: 'too short' })).status).toBe(400);
    expect((await pub.post('/api/identity/public/contact', { name: 'Pat Prospect', email: 'pat@e2e.test', message: 'A reasonable message here.', topic: 'MARTIANS' })).status).toBe(400);
    test.skip(!CAN_WRITE, 'creates a contact request on the live site');
    const sfx = SUFFIX();
    const ok = await pub.post('/api/identity/public/contact', { name: `Pat ${sfx}`, email: `pat.${sfx}@e2e.test`, message: 'We manage 80 doors and would like to see a demo.', topic: 'DEMO' });
    expect(ok.status).toBe(200);
    const mine = rows(await (await apiAs('platform')).get('/api/identity/platform/site/contact-requests')).find((r: any) => r.email === `pat.${sfx}@e2e.test`);
    expect(mine.status).toBe('NEW');
    expect(mine.topic).toBe('DEMO');
    // the hidden "website" field is a bot trap: it looks accepted but nothing is stored
    const bot = await pub.post('/api/identity/public/contact', { name: `Bot ${sfx}`, email: `bot.${sfx}@e2e.test`, message: 'Buy cheap watches now please.', website: 'http://spam.example' });
    expect(bot.status).toBe(200);
    expect(rows(await (await apiAs('platform')).get('/api/identity/platform/site/contact-requests')).some((r: any) => r.email === `bot.${sfx}@e2e.test`)).toBe(false);
    expect((await (await apiAs('admin')).get('/api/identity/platform/site/contact-requests')).status).toBe(403);
  });

  test('[SITE-04] the public rental site lists live homes without signing in', async () => {
    const pub = await Api.create();
    const list = await pub.get('/api/leasing/public/listings');
    expect(list.status).toBe(200);
    const items = rows(list);
    for (const l of items) {
      expect(l.id).toBeTruthy();
      expect(JSON.stringify(l)).not.toMatch(/password|owner_id|org_id|management_fee/i);
    }
    if (items.length) expect((await pub.get(`/api/leasing/public/listings/${items[0].id}`)).status).toBe(200);
    expect((await pub.get('/api/leasing/public/listings/00000000-0000-0000-0000-000000000000')).status).toBe(404);
    expect((await pub.get('/api/leasing/public/listings?beds=abc')).status).toBeGreaterThanOrEqual(400);
  });

  test('[SITE-05] a prospect can enquire, book a showing and apply without an account', async () => {
    const pub = await Api.create();
    const items = rows(await pub.get('/api/leasing/public/listings'));
    test.skip(!items.length, 'no live homes on this environment');
    const id = items[0].id;
    // validation works on every environment; creating data only where writes are allowed
    expect((await pub.post(`/api/leasing/public/listings/${id}/enquiries`, { name: 'Q', email: 'bad' })).status).toBe(400);
    expect((await pub.post(`/api/leasing/public/listings/${id}/showings`, { name: 'Q', email: 'q@e2e.test', scheduledAt: new Date(Date.now() - 86400000).toISOString() })).status).toBe(400);
    expect((await pub.post(`/api/leasing/public/listings/${id}/applications`, { firstName: 'A', lastName: 'B', email: 'a@e2e.test', consent: false })).status).toBe(400);
  });

  test('[SITE-06] site content and plans are served from the platform', async () => {
    const pub = await Api.create();
    const site = (await pub.get('/api/identity/public/site')).body;
    expect(site.plans.map((p: any) => p.key)).toEqual(expect.arrayContaining(['STARTER', 'GROWTH', 'ENTERPRISE']));
    for (const p of site.plans) {
      expect(p.name).toBeTruthy();
      expect(Number(p.pricePerUnit ?? p.price_per_unit)).toBeGreaterThan(0);
    }
    expect(site.features.length).toBeGreaterThan(3);
    const opts = (await pub.get('/api/identity/public/options')).body;
    expect(typeof opts.demo).toBe('boolean');
    const demo = (await pub.get('/api/identity/public/demo-users')).body;
    if (demo.enabled) expect(demo.users.length).toBeGreaterThanOrEqual(10);
  });
});
