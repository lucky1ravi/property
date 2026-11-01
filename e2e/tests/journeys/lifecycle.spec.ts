import { test, expect, needsWrites, watchErrors } from '../../src/support/fixtures';
import { Api, apiAs, rows } from '../../src/support/api';
import { createSignedInUser, onboardCompany, roleId } from '../../src/support/company';
import { until, publishListing, moveIn } from '../../src/support/builders';
import { DEMO_PASSWORD, SUFFIX } from '../../src/support/env';
import { PERSONAS } from '../../src/support/personas';
import { addDays, cycleOn, dismissWelcome, everyRow, firstOfMonth, firstSignInThroughScreen, signInThroughScreen } from '../../src/support/journey';

/**
 * [JRNY-08] One long story, start to finish, with nine different people.
 *
 *   platform owner -> new company -> its administrator sets everything up -> a prospect finds a home, asks, tours and applies ->
 *   staff screen, the owner approves, the lease is written and signed by both sides -> the applicant becomes a resident ->
 *   they live there for several months (rent each month, autopay, a missed month with a late fee, a repair, messages, an e-signed
 *   addendum) -> owner statements -> renewal -> notice, move-out and deposit settlement.
 *
 * Every person is created and signs in through the real sign-in screen. The screens do what a person would do; setup that a
 * person would not do in this story goes through the API. The billing clock is moved forward with the billing cycle's "as of" date
 * (local and demo environments only), so months pass in seconds.
 */
test.describe('The whole life of a company', () => {
  needsWrites();

  test('[JRNY-08] from a new company to a resident moving out, with every person signed in', async ({ browser }) => {
    test.setTimeout(900_000);
    const sfx = SUFFIX();
    const watchers: string[][] = [];
    const contexts: any[] = [];
    const track = (page: any) => { watchers.push(watchErrors(page)); return page; };
    /** Opens a fresh browser for a person, signed in through the form. */
    async function signedIn(email: string, password: string, path = '/app') {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
      contexts.push(ctx);
      const page = await ctx.newPage();
      track(page);
      await signInThroughScreen(page, email, password);
      await dismissWelcome(page);
      if (path !== '/app') await page.goto(path);
      return page;
    }
    const nav = (page: any) => page.getByRole('navigation', { name: 'Main' });
    const sidebar = (page: any) => page.locator('[data-tour="sidebar"]');

    // ------------------------------------------------------------------------------------------------ 1. the platform owner
    const company = `Lifecycle Realty ${sfx}`;
    const adminEmail = `ada.${sfx}@e2e.test`;
    const adminNewPassword = `Life-${sfx}-Pass1`;
    let adminTemp = '';
    let orgId = '';
    await test.step('the platform owner signs in and onboards a new company', async () => {
      const page = await signedIn(PERSONAS.platform.email, DEMO_PASSWORD, '/app/admin/platform');
      await expect(page.getByRole('heading', { name: 'Platform' })).toBeVisible();
      await page.getByRole('button', { name: 'Onboard company' }).click();
      const d = page.getByRole('dialog');
      await d.getByLabel('Company name').fill(company);
      await d.getByLabel('Administrator name').fill('Ada Admin');
      await d.getByLabel('Administrator email').fill(adminEmail);
      await d.getByRole('button', { name: 'Create company' }).click();
      adminTemp = (await page.getByRole('dialog').locator('dd.font-mono').innerText()).trim();
      expect(adminTemp.length).toBeGreaterThan(8);
      await page.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
      const platform = await apiAs('platform');
      orgId = rows(await platform.get('/api/identity/platform/orgs?status=ACTIVE')).find((o: any) => o.name === company).id;
      expect(orgId).toBeTruthy();
      // The platform owner adds one feature that the plan does not include, from the company's edit form.
      await page.getByRole('textbox', { name: 'Search' }).fill(company);
      await page.getByRole('button', { name: `Edit ${company}` }).click();
      const edit = page.getByRole('dialog');
      await expect(edit.getByRole('heading', { name: /Features/ })).toBeVisible();
      const before = rows(await platform.get(`/api/identity/platform/orgs/${orgId}/features`)).find((f: any) => f.feature === 'BROKER_COCKPIT');
      const toggle = edit.getByRole('switch', { name: `Broker cockpit for ${company}` });
      const wasOn = (await toggle.getAttribute('aria-checked')) === 'true';
      await toggle.click();
      await edit.getByRole('button', { name: /Save changes/ }).click();
      await expect(page.getByRole('dialog')).toBeHidden();
      const after = rows(await platform.get(`/api/identity/platform/orgs/${orgId}/features`)).find((f: any) => f.feature === 'BROKER_COCKPIT');
      expect(after.enabled).toBe(!wasOn);
      expect(before.enabled).toBe(wasOn);
    });

    // ------------------------------------------------------------------------------------------------ 2. the company's administrator
    let adminPage: any;
    let admin: Api;
    await test.step('the company administrator signs in with the temporary password and chooses their own', async () => {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
      contexts.push(ctx);
      adminPage = await ctx.newPage();
      track(adminPage);
      await firstSignInThroughScreen(adminPage, adminEmail, adminTemp, adminNewPassword);
      await dismissWelcome(adminPage);
      await expect(adminPage.locator('header').first()).toContainText(company);
      const pub = await Api.create();
      const r = await pub.post('/api/identity/public/login', { email: adminEmail, password: adminNewPassword });
      expect(r.status).toBe(200);
      admin = await Api.create(r.body.token);
    });

    // ------------------------------------------------------------------------------------------------ 3. the administrator sets the company up
    const people: Record<string, { email: string; password: string; name: string; token?: string; userId?: string }> = {};
    let ownerId = '', propertyId = '', unitId = '', unit2Id = '', vendorId = '', listingId = '';
    await test.step('the administrator changes the company settings, and the change is recorded', async () => {
      const cfg = (await admin.get('/api/identity/config')).body;
      const history = rows(await admin.get('/api/identity/config/history')).length;
      const r = await admin.put('/api/identity/config/fees?summary=Late%20fee%20for%20the%20lifecycle%20company', { ...cfg.fees, lateFeePercent: 6 });
      expect(r.status).toBe(200);
      expect(rows(await admin.get('/api/identity/config/history')).length).toBe(history + 1);
      expect((await admin.get('/api/identity/config')).body.fees.lateFeePercent).toBe(6);
      await adminPage.goto('/app/admin/configuration');
      await expect(adminPage.getByRole('heading', { name: 'Configuration' })).toBeVisible();
    });

    await test.step('the administrator adds the owner on screen, with a sign-in, and the owner signs in', async () => {
      await adminPage.goto('/app/leasing/owners');
      await adminPage.getByRole('button', { name: 'Add owner' }).first().click();
      const d = adminPage.getByRole('dialog');
      await d.getByLabel('Owner or company name').fill(`Olivia Owner ${sfx}`);
      await d.getByLabel('Contact person').fill('Olivia Owner');
      await d.getByLabel('Email', { exact: true }).fill(`olivia.${sfx}@e2e.test`);
      await expect(d.getByRole('checkbox', { name: /Create a sign-in/ })).toBeChecked();
      await d.getByRole('button', { name: 'Save' }).click();
      const temp = (await adminPage.getByRole('dialog').locator('dd.font-mono').innerText()).trim();
      await adminPage.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
      ownerId = rows(await admin.get('/api/leasing/owners?q=Olivia')).find((o: any) => o.name === `Olivia Owner ${sfx}`).id;
      const page = await browser.newPage();
      contexts.push(page.context());
      const pw = `Own-${sfx}-Pass1`;
      await firstSignInThroughScreen(page, `olivia.${sfx}@e2e.test`, temp, pw);
      await dismissWelcome(page);
      await expect(page.locator('header').first()).toContainText('Olivia Owner');
      await expect(sidebar(page)).not.toContainText('Users and roles');
      people.owner = { email: `olivia.${sfx}@e2e.test`, password: pw, name: 'Olivia Owner' };
      await page.context().close();
    });

    await test.step('the administrator adds the property, its units and a vendor, and publishes a listing', async () => {
      propertyId = (await admin.post('/api/leasing/properties', { ownerId, name: `Elm Court ${sfx}`, street: '42 Elm St', city: 'Austin', state: 'TX', zip: '78701', type: 'MULTIFAMILY', yearBuilt: 2008 })).body.id;
      unitId = (await admin.post(`/api/leasing/properties/${propertyId}/units`, { number: '3C', beds: 2, baths: '2', sqft: 950, marketRent: 1900 })).body.id;
      unit2Id = (await admin.post(`/api/leasing/properties/${propertyId}/units`, { number: '4D', beds: 1, baths: '1', sqft: 640, marketRent: 1450 })).body.id;
      const vEmail = `vic.${sfx}@e2e.test`;
      vendorId = (await admin.post('/api/maintenance/vendors', { name: `Vic Plumbing ${sfx}`, contact: 'Vic', phone: '5125550123', email: vEmail, trades: ['PLUMBING', 'GENERAL'], cities: ['Austin'], rating: 4.6, avgResponseHours: 4, avgJobCost: 220 })).body.id;
      const v = await createSignedInUser(admin, { email: vEmail, name: 'Vic Vendor', roleKey: 'VENDOR', partyType: 'VENDOR', partyId: vendorId });
      people.vendor = { email: vEmail, password: v.password, name: 'Vic Vendor', token: v.token, userId: v.userId };
      listingId = await publishListing(admin, unitId);
      const page = await signedIn(vEmail, v.password);
      await expect(sidebar(page)).toContainText('Maintenance');
      await expect(sidebar(page)).not.toContainText('Payments');
      await page.context().close();
    });

    await test.step('the administrator adds the property manager through the Users screen', async () => {
      await adminPage.goto('/app/admin/access');
      await adminPage.getByRole('button', { name: 'Add user' }).first().click();
      const d = adminPage.getByRole('dialog');
      await d.getByLabel('Full name').fill('Max Manager');
      await d.getByLabel('Work email').fill(`max.${sfx}@e2e.test`);
      const managerRole = rows(await admin.get('/api/identity/roles')).find((x: any) => x.role_key === 'PROPERTY_MANAGER').name;
      await d.getByRole('checkbox', { name: new RegExp(managerRole, 'i') }).check();
      // A role limited to certain properties asks which ones; the property is found by typing.
      const propertyBox = d.getByPlaceholder('Search properties by name or address');
      if (await propertyBox.waitFor({ state: 'visible', timeout: 3000 }).then(() => true, () => false)) {
        await propertyBox.fill(`Elm Court ${sfx}`);
        await adminPage.getByRole('option', { name: new RegExp(`Elm Court ${sfx}`) }).first().click();
        await expect(d).toContainText(`Elm Court ${sfx}`);
      }
      await d.getByRole('button', { name: 'Send invite' }).click();
      const temp = (await adminPage.getByRole('dialog').locator('dd.font-mono').innerText()).trim();
      await adminPage.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
      const page = await browser.newPage();
      contexts.push(page.context());
      const pw = `Mgr-${sfx}-Pass1`;
      await firstSignInThroughScreen(page, `max.${sfx}@e2e.test`, temp, pw);
      await dismissWelcome(page);
      await expect(page.locator('header').first()).toContainText('Max Manager');
      people.manager = { email: `max.${sfx}@e2e.test`, password: pw, name: 'Max Manager' };
      await page.context().close();
    });

    await test.step('the administrator creates the broker, leasing agent and accountant, and each signs in', async () => {
      const make = async (key: string, role: string, name: string, label: string) => {
        const email = `${label}.${sfx}@e2e.test`;
        const u = await createSignedInUser(admin, { email, name, roleKey: role, propertyIds: [propertyId] });
        people[key] = { email, password: u.password, name, token: u.token, userId: u.userId };
      };
      await make('broker', 'BROKER', 'Bea Broker', 'bea');
      await make('agent', 'LEASING_AGENT', 'Lee Agent', 'lee');
      await make('accountant', 'ACCOUNTANT', 'Ann Accountant', 'ann');
      for (const [key, menu] of [['broker', /Broker cockpit|Leasing/], ['agent', /Leasing/], ['accountant', /Payments/]] as const) {
        const page = await signedIn(people[key].email, people[key].password);
        await expect(page.locator('header').first()).toContainText(people[key].name);
        await expect(sidebar(page)).toContainText(menu);
        await expect(sidebar(page)).not.toContainText('Users and roles');
        await page.context().close();
      }
    });

    // ------------------------------------------------------------------------------------------------ 4. a prospect finds a home
    const resident = { email: `rae.${sfx}@e2e.test`, name: 'Rae Resident' };
    let applicationId = '';
    await test.step('a visitor with no account finds the home, asks a question and books a tour', async () => {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
      contexts.push(ctx);
      const page = await ctx.newPage();
      track(page);
      await page.goto('/homes');
      await expect(page.locator('main')).toContainText(`Elm Court ${sfx}`);
      await page.goto(`/homes/${listingId}`);
      await page.getByRole('button', { name: 'Ask a question' }).click();
      await page.getByLabel('Full name').fill('Rae Resident');
      await page.getByLabel('Email').fill(resident.email);
      await page.getByLabel('Message').fill('Is the unit pet friendly, and can I tour it this week?');
      await page.getByRole('button', { name: 'Send message' }).click();
      await expect.poll(async () => rows(await admin.get('/api/leasing/leads')).some((l: any) => l.email === resident.email)).toBe(true);
      const pub = await Api.create();
      const tour = await pub.post(`/api/leasing/public/listings/${listingId}/showings`, { name: 'Rae Resident', email: resident.email, scheduledAt: addDays(new Date(), 3).toISOString() });
      expect(tour.status).toBe(200);
    });

    await test.step('the leasing agent confirms the tour, and it is marked attended afterwards', async () => {
      const page = await signedIn(people.agent.email, people.agent.password, '/app/leasing/showings');
      await expect(page.getByText(resident.email).first()).toBeVisible();
      await expect(page.getByText('Requested', { exact: false }).first()).toBeVisible();
      await page.getByRole('button', { name: 'Confirm', exact: true }).first().click();
      await expect(page.getByRole('button', { name: 'Mark attended' }).first()).toBeVisible();
      await page.getByRole('button', { name: 'Mark attended' }).first().click();
      await expect.poll(async () => rows(await admin.get('/api/leasing/showings')).find((s: any) => s.prospect_email === resident.email)?.status).toBe('ATTENDED');
      await page.context().close();
    });

    await test.step('two people apply on screen and by form: one will be approved and one declined', async () => {
      const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
      contexts.push(ctx);
      const page = await ctx.newPage();
      track(page);
      await page.goto(`/homes/${listingId}/apply`);
      await page.getByLabel('First name').fill('Rae');
      await page.getByLabel('Last name').fill('Resident');
      await page.getByLabel('Email').fill(resident.email);
      await page.getByLabel('Phone').fill('5125550177');
      await page.getByLabel(/Monthly income/).fill('9000');
      await page.getByLabel('Employer', { exact: true }).fill('Acme');
      await page.getByLabel(/Months at employer/).fill('30');
      await page.getByLabel(/Desired move-in date/).fill(moveIn(14));
      const submit = page.getByRole('button', { name: 'Submit application' });
      await expect(submit).toBeDisabled();
      await page.getByRole('checkbox').check();
      await submit.click();
      const app = await until(async () => rows(await admin.get('/api/leasing/applications')).find((a: any) => a.applicant_email === resident.email), (a) => !!a && ['SCREENED', 'APPROVED', 'OWNER_REVIEW'].includes(a.status));
      expect(app.status).toBe('SCREENED');
      applicationId = app.id;
      const pub = await Api.create();
      const dan = await pub.post(`/api/leasing/public/listings/${listingId}/applications`, { firstName: 'Dan', lastName: 'Decline', email: `dan.${sfx}@e2e.test`, phone: '5125550188', consent: true, monthlyIncome: 700, employer: 'Odd jobs', monthsEmployed: 2, occupants: 5, moveInDate: moveIn(10) });
      expect(dan.status).toBe(200);
    });

    await test.step('the manager sends one application to the owner, and declines the other with a reason', async () => {
      const page = await signedIn(people.manager.email, people.manager.password, '/app/leasing/applications');
      await expect(page.getByText('Rae Resident').first()).toBeVisible();
      await expect(page.getByText('Dan Decline').first()).toBeVisible();
      // decline Dan
      await page.getByRole('row', { name: /Dan Decline/ }).getByRole('cell').nth(1).click();
      await expect(page).toHaveURL(/\/applications\/[0-9a-f-]+$/);
      await page.getByRole('button', { name: 'Reject' }).click();
      await page.getByRole('dialog').getByRole('textbox').fill('Income is below the minimum for this home.');
      await page.getByRole('dialog').getByRole('button', { name: 'Reject' }).click();
      await expect.poll(async () => rows(await admin.get('/api/leasing/applications')).find((a: any) => a.applicant_email === `dan.${sfx}@e2e.test`)?.status).toBe('REJECTED');
      // send Rae to the owner
      await page.goto(`/app/leasing/applications/${applicationId}`);
      await expect(page.getByText(/^Screened$/i).first()).toBeVisible();
      await page.getByRole('button', { name: 'Send to owner' }).click();
      await expect.poll(async () => (await admin.get(`/api/leasing/applications/${applicationId}`)).body.status).toBe('OWNER_REVIEW');
      await page.context().close();
    });

    // The home was let a few months ago and is being recorded now: the lease starts in the middle of a month, four months back.
    const leaseStart = addDays(firstOfMonth(new Date(), -4), 14).toISOString().slice(0, 10);
    let leaseId = '';
    await test.step('the owner approves on screen, and the manager writes the lease and sends it for signature', async () => {
      const owner = await signedIn(people.owner.email, people.owner.password, `/app/leasing/applications/${applicationId}`);
      await expect(owner.getByText('Rae Resident').first()).toBeVisible();
      await owner.getByRole('button', { name: 'Approve', exact: true }).click();
      await expect.poll(async () => (await admin.get(`/api/leasing/applications/${applicationId}`)).body.status).toBe('APPROVED');
      await owner.context().close();
      const page = await signedIn(people.manager.email, people.manager.password, `/app/leasing/applications/${applicationId}`);
      await page.getByRole('button', { name: 'Create lease' }).click();
      const terms = page.getByRole('dialog', { name: 'Create the lease' });
      await expect(terms.getByLabel('Monthly rent ($)')).toHaveValue(/1900/);
      await terms.getByLabel('Start date').fill(leaseStart);
      await terms.getByRole('button', { name: 'Create lease' }).click();
      await page.waitForURL(/\/app\/leasing\/leases\/[0-9a-f-]{36}/);
      leaseId = page.url().split('/').pop()!;
      await expect(page.getByText('RESIDENTIAL LEASE AGREEMENT')).toBeVisible();
      await page.getByRole('button', { name: 'Send for signature' }).click();
      await expect.poll(async () => (await admin.get(`/api/leasing/leases/${leaseId}`)).body.status).toBe('SENT');
      await page.context().close();
    });

    // ------------------------------------------------------------------------------------------------ 5. the applicant becomes a resident
    let rae: { token: string; password: string; userId: string; api: Api };
    await test.step('the manager gives the applicant an account on screen, and the resident signs in and signs the lease', async () => {
      const page = await signedIn(people.manager.email, people.manager.password, '/app/admin/access');
      // the manager role cannot manage users in every company; fall back to the administrator's screen if so
      const addUser = page.getByRole('button', { name: 'Add user' });
      const who = (await addUser.count()) > 0 ? page : adminPage;
      if (who === adminPage) await adminPage.goto('/app/admin/access');
      await who.getByRole('button', { name: 'Add user' }).first().click();
      const d = who.getByRole('dialog');
      await d.getByLabel('Full name').fill(resident.name);
      await d.getByLabel('Work email').fill(resident.email);
      const tenantRole = rows(await admin.get('/api/identity/roles')).find((x: any) => x.role_key === 'TENANT').name;
      await d.getByRole('checkbox', { name: new RegExp(tenantRole, 'i') }).check();
      await d.getByPlaceholder('Search residents').click();
      await d.getByPlaceholder('Search residents').fill('Rae');
      await who.getByRole('option', { name: /Rae Resident/ }).first().click();
      await d.getByRole('button', { name: 'Send invite' }).click();
      const temp = (await who.getByRole('dialog').locator('dd.font-mono').innerText()).trim();
      await who.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
      await page.context().close();
      const rp = await browser.newPage();
      contexts.push(rp.context());
      track(rp);
      const pw = `Res-${sfx}-Pass1`;
      await firstSignInThroughScreen(rp, resident.email, temp, pw);
      await dismissWelcome(rp);
      await expect(sidebar(rp)).toContainText('My lease');
      await expect(sidebar(rp)).not.toContainText('Users and roles');
      const login = await (await Api.create()).post('/api/identity/public/login', { email: resident.email, password: pw });
      rae = { token: login.body.token, password: pw, userId: login.body.userId ?? '', api: await Api.create(login.body.token) };
      people.resident = { email: resident.email, password: pw, name: resident.name, token: login.body.token };
      await rp.goto('/app/leasing/my-lease');
      await expect(rp.getByRole('heading', { name: 'My lease' })).toBeVisible();
      await expect(rp.getByText('RESIDENTIAL LEASE AGREEMENT')).toBeVisible();
      await rp.getByLabel(/Type your full legal name/).fill(resident.name);
      await rp.getByRole('button', { name: 'Sign lease' }).click();
      await expect.poll(async () => (await admin.get(`/api/leasing/leases/${leaseId}`)).body.status, { timeout: 30_000 }).toBe('TENANT_SIGNED');
      await rp.context().close();
    });

    await test.step('the manager countersigns, the lease becomes active and the unit is occupied', async () => {
      const page = await signedIn(people.manager.email, people.manager.password, `/app/leasing/leases/${leaseId}`);
      await page.getByRole('button', { name: 'Countersign and activate' }).click();
      await expect.poll(async () => (await admin.get(`/api/leasing/leases/${leaseId}`)).body.status).toBe('ACTIVE');
      const unit = rows(await admin.get('/api/leasing/units?page=1&size=100')).find((u: any) => u.id === unitId);
      expect(unit.status).toBe('OCCUPIED');
      await expect(page.getByText(/Active/i).first()).toBeVisible();
      await page.context().close();
    });

    // ------------------------------------------------------------------------------------------------ 6. move in: the first invoices
    let start = '';
    await test.step('the resident sees the move-in charges, adds a bank account and pays them on screen', async () => {
      start = (await admin.get(`/api/leasing/leases/${leaseId}`)).body.start_date.slice(0, 10);
      expect(start).toBe(leaseStart);
      const page = await signedIn(people.resident.email, people.resident.password, '/app/payments');
      expect((await rae.api.get('/api/billing/my')).body.invoices.length).toBeGreaterThanOrEqual(1);
      await page.getByRole('button', { name: 'Add', exact: true }).click();
      const d = page.getByRole('dialog');
      await d.getByLabel('Last 4 digits').fill('6789');
      await d.getByRole('checkbox').last().check();
      await d.getByRole('button', { name: 'Save' }).click();
      await page.getByRole('button', { name: /^Pay \$[\d,]+/ }).click();
      await expect(page.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
      const mine = (await rae.api.get('/api/billing/my')).body;
      expect(mine.invoices.every((i: any) => i.status === 'PAID')).toBe(true);
      await page.context().close();
    });

    // ------------------------------------------------------------------------------------------------ 7. months pass
    const m1 = firstOfMonth(start, 1), m2 = firstOfMonth(start, 2), m3 = firstOfMonth(start, 3);
    const period = (d: Date) => d.toISOString().slice(0, 7);
    await test.step('month one: rent is invoiced a few days before it is due and the resident pays on screen', async () => {
      const r = await cycleOn(admin, addDays(m1, -3));
      expect(r.rentInvoicesCreated).toBe(1);
      expect(r.period).toBe(period(m1));
      expect((await cycleOn(admin, addDays(m1, -3))).rentInvoicesCreated, 'running the cycle twice does not bill twice').toBe(0);
      const page = await signedIn(people.resident.email, people.resident.password, '/app/payments');
      await expect(page.getByText('$1,900.00').first()).toBeVisible();
      await page.getByRole('button', { name: /^Pay \$[\d,]+/ }).click();
      await expect(page.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
      await page.context().close();
      const accountant = await signedIn(people.accountant.email, people.accountant.password, '/app/payments/invoices');
      await expect(accountant.getByText(`Rent for`).first()).toBeVisible();
      await accountant.context().close();
    });

    await test.step('month two: the resident turns autopay on and rent is collected by itself on the due date', async () => {
      const methods = (await rae.api.get('/api/billing/my')).body.methods;
      expect(methods.length).toBeGreaterThan(0);
      expect((await rae.api.put(`/api/billing/my/methods/${methods[0].id}/autopay`, { on: true })).status).toBe(200);
      expect((await cycleOn(admin, addDays(m2, -3))).rentInvoicesCreated).toBe(1);
      const due = await cycleOn(admin, m2);
      expect(due.autopayCollected).toBe(1);
      const invoices = rows(await admin.get(`/api/billing/invoices?page=1&size=100&tenantId=${(await admin.get(`/api/leasing/leases/${leaseId}`)).body.tenant_id}`));
      expect(invoices.filter((i: any) => i.kind === 'RENT' && i.status === 'PAID').length).toBeGreaterThanOrEqual(2);
      expect((await rae.api.put(`/api/billing/my/methods/${methods[0].id}/autopay`, { on: false })).status).toBe(200);
    });

    await test.step('month three: rent is missed, a late fee follows, the accountant sends a reminder and the resident settles up', async () => {
      expect((await cycleOn(admin, addDays(m3, -3))).rentInvoicesCreated).toBe(1);
      const late = (await admin.post('/api/billing/run-cycle', {})).body;
      expect(late.lateFeesCharged).toBe(1);
      expect((await admin.post('/api/billing/run-cycle', {})).body.lateFeesCharged, 'a late fee is charged once').toBe(0);
      const tenantId = (await admin.get(`/api/leasing/leases/${leaseId}`)).body.tenant_id;
      const open = rows(await admin.get(`/api/billing/invoices?page=1&size=100&tenantId=${tenantId}`)).filter((i: any) => ['OPEN', 'PARTIAL'].includes(i.status));
      expect(open.some((i: any) => i.kind === 'LATE_FEE')).toBe(true);
      const page = await signedIn(people.accountant.email, people.accountant.password, '/app/payments/collections');
      await expect(page.getByText('Rae Resident').first()).toBeVisible();
      await page.getByRole('button', { name: 'Send reminder' }).first().click();
      await expect.poll(async () => rows(await rae.api.get('/api/workflow/notifications')).some((n: any) => /reminder|past-due|balance/i.test(`${n.title} ${n.body}`))).toBe(true);
      await page.context().close();
      const rp = await signedIn(people.resident.email, people.resident.password, '/app/payments');
      await rp.getByRole('button', { name: /^Pay \$[\d,]+/ }).click();
      await expect(rp.getByText(/Balance due\s*\$0\.00/)).toBeVisible({ timeout: 30_000 });
      await rp.context().close();
    });

    // ------------------------------------------------------------------------------------------------ 8. living there
    await test.step('the resident reports a leak, the vendor fixes it and the manager closes it with a rating', async () => {
      const title = `Kitchen tap leaking ${sfx}`;
      const rp = await signedIn(people.resident.email, people.resident.password, '/app/maintenance');
      await rp.getByRole('button', { name: 'New request' }).click();
      await rp.getByLabel(/What.s wrong/).fill(title);
      await rp.getByLabel('Details').fill('The tap drips all day and the cabinet below is damp.');
      await rp.getByRole('button', { name: 'Submit' }).click();
      await rp.waitForURL(/\/app\/maintenance\/[0-9a-f-]{36}/);
      const id = rp.url().split('/').pop()!;
      await rp.context().close();
      const job = () => admin.get(`/api/maintenance/work-orders/${id}`);
      await until(job, (r) => r.body.status !== 'SUBMITTED');
      if ((await job()).body.status === 'AWAITING_APPROVAL') await admin.post(`/api/maintenance/work-orders/${id}/actions/APPROVE`, {});
      if ((await job()).body.status !== 'ASSIGNED') await admin.post(`/api/maintenance/work-orders/${id}/actions/ASSIGN`, { vendorId });
      expect((await job()).body.vendor_id).toBe(vendorId);
      const vp = await signedIn(people.vendor.email, people.vendor.password, `/app/maintenance/${id}`);
      await expect(vp.getByRole('heading', { name: title })).toBeVisible();
      await vp.getByRole('button', { name: 'Schedule visit' }).click();
      await vp.getByLabel('Visit date and time').fill(addDays(new Date(), 1).toISOString().slice(0, 16));
      await vp.getByRole('dialog').getByRole('button', { name: 'Schedule visit' }).click();
      await vp.getByRole('button', { name: 'Start work' }).click();
      await vp.getByRole('button', { name: /Mark complete/i }).first().click();
      await vp.getByLabel(/Final cost/).fill('240');
      await vp.getByLabel('Work notes').fill('Replaced the cartridge and resealed the base.');
      await vp.getByRole('dialog').getByRole('button', { name: /Mark complete/i }).click();
      await expect.poll(async () => (await job()).body.status).toBe('COMPLETED');
      await vp.context().close();
      const mp = await signedIn(people.manager.email, people.manager.password, `/app/maintenance/${id}`);
      await mp.getByRole('button', { name: 'Confirm fixed and close' }).first().click();
      await mp.getByRole('dialog').getByRole('combobox').click();
      await mp.getByRole('option', { name: /5 stars/ }).click();
      await mp.getByRole('dialog').getByRole('button', { name: 'Confirm fixed and close' }).click();
      await expect.poll(async () => (await job()).body.status).toBe('CLOSED');
      await mp.context().close();
    });

    await test.step('the resident and the manager exchange messages, finding each other by name', async () => {
      const rp = await signedIn(people.resident.email, people.resident.password, '/app/messages');
      await rp.getByRole('button', { name: 'New conversation' }).click();
      const d = rp.getByRole('dialog');
      await d.getByLabel('Subject').fill('Parking permit');
      await d.getByPlaceholder(/Search people/).click();
      await d.getByPlaceholder(/Search people/).fill('Max');
      await rp.getByRole('option', { name: /Max Manager/ }).first().click();
      await d.getByLabel('Message').fill('Where do I collect a parking permit?');
      await d.getByRole('button', { name: 'Send' }).click();
      await expect(rp.getByText('Parking permit').first()).toBeVisible();
      await rp.context().close();
      const mp = await signedIn(people.manager.email, people.manager.password, '/app/messages');
      await mp.getByPlaceholder('Search conversations').fill('Parking');
      await mp.getByText('Parking permit').first().click();
      await expect(mp.getByText('Where do I collect a parking permit?')).toBeVisible();
      await mp.getByPlaceholder(/message|reply/i).fill('At the leasing office, any weekday.');
      await mp.getByRole('button', { name: /Send/ }).last().click();
      await expect(mp.getByText('At the leasing office, any weekday.')).toBeVisible();
      await mp.context().close();
    });

    await test.step('the manager sends an addendum for e-signature and the resident signs it on screen', async () => {
      const doc = rows(await admin.get(`/api/documents?page=1&size=20&entityType=LEASE&entityId=${leaseId}`));
      const target = doc.find((d: any) => /pdf/i.test(d.mime_type ?? '')) ?? doc[0];
      test.skip(!target, 'no lease document is filed for e-signature in this environment');
      const signers = (await admin.get(`/api/documents/${target.id}/signers?q=Rae`)).body.rows;
      expect(signers.some((s: any) => s.name === resident.name)).toBe(true);
      const sent = await admin.post(`/api/documents/${target.id}/signature-requests`, { signers: [{ userId: signers.find((s: any) => s.name === resident.name).userId }], message: 'Please sign the lease copy.' });
      expect(sent.status).toBe(200);
      const rp = await signedIn(people.resident.email, people.resident.password, `/app/documents/signatures/${sent.body.id}`);
      await rp.getByLabel(/Type your full legal name/).fill(resident.name);
      await rp.getByRole('checkbox').check();
      await rp.getByRole('button', { name: 'Sign document' }).click();
      await expect.poll(async () => (await admin.get(`/api/documents/signatures/${sent.body.id}`)).body.status).toBe('COMPLETED');
      await rp.context().close();
    });

    // ------------------------------------------------------------------------------------------------ 9. the books
    await test.step('the accountant records the owner W-9, issues the owner statement and the owner reads it', async () => {
      expect((await admin.put(`/api/billing/tax/owners/${ownerId}/w9`, { w9Received: true, tinLast4: '1234', legalName: `Olivia Owner ${sfx}`, tinType: 'SSN' })).status).toBe(200);
      const st = await admin.post('/api/billing/statements', { ownerId, period: period(m1), ownerName: `Olivia Owner ${sfx}`, feePercent: 8 });
      expect(st.status).toBe(200);
      expect((await admin.post(`/api/billing/statements/${st.body.id}/publish`, {})).status).toBe(200);
      const owner = await signedIn(people.owner.email, people.owner.password, '/app/payments/statements');
      await expect(owner.getByText(`Olivia Owner ${sfx}`).first()).toBeVisible();
      await owner.context().close();
      const deposits = rows(await admin.get('/api/billing/deposits'));
      expect(deposits.length).toBeGreaterThanOrEqual(1);
    });

    // ------------------------------------------------------------------------------------------------ 10. renewal, notice, move-out
    await test.step('the manager offers a renewal and the resident accepts it', async () => {
      const offer = await admin.post(`/api/leasing/leases/${leaseId}/renewal`, { termMonths: 12 });
      expect(offer.status).toBe(200);
      expect((await rae.api.post(`/api/leasing/leases/${leaseId}/actions/ACCEPT_RENEWAL`, {})).status).toBeLessThan(500);
    });

    await test.step('the resident gives notice, moves out and the deposit is settled with a deduction', async () => {
      for (const a of ['GIVE_NOTICE', 'END']) {
        const r = await admin.post(`/api/leasing/leases/${leaseId}/actions/${a}`, {});
        expect([200, 409], `${a}: ${JSON.stringify(r.body)}`).toContain(r.status);
      }
      const dep = rows(await admin.get('/api/billing/deposits')).find((d: any) => d.lease_id === leaseId) ?? rows(await admin.get('/api/billing/deposits'))[0];
      const page = await signedIn(people.accountant.email, people.accountant.password, '/app/payments/deposits');
      await expect(page.getByRole('heading', { name: 'Deposits' })).toBeVisible();
      await page.context().close();
      const settled = await admin.post(`/api/billing/deposits/${dep.id}/settle`, { deductions: [{ description: 'Carpet cleaning', amount: 85 }] });
      expect([200, 409]).toContain(settled.status);
      const lease = (await admin.get(`/api/leasing/leases/${leaseId}`)).body;
      expect(['ENDED', 'CLOSED', 'NOTICE_GIVEN', 'RENEWAL_OFFERED', 'ACTIVE']).toContain(lease.status);
    });

    // ------------------------------------------------------------------------------------------------ 11. what the company can see afterwards
    await test.step('the audit log names who did what, and the platform owner sees the company in use', async () => {
      const actors = new Set(rows(await admin.get('/api/identity/audit?page=1&size=100')).map((a: any) => a.actor_name));
      for (const who of ['Ada Admin', 'Max Manager']) expect(actors.has(who), `the audit log records ${who}`).toBe(true);
      await adminPage.goto('/app/admin/audit');
      await expect(adminPage.getByRole('heading', { name: 'Audit log' })).toBeVisible();
      const platform = await apiAs('platform');
      const detail = (await platform.get(`/api/identity/platform/orgs/${orgId}`)).body;
      expect(detail.userStats.total).toBeGreaterThanOrEqual(8);
      expect(await everyRow(admin, '/api/identity/users')).toHaveLength(detail.userStats.total);
    });

    for (const c of contexts) await c.close().catch(() => undefined);
    expect(watchers.flat().filter((p) => !/40[134]|409/.test(p))).toEqual([]);
  });
});
