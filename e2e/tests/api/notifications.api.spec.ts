import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { SUFFIX } from '../../src/support/env';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, apply, until } from '../../src/support/builders';

/**
 * Somebody outside the company fills in a form on the website: the people who must act on it are told in their notifications,
 * without anybody having to go and look. One test per form, each checking who is told and where the notification leads.
 */
test.describe('Website forms notify the right people', () => {
  needsWrites();
  test.setTimeout(180_000);

  const titles = (r: any) => rows(r).map((n: any) => `${n.title} | ${n.body} | ${n.link}`);

  test('[NOTIFY-01] a demo request on the contact form notifies every platform administrator, with a link to the enquiry inbox', async () => {
    const platform = await apiAs('platform');
    const sfx = SUFFIX();
    const sent = await (await Api.create()).post('/api/identity/public/contact', { name: `Dana ${sfx}`, email: `dana.${sfx}@e2e.test`, company: `Dana Homes ${sfx}`, units: '120',
      message: 'We manage 120 doors across Texas and would like a demo this week.', topic: 'DEMO' });
    expect(sent.status).toBe(200);
    const inbox = await until(() => platform.get('/api/workflow/notifications'), (r) => titles(r).some((t) => t.includes(`Dana ${sfx}`)));
    const mine = rows(inbox).find((n: any) => String(n.title).includes(`Dana ${sfx}`));
    expect(mine.title).toMatch(/^Demo request: Dana/);
    expect(mine.body).toContain(`Dana Homes ${sfx}`);
    expect(mine.body).toContain('120 units');
    expect(mine.link).toBe('/admin/enquiries');
    expect(mine.priority).toBe('HIGH');
    // The inbox entry is there too, and a company administrator is not told about somebody else's enquiry.
    const listed = rows(await platform.get('/api/identity/platform/site/contact-requests')).find((r: any) => r.email === `dana.${sfx}@e2e.test`);
    expect(listed.status).toBe('NEW');
    const admin = await apiAs('admin');
    expect(titles(await admin.get('/api/workflow/notifications')).some((t) => t.includes(`Dana ${sfx}`))).toBe(false);
  });

  test('[NOTIFY-02] other topics read naturally, and a bot or a refused form notifies nobody', async () => {
    const platform = await apiAs('platform');
    const pub = await Api.create();
    const sfx = SUFFIX();
    for (const [topic, lead] of [['PRICING', 'Pricing question'], ['SUPPORT', 'Support question'], ['PARTNERSHIP', 'Partnership enquiry'], ['OTHER', 'New enquiry']] as const) {
      expect((await pub.post('/api/identity/public/contact', { name: `Pia ${topic} ${sfx}`, email: `pia.${topic.toLowerCase()}.${sfx}@e2e.test`, message: 'A perfectly reasonable message for you.', topic })).status).toBe(200);
      const inbox = await until(() => platform.get('/api/workflow/notifications'), (r) => titles(r).some((t) => t.includes(`Pia ${topic} ${sfx}`)));
      expect(rows(inbox).find((n: any) => String(n.title).includes(`Pia ${topic} ${sfx}`)).title).toMatch(new RegExp(`^${lead}:`));
    }
    await pub.post('/api/identity/public/contact', { name: `Bot ${sfx}`, email: `bot.${sfx}@e2e.test`, message: 'Buy cheap watches now please.', website: 'http://spam.example' });
    expect((await pub.post('/api/identity/public/contact', { name: `Bad ${sfx}`, email: 'not-an-email', message: 'This one is refused by the form.' })).status).toBe(400);
    await new Promise((r) => setTimeout(r, 4000)); // long enough for any notification to have been delivered
    const all = titles(await platform.get('/api/workflow/notifications'));
    expect(all.some((t) => t.includes(`Bot ${sfx}`) || t.includes(`Bad ${sfx}`))).toBe(false);
  });

  test('[NOTIFY-03] an enquiry on a home notifies the leasing agents, an application notifies the managers, a tour request notifies the managers', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const agent = await createSignedInUser(c.api, { email: `agent.${p.sfx}@e2e.test`, name: 'Lee Agent', roleKey: 'LEASING_AGENT', propertyIds: [p.propertyId] });
    const manager = await createSignedInUser(c.api, { email: `mgr.${p.sfx}@e2e.test`, name: 'Max Manager', roleKey: 'PROPERTY_MANAGER' });
    const pub = await Api.create();

    expect((await pub.post(`/api/leasing/public/listings/${listing}/enquiries`, { name: `Quinn ${p.sfx}`, email: `q.${p.sfx}@e2e.test`, message: 'Is the home still available this month?' })).status).toBe(200);
    const agentInbox = await until(() => agent.api.get('/api/workflow/notifications'), (r) => titles(r).some((t) => t.includes(`Quinn ${p.sfx}`)) );
    const enquiry = rows(agentInbox).find((n: any) => String(n.title).includes(`Quinn ${p.sfx}`));
    expect(enquiry.title).toMatch(/^New enquiry:|^Hot lead:/);

    expect((await apply(pub, listing, { first: 'Rae', last: `Resident ${p.sfx}`, email: `rae.${p.sfx}@e2e.test` })).status).toBe(200);
    const managerInbox = await until(() => manager.api.get('/api/workflow/notifications'), (r) => titles(r).some((t) => t.includes(`Resident ${p.sfx}`)));
    const application = rows(managerInbox).find((n: any) => String(n.title).includes(`Resident ${p.sfx}`));
    expect(application.title).toMatch(/^New application: Rae/);
    expect(application.link).toMatch(/^\/leasing\/applications\/[0-9a-f-]{36}$/);

    const when = new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 16);
    const tour = await pub.post(`/api/leasing/public/listings/${listing}/showings`, { name: `Tessa ${p.sfx}`, email: `t.${p.sfx}@e2e.test`, type: 'SELF_GUIDED', scheduledAt: `${when}:00.000Z` });
    expect(tour.status).toBe(200);
    await until(() => manager.api.get('/api/workflow/notifications'), (r) => titles(r).some((t) => /showing/i.test(t)), 40_000);
  });
});
