import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { CAN_WRITE } from '../../src/support/env';
import { onboardCompany, createSignedInUser } from '../../src/support/company';
import { buildPortfolio, publishListing, until } from '../../src/support/builders';

const ZERO = '00000000-0000-0000-0000-000000000000';

test.describe('Workflow definitions and automation rules', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  test('[WORK-01] workflow definitions can be viewed, edited, validated and reset', async () => {
    const c = await onboardCompany();
    const list = rows(await c.api.get('/api/workflow/workflows'));
    expect(list.map((w: any) => w.key)).toEqual(expect.arrayContaining(['WORK_ORDER', 'APPLICATION', 'LEASE', 'LISTING', 'SHOWING']));
    expect(list.every((w: any) => w.customised === false)).toBe(true);
    const def = (await c.api.get('/api/workflow/workflows/WORK_ORDER')).body.current;
    expect(def.states.length).toBeGreaterThan(5);
    expect((await c.api.get('/api/workflow/workflows/NOPE')).status).toBe(404);
    const edited = JSON.parse(JSON.stringify(def));
    edited.states.find((s: any) => s.key === 'ASSIGNED').label = 'Vendor booked';
    expect((await c.api.put('/api/workflow/workflows/WORK_ORDER', edited)).status).toBe(200);
    expect((await c.api.get('/api/workflow/workflows/WORK_ORDER')).body.current.states.find((s: any) => s.key === 'ASSIGNED').label).toBe('Vendor booked');
    expect(rows(await c.api.get('/api/workflow/workflows')).find((w: any) => w.key === 'WORK_ORDER').customised).toBe(true);
    const noSystem = JSON.parse(JSON.stringify(def));
    noSystem.transitions = noSystem.transitions.filter((t: any) => t.action !== 'TRIAGE');
    const bad = await c.api.put('/api/workflow/workflows/WORK_ORDER', noSystem);
    expect(bad.status).toBe(400);
    expect(String(bad.body.detail)).toMatch(/required by the platform/);
    const badState = JSON.parse(JSON.stringify(def));
    badState.initialState = 'NOWHERE';
    expect((await c.api.put('/api/workflow/workflows/WORK_ORDER', badState)).status).toBe(400);
    expect((await c.api.put('/api/workflow/workflows/NOPE', def)).status).toBe(404);
    expect((await c.api.post('/api/workflow/workflows/WORK_ORDER/reset', {})).status).toBe(200);
    expect(rows(await c.api.get('/api/workflow/workflows')).find((w: any) => w.key === 'WORK_ORDER').customised).toBe(false);
    expect((await c.api.get('/api/workflow/workflows/WORK_ORDER')).body.current.states.find((s: any) => s.key === 'ASSIGNED').label).not.toBe('Vendor booked');
  });

  test('[WORK-02] automation rules can be created, edited, toggled and deleted with validation', async () => {
    const c = await onboardCompany();
    const rule = { name: 'Tell brokers about showings', triggerEvent: 'ShowingRequested', conditions: [], actions: [{ type: 'NOTIFY', audience: 'ROLE', target: 'BROKER', title: 'New showing', body: 'A showing was requested.' }], enabled: true, minutesSaved: 5 };
    expect((await c.api.post('/api/workflow/rules', { ...rule, actions: [] })).status).toBe(400);
    expect((await c.api.post('/api/workflow/rules', { ...rule, conditions: [{ field: 'x', op: 'roughly', value: '1' }] })).status).toBe(400);
    expect((await c.api.post('/api/workflow/rules', { ...rule, name: '' })).status).toBe(400);
    expect((await c.api.post('/api/workflow/rules', { ...rule, triggerEvent: '' })).status).toBe(400);
    const made = await c.api.post('/api/workflow/rules', rule);
    expect(made.status).toBe(200);
    const id = made.body.id;
    expect(made.body.enabled).toBe(true);
    expect((await c.api.put(`/api/workflow/rules/${id}`, { ...rule, name: 'Renamed rule', minutesSaved: 9 })).status).toBe(200);
    expect((await c.api.get(`/api/workflow/rules/${id}`)).body.name).toBe('Renamed rule');
    expect((await c.api.post(`/api/workflow/rules/${id}/toggle`, { enabled: false })).status).toBe(200);
    expect((await c.api.get(`/api/workflow/rules/${id}`)).body.enabled).toBe(false);
    expect((await c.api.post(`/api/workflow/rules/${id}/toggle`, { enabled: true })).status).toBe(200);
    expect((await c.api.get(`/api/workflow/rules/${id}`)).body.enabled).toBe(true);
    expect((await c.api.post(`/api/workflow/rules/${ZERO}/toggle`, { enabled: true })).status).toBe(404);
    expect((await c.api.put(`/api/workflow/rules/${ZERO}`, rule)).status).toBe(404);
    expect((await c.api.del(`/api/workflow/rules/${id}`)).status).toBe(200);
    expect(rows(await c.api.get('/api/workflow/rules')).some((r: any) => r.id === id)).toBe(false);
    const summary = (await c.api.get('/api/workflow/automation/summary')).body;
    expect(summary).toHaveProperty('active_rules');
    expect((await (await apiAs('tenant')).get('/api/workflow/rules')).status).toBe(403);
  });

  test('[WORK-03] a rule runs when its trigger event happens, and the run is recorded', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    const mgr = await c.api.post('/api/workflow/rules', { name: 'Tell admins about showings', triggerEvent: 'ShowingRequested', conditions: [{ field: 'listingId', op: 'eq', value: live }], actions: [{ type: 'NOTIFY', audience: 'ROLE', target: 'ORG_ADMIN', title: 'New showing', body: 'Please confirm.' }], enabled: true });
    const skip = await c.api.post('/api/workflow/rules', { name: 'Never matches', triggerEvent: 'ShowingRequested', conditions: [{ field: 'listingId', op: 'eq', value: ZERO }], actions: [{ type: 'NOTIFY', audience: 'ROLE', target: 'ORG_ADMIN', title: 'Nope', body: '' }], enabled: true });
    const off = await c.api.post('/api/workflow/rules', { name: 'Disabled', triggerEvent: 'ShowingRequested', conditions: [], actions: [{ type: 'NOTIFY', audience: 'ROLE', target: 'ORG_ADMIN', title: 'Off', body: '' }], enabled: false });
    const before = rows(await c.api.get('/api/workflow/notifications')).length;
    const pub = await Api.create();
    expect((await pub.post(`/api/leasing/public/listings/${live}/showings`, { name: 'Sam', email: `sam.${p.sfx}@e2e.test`, scheduledAt: new Date(Date.now() + 3 * 86400000).toISOString() })).status).toBe(200);
    const ran = await until(() => c.api.get(`/api/workflow/rules/${mgr.body.id}`), (r) => r.body.recentRuns.length > 0);
    expect(ran.body.recentRuns[0].status).toBe('SUCCEEDED');
    expect(ran.body.runs).toBe(1);
    expect((await c.api.get(`/api/workflow/rules/${skip.body.id}`)).body.runs).toBe(0);
    expect((await c.api.get(`/api/workflow/rules/${off.body.id}`)).body.runs).toBe(0);
    const inbox = await until(() => c.api.get('/api/workflow/notifications'), (r) => rows(r).length > before);
    expect(rows(inbox).some((n: any) => n.title === 'New showing')).toBe(true);
    const events = await c.api.get('/api/workflow/automation/events');
    expect(events.status).toBe(200);
    expect(JSON.stringify(events.body)).toContain('ShowingRequested');
  });

  test('[WORK-04] a new company receives default automation rules', async () => {
    const c = await onboardCompany();
    const rules = await until(() => c.api.get('/api/workflow/rules'), (r) => rows(r).length > 0, 40_000);
    expect(rows(rules).length).toBeGreaterThanOrEqual(3);
    expect(rows(rules).every((r: any) => r.name && r.trigger_event)).toBe(true);
    const harbor = rows(await (await apiAs('harbor')).get('/api/workflow/rules')).map((r: any) => r.id);
    expect(rows(rules).some((r: any) => harbor.includes(r.id))).toBe(false);
  });
});

test.describe('Chat', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  async function team() {
    const c = await onboardCompany();
    const a = await createSignedInUser(c.api, { email: `a.${c.sfx}@e2e.test`, name: 'Amy Agent', roleKey: 'LEASING_AGENT' });
    const b = await createSignedInUser(c.api, { email: `b.${c.sfx}@e2e.test`, name: 'Ben Broker', roleKey: 'BROKER' });
    const d = await createSignedInUser(c.api, { email: `d.${c.sfx}@e2e.test`, name: 'Dee Accountant', roleKey: 'ACCOUNTANT' });
    return { c, a, b, d };
  }

  test('[CHAT-01] contacts and conversations can be listed', async () => {
    const { c, a } = await team();
    const contacts = rows(await c.api.get('/api/workflow/chat/contacts'));
    expect(contacts.map((x: any) => x.name)).toEqual(expect.arrayContaining(['Amy Agent', 'Ben Broker', 'Dee Accountant']));
    expect(contacts.some((x: any) => x.name === 'Ada Admin')).toBe(false);
    expect((await a.api.get('/api/workflow/chat/conversations')).status).toBe(200);
    const demo = rows(await (await apiAs('manager')).get('/api/workflow/chat/contacts'));
    expect(demo.some((x: any) => x.name === 'Amy Agent')).toBe(false);
  });

  test('[CHAT-02] a conversation needs a subject, a message and people', async () => {
    const { c, a } = await team();
    const me = rows(await c.api.get('/api/workflow/chat/contacts')).find((x: any) => x.name === 'Amy Agent').id;
    expect((await c.api.post('/api/workflow/chat/conversations', { subject: '', body: 'hi', memberIds: [me] })).status).toBe(400);
    expect((await c.api.post('/api/workflow/chat/conversations', { subject: 'Hello', body: '', memberIds: [me] })).status).toBe(400);
    expect((await c.api.post('/api/workflow/chat/conversations', { subject: 'Hello', body: 'hi', memberIds: [] })).status).toBe(400);
    expect((await c.api.post('/api/workflow/chat/conversations', { subject: 'Hello', body: 'hi', memberIds: [me], contextType: 'MARTIAN' })).status).toBe(400);
    const ok = await c.api.post('/api/workflow/chat/conversations', { subject: 'Weekly sync', body: 'Agenda attached.', memberIds: [me] });
    expect(ok.status).toBe(200);
    expect(rows(await a.api.get('/api/workflow/chat/conversations')).some((x: any) => x.id === ok.body.id)).toBe(true);
    expect((await c.api.post(`/api/workflow/chat/conversations/${ok.body.id}/messages`, { body: 'x'.repeat(5000) })).status).toBe(400);
    expect((await c.api.post(`/api/workflow/chat/conversations/${ok.body.id}/messages`, { body: '  ' })).status).toBe(400);
  });

  test('[CHAT-03] members can reply, resolve and request an AI assist', async () => {
    const { c, a } = await team();
    const amy = rows(await c.api.get('/api/workflow/chat/contacts')).find((x: any) => x.name === 'Amy Agent').id;
    const conv = (await c.api.post('/api/workflow/chat/conversations', { subject: 'Unit 1A keys', body: 'Who has the keys?', memberIds: [amy] })).body.id;
    expect((await a.api.post(`/api/workflow/chat/conversations/${conv}/messages`, { body: 'I have them.' })).status).toBe(200);
    const view = (await c.api.get(`/api/workflow/chat/conversations/${conv}`)).body;
    expect(JSON.stringify(view)).toContain('I have them.');
    const assist = await c.api.post(`/api/workflow/chat/conversations/${conv}/assist`, {});
    expect(assist.status).toBe(200);
    expect(JSON.stringify(assist.body).length).toBeGreaterThan(10);
    expect((await a.api.post(`/api/workflow/chat/conversations/${conv}/resolve`, { resolved: true })).status).toBe(200);
    expect((await c.api.get(`/api/workflow/chat/conversations/${conv}`)).body.status).toMatch(/RESOLVED|CLOSED/);
    expect((await c.api.get(`/api/workflow/chat/conversations/${ZERO}`)).status).toBe(404);
  });

  test('[CHAT-04] a person sees only conversations they belong to', async () => {
    const { c, a, b, d } = await team();
    const amy = rows(await c.api.get('/api/workflow/chat/contacts')).find((x: any) => x.name === 'Amy Agent').id;
    const conv = (await c.api.post('/api/workflow/chat/conversations', { subject: 'Private', body: 'Only us two.', memberIds: [amy] })).body.id;
    expect((await a.api.get(`/api/workflow/chat/conversations/${conv}`)).status).toBe(200);
    for (const outsider of [b, d]) {
      expect((await outsider.api.get(`/api/workflow/chat/conversations/${conv}`)).status).toBe(404);
      expect(rows(await outsider.api.get('/api/workflow/chat/conversations')).some((x: any) => x.id === conv)).toBe(false);
      expect((await outsider.api.post(`/api/workflow/chat/conversations/${conv}/messages`, { body: 'hello' })).status).toBeGreaterThanOrEqual(403);
    }
    const harbor = await apiAs('harbor');
    expect((await harbor.get(`/api/workflow/chat/conversations/${conv}`)).status).toBe(404);
  });
});

test.describe('Notifications', () => {
  test.skip(!CAN_WRITE, 'creates a company');

  async function withNote() {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const live = await publishListing(c.api, p.unitId);
    await c.api.post('/api/workflow/rules', { name: 'Ping admins', triggerEvent: 'ShowingRequested', conditions: [], actions: [{ type: 'NOTIFY', audience: 'ROLE', target: 'ORG_ADMIN', title: 'Showing asked', body: 'Please confirm.' }], enabled: true });
    await (await Api.create()).post(`/api/leasing/public/listings/${live}/showings`, { name: 'Sam', email: `sam.${p.sfx}@e2e.test`, scheduledAt: new Date(Date.now() + 3 * 86400000).toISOString() });
    await until(() => c.api.get('/api/workflow/notifications'), (r) => rows(r).some((n: any) => n.title === 'Showing asked'), 40_000);
    return c;
  }

  test('[NOTE-01] notifications list, mark read and mark all read', async () => {
    const c = await withNote();
    const list = rows(await c.api.get('/api/workflow/notifications'));
    const target = list.find((n: any) => n.title === 'Showing asked');
    expect(target, 'the rule produced a notification').toBeTruthy();
    expect(target.read_at ?? null).toBeNull();
    expect((await c.api.post(`/api/workflow/notifications/${target.id}/read`, {})).status).toBe(200);
    expect(rows(await c.api.get('/api/workflow/notifications')).find((n: any) => n.id === target.id).read_at).toBeTruthy();
    expect((await c.api.post('/api/workflow/notifications/read-all', {})).status).toBe(200);
    expect(rows(await c.api.get('/api/workflow/notifications')).every((n: any) => n.read_at)).toBe(true);
    const harbor = rows(await (await apiAs('harbor')).get('/api/workflow/notifications'));
    expect(harbor.some((n: any) => n.id === target.id)).toBe(false);
  });

  test('[NOTE-02] preferences can be read and changed', async () => {
    const c = await onboardCompany();
    const prefs = (await c.api.get('/api/workflow/notifications/preferences')).body;
    expect(prefs.categories.length).toBeGreaterThan(2);
    const cat = prefs.categories[0].key;
    expect(prefs.categories[0].email).toBe(true);
    expect((await c.api.put('/api/workflow/notifications/preferences', [{ key: cat, inApp: true, email: false, sms: false }])).status).toBe(200);
    const after = (await c.api.get('/api/workflow/notifications/preferences')).body;
    const row = after.categories.find((x: any) => x.key === cat);
    expect(row.email).toBe(false);
    expect(row.inApp).toBe(true);
    expect(after.categories.filter((x: any) => x.key !== cat).every((x: any) => x.email === true)).toBe(true);
  });

  test('[NOTE-03] the delivery log is available to automation viewers only', async () => {
    const c = await onboardCompany();
    expect((await c.api.get('/api/workflow/deliveries')).status).toBe(200);
    expect((await (await apiAs('tenant')).get('/api/workflow/deliveries')).status).toBe(403);
    expect((await (await apiAs('vendor')).get('/api/workflow/deliveries')).status).toBe(403);
  });
});
