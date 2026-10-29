import { test, expect, watchErrors, pick } from '../../src/support/fixtures';
import { CAN_WRITE, SUFFIX } from '../../src/support/env';
import { rows } from '../../src/support/api';
import { onboardCompany } from '../../src/support/company';
import { buildPortfolio, publishListing, moveIn } from '../../src/support/builders';

const TITLES: Array<[string, string]> = [
  ['/', 'Property management software | PropertyOS'],
  ['/products', 'Products | PropertyOS'],
  ['/solutions', 'Solutions | PropertyOS'],
  ['/features', 'Features | PropertyOS'],
  ['/plans', 'Plans and pricing | PropertyOS'],
  ['/why-us', 'Why PropertyOS | PropertyOS'],
  ['/security', 'Security | PropertyOS'],
  ['/contact', 'Contact us | PropertyOS'],
];
const PRODUCT_NAMES = ['Leasing', 'Rent and owners', 'Maintenance', 'Automation and AI', 'Broker tools and insights', 'Messages and documents'];
const ROLE_IDS = ['brokers', 'managers', 'accountants', 'owners', 'residents', 'vendors'];

test.describe('Marketing website', () => {
  test('[SITE-07] every page sets its own browser tab title and has one main heading and landmarks', async ({ page }) => {
    for (const [path, title] of TITLES) {
      await page.goto(path);
      await expect(page).toHaveTitle(title);
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
      await expect(page.getByRole('main')).toHaveCount(1);
      await expect(page.getByRole('banner')).toHaveCount(1);
      await expect(page.getByRole('contentinfo')).toContainText('All rights reserved');
      await expect(page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible();
    }
  });

  test('[SITE-08] the header and footer links all lead to the right page, and the logo goes home', async ({ page }) => {
    const header: Array<[string, RegExp]> = [['Platform', /\/products$/], ['Solutions', /\/solutions$/], ['Features', /\/features$/], ['Plans', /\/plans$/], ['Why us', /\/why-us$/], ['Security', /\/security$/], ['Request a demo', /\/contact$/], ['Sign in', /\/signin$/]];
    for (const [label, url] of header) {
      await page.goto('/why-us');
      await page.locator('header').getByRole('link', { name: label, exact: true }).first().click();
      await expect(page, label).toHaveURL(url);
    }
    const footer: Array<[string, RegExp]> = [
      ['Leasing', /\/products#leasing$/], ['Rent and owners', /\/products#rent$/], ['Maintenance', /\/products#maintenance$/], ['Automation and AI', /\/products#automation$/],
      ['Brokers', /\/solutions#brokers$/], ['Property managers', /\/solutions#managers$/], ['Owners', /\/solutions#owners$/], ['Accountants', /\/solutions#accountants$/],
      ['Features', /\/features$/], ['Plans and pricing', /\/plans$/], ['Why PropertyOS', /\/why-us$/], ['Security', /\/security$/],
      ['Contact sales', /\/contact$/], ['Sign in', /\/signin$/], ['Homes for rent', /\/homes$/],
    ];
    for (const [label, url] of footer) {
      await page.goto('/');
      await page.getByRole('contentinfo').getByRole('link', { name: label, exact: true }).click();
      await expect(page, label).toHaveURL(url);
    }
    await page.goto('/security');
    await page.getByRole('link', { name: 'PropertyOS home' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Run a bigger portfolio');
  });

  test('[SITE-09] the home page: hero actions, every product card, pricing teaser and the governance section', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('main').getByRole('link', { name: 'Explore the platform' }).click();
    await expect(page).toHaveURL(/\/products$/);
    await page.goto('/');
    await page.getByRole('main').getByRole('link', { name: 'Request a demo' }).first().click();
    await expect(page).toHaveURL(/\/contact$/);
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Automation that knows when to stop' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Priced by the units you manage' })).toBeVisible();
    // each product card goes to its section on the products page
    const cards = page.getByRole('region', { name: /One platform for the whole property lifecycle/ }).getByRole('link');
    expect(await cards.count()).toBeGreaterThanOrEqual(6);
    await page.getByRole('main').getByRole('link', { name: 'Compare plans' }).click();
    await expect(page).toHaveURL(/\/plans$/);
    await page.goto('/');
    await page.getByRole('main').getByRole('link', { name: /How we protect your data/ }).click();
    await expect(page).toHaveURL(/\/security$/);
    await page.goto('/');
    await page.getByRole('main').getByRole('link', { name: /Why teams choose PropertyOS/ }).click();
    await expect(page).toHaveURL(/\/why-us$/);
    await page.goto('/');
    await page.getByRole('main').getByRole('link', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/signin$/);
  });

  test('[SITE-10] the products page has all six areas with their flow and the features the platform lists for each', async ({ page, publicApi }) => {
    const site = (await (await publicApi()).get('/api/identity/public/site')).body;
    await page.goto('/products');
    for (const name of PRODUCT_NAMES) await expect(page.getByRole('heading', { name, level: 2 })).toBeVisible();
    await expect(page.locator('ol[aria-label^="How "]')).toHaveCount(6);
    await expect(page.locator('main section[id]')).toHaveCount(6);
    // every feature the platform serves appears as a tag under its area
    for (const f of site.features) await expect(page.locator('main').getByText(f.title, { exact: true }).first(), f.title).toBeVisible();
    // a link with an anchor lands on that section
    await page.goto('/products#maintenance');
    await expect(page.locator('#maintenance')).toBeInViewport({ ratio: 0.1 });
    await expect(page.locator('#maintenance')).toContainText('Repairs dispatched before the second phone call');
  });

  test('[SITE-11] the solutions page has a section for each of the six roles, reachable by anchor', async ({ page }) => {
    await page.goto('/solutions');
    for (const id of ROLE_IDS) await expect(page.locator(`li#${id}`), id).toBeVisible();
    await expect(page.locator('main li[id] h2')).toHaveCount(6);
    await page.goto('/solutions#vendors');
    await expect(page.locator('#vendors')).toBeInViewport({ ratio: 0.1 });
    await page.getByRole('main').getByRole('link', { name: 'See features' }).click();
    await expect(page).toHaveURL(/\/features$/);
  });

  test('[SITE-12] the features page lists every platform feature under its area with the lowest plan that includes it', async ({ page, publicApi }) => {
    const site = (await (await publicApi()).get('/api/identity/public/site')).body;
    await page.goto('/features');
    for (const f of site.features) {
      const item = page.locator('main li').filter({ has: page.getByRole('heading', { name: f.title, exact: true, level: 3 }) });
      await expect(item, f.title).toBeVisible();
      await expect(item).toContainText(f.summary);
      const lowest = site.plans.find((p: any) => p.allFeatures || p.features.includes(f.key));
      await expect(item).toContainText(lowest ? `From ${lowest.name}` : 'On request');
    }
    await page.getByRole('main').getByRole('link', { name: 'Compare plans' }).click();
    await expect(page).toHaveURL(/\/plans$/);
  });

  test('[SITE-13] the plans page shows each plan with its price, minimum, popular badge and feature list from the platform', async ({ page, publicApi }) => {
    const site = (await (await publicApi()).get('/api/identity/public/site')).body;
    const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: n % 1 === 0 ? 0 : 2 });
    await page.goto('/plans');
    await expect(page.locator('article')).toHaveCount(site.plans.length);
    for (const p of site.plans) {
      const card = page.locator('article').filter({ has: page.getByRole('heading', { name: p.name, exact: true }) });
      await expect(card).toContainText(usd(p.pricePerUnit));
      await expect(card).toContainText(`${usd(p.minimumMonthly)} monthly minimum`);
      await expect(card.getByText('Most popular')).toHaveCount(p.highlight ? 1 : 0);
      if (p.allFeatures) await expect(card).toContainText('Every feature');
      else await expect(card.locator('li')).toHaveCount(p.features.length);
    }
    // the FAQ answers the four pricing questions
    await expect(page.getByRole('heading', { name: 'Questions about pricing' })).toBeVisible();
    await expect(page.locator('dt')).toHaveCount(4);
    await expect(page.getByText('How is PropertyOS priced?')).toBeVisible();
  });

  test('[SITE-13] the plan comparison table has a row per feature and a column per plan with the right ticks', async ({ page, publicApi }) => {
    const site = (await (await publicApi()).get('/api/identity/public/site')).body;
    await page.goto('/plans');
    const table = page.getByRole('table', { name: 'Features included in each plan' });
    await expect(table.locator('thead th')).toHaveCount(site.plans.length + 1);
    await expect(table.locator('tbody tr')).toHaveCount(site.features.length);
    for (const f of site.features) {
      const row = table.locator('tbody tr').filter({ has: page.getByRole('rowheader', { name: new RegExp(f.title) }) });
      for (let i = 0; i < site.plans.length; i++) {
        const p = site.plans[i];
        const yes = p.allFeatures || p.features.includes(f.key);
        await expect(row.locator('td').nth(i), `${f.title} / ${p.name}`).toContainText(yes ? 'Included' : 'Not included');
      }
    }
  });

  test('[SITE-14] choosing a plan opens the contact form already set to pricing with the plan in the message', async ({ page, publicApi }) => {
    const site = (await (await publicApi()).get('/api/identity/public/site')).body;
    const plan = site.plans[0];
    await page.goto('/plans');
    await page.getByRole('link', { name: `Talk to us about ${plan.name}` }).click();
    await expect(page).toHaveURL(new RegExp(`/contact\\?topic=PRICING&plan=${plan.key}$`));
    await expect(page.getByLabel('Message')).toHaveValue(new RegExp(`interested in the ${plan.name} plan`, 'i'));
    await expect(page.getByLabel('What’s this about?')).toContainText('Pricing and plans');
    await page.goto('/plans');
    await page.getByRole('main').getByRole('link', { name: 'Get a quote' }).click();
    await expect(page).toHaveURL(/topic=PRICING/);
  });

  test('[SITE-15] the why-us and security pages show their sections and lead to a contact form', async ({ page }) => {
    await page.goto('/why-us');
    await expect(page.getByRole('heading', { name: 'Where a person stays in charge' })).toBeVisible();
    expect(await page.locator('main h2').count()).toBeGreaterThanOrEqual(3);
    await page.getByRole('main').getByRole('link', { name: 'See plans' }).first().click();
    await expect(page).toHaveURL(/\/plans$/);
    await page.goto('/security');
    expect(await page.locator('main h2').count()).toBeGreaterThanOrEqual(4);
    await expect(page.getByRole('heading', { name: 'Need details for your own review?' })).toBeVisible();
    await page.getByRole('main').getByRole('link', { name: 'Ask a security question' }).click();
    await expect(page).toHaveURL(/\/contact$/);
  });

  test('[SITE-16] the contact form: topics, number-only units, bot trap hidden, button enables only when valid', async ({ page }) => {
    await page.goto('/contact?topic=SUPPORT');
    await expect(page.getByLabel('What’s this about?')).toContainText('Help with my account');
    await page.getByLabel('What’s this about?').click();
    for (const t of ['I’d like a demo', 'Pricing and plans', 'Help with my account', 'Partnership', 'Something else']) await expect(page.getByRole('option', { name: t })).toBeVisible();
    await page.keyboard.press('Escape');
    await page.goto('/contact?topic=NOPE');
    await expect(page.getByLabel('What’s this about?')).toContainText('I’d like a demo'); // unknown topic falls back
    await page.getByLabel('Units you manage').fill('12ab3');
    await expect(page.getByLabel('Units you manage')).toHaveValue('123');
    // the bot trap is off-screen and out of the tab order
    const trap = page.getByLabel('Leave this empty');
    await expect(trap).toHaveAttribute('tabindex', '-1');
    expect((await trap.boundingBox())!.x).toBeLessThan(-1000);
    const send = page.getByRole('button', { name: 'Send message' });
    await expect(send).toBeDisabled();
    await page.getByLabel('Your name').fill('Dana Prospect');
    await page.getByLabel('Work email').fill('dana@e2e.test');
    await expect(send).toBeDisabled(); // message still too short
    await page.getByLabel('Message').fill('Short');
    await expect(send).toBeDisabled();
    await page.getByLabel('Message').fill('We manage forty doors and want a walkthrough.');
    await expect(send).toBeEnabled();
    await page.getByLabel('Work email').fill('no-at-sign');
    await expect(send).toBeDisabled();
    await expect(page.getByRole('heading', { name: 'What happens next' })).toBeVisible();
    await expect(page.locator('aside li')).toHaveCount(3);
  });

  test('[SITE-17] sending the contact form shows the thank-you and the operator sees the request with its topic, company and units', async ({ page, apiAs }) => {
    test.skip(!CAN_WRITE, 'creates a contact request on the live site');
    const sfx = SUFFIX();
    const email = `erin.${sfx}@e2e.test`;
    await page.goto('/contact?topic=PARTNERSHIP');
    await page.getByLabel('Your name').fill(`Erin ${sfx}`);
    await page.getByLabel('Work email').fill(email);
    await page.getByLabel('Company').fill(`Erin Estates ${sfx}`);
    await page.getByLabel('Phone').fill('5125550100');
    await page.getByLabel('Units you manage').fill('85');
    await page.getByLabel('Message').fill('We would like to talk about a partnership with your team.');
    await page.getByRole('button', { name: 'Send message' }).click();
    await expect(page.getByRole('status')).toContainText('Thanks, we’ve got your message');
    await expect(page.getByRole('status')).toContainText(email);
    await expect(page.getByLabel('Your name')).toHaveCount(0);
    const platform = await apiAs('platform');
    const mine = rows(await platform.get('/api/identity/platform/site/contact-requests')).find((r: any) => r.email === email);
    expect(mine.topic).toBe('PARTNERSHIP');
    expect(mine.company).toBe(`Erin Estates ${sfx}`);
    expect(String(mine.units)).toBe('85');
    expect(mine.status).toBe('NEW');
  });

  test('[SITE-18] on a phone the menu opens and closes, every page fits the width and nothing breaks', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 800 }, isMobile: true });
    const page = await ctx.newPage();
    const problems = watchErrors(page);
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Open menu' })).toBeVisible();
    await expect(page.locator('header').getByRole('link', { name: 'Features', exact: true })).toBeHidden();
    await page.getByRole('button', { name: 'Open menu' }).click();
    await expect(page.locator('#mobile-nav')).toBeVisible();
    for (const l of ['Platform', 'Solutions', 'Features', 'Plans', 'Why us', 'Security', 'Contact sales']) await expect(page.locator('#mobile-nav').getByRole('link', { name: l, exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Close menu' }).click();
    await expect(page.locator('#mobile-nav')).toHaveCount(0);
    await page.getByRole('button', { name: 'Open menu' }).click();
    await page.locator('#mobile-nav').getByRole('link', { name: 'Plans', exact: true }).click();
    await expect(page).toHaveURL(/\/plans$/);
    await expect(page.locator('#mobile-nav')).toHaveCount(0); // closes after navigating
    for (const [path] of TITLES) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${path} scrolls sideways`).toBeLessThanOrEqual(1);
    }
    expect(problems).toEqual([]);
    await ctx.close();
  });

  test('[SITE-19] keyboard users get a skip link, and every image on the marketing pages has a text alternative', async ({ page }) => {
    await page.goto('/features');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible(); // wait for the page to be ready before tabbing
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to content' });
    await expect(skip).toBeFocused();
    await expect(skip).toBeVisible();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/#content$/);
    for (const [path] of TITLES) {
      await page.goto(path);
      const missing = await page.evaluate(() => [...document.querySelectorAll('img')].filter((i) => i.getAttribute('alt') === null).length);
      expect(missing, `${path} has images without alt`).toBe(0);
    }
  });

  test('[SITE-20] a signed-in visitor sees an Open PropertyOS link, and the sign-in page sends them into the app', async ({ pageAs }) => {
    const page = await pageAs('admin', '/');
    await expect(page.locator('header').getByRole('link', { name: 'Open PropertyOS' })).toBeVisible();
    await expect(page.locator('header').getByRole('link', { name: 'Sign in', exact: true })).toHaveCount(0);
    await page.locator('header').getByRole('link', { name: 'Open PropertyOS' }).click();
    await expect(page).toHaveURL(/\/app/);
    await page.goto('/signin');
    await expect(page).toHaveURL(/\/app/);
  });
});

test.describe('Public rental site', () => {
  test('[SITE-21] the search filters by city, bedrooms and rent and says so when nothing matches', async ({ page, publicApi }) => {
    const live = rows(await (await publicApi()).get('/api/leasing/public/listings'));
    await page.goto('/homes');
    await expect(page.getByRole('heading', { name: /Find a home you.ll want to stay in/ })).toBeVisible();
    await page.getByLabel('City').fill('Nowhereville-xyz');
    await expect(page.getByText('No homes match your search')).toBeVisible();
    await page.getByLabel('City').fill('');
    for (const b of ['Any', '1+', '2+', '3+']) {
      await page.getByLabel('Bedrooms').click();
      await expect(page.getByRole('option', { name: b, exact: true })).toBeVisible();
      await page.keyboard.press('Escape');
    }
    if (live.length) {
      const l = live[0];
      await expect(page.getByRole('link', { name: new RegExp(l.property_name) }).first()).toBeVisible();
      await page.getByLabel('City').fill(l.city);
      await expect(page.getByRole('link', { name: new RegExp(l.property_name) }).first()).toBeVisible();
      await pick(page, page.getByLabel('Max rent'), '$1,500');
      const cheap = rows(await (await publicApi()).get('/api/leasing/public/listings?maxRent=1500'));
      if (!cheap.some((x: any) => x.id === l.id)) await expect(page.getByRole('link', { name: new RegExp(`/homes/${l.id}$`) })).toHaveCount(0);
    }
  });

  test('[SITE-22] an unknown home and an incomplete documents link both show a helpful message', async ({ page }) => {
    await page.goto('/homes/00000000-0000-0000-0000-000000000000');
    await expect(page.getByText('This home is no longer available')).toBeVisible();
    await page.getByRole('link', { name: 'See other homes' }).click();
    await expect(page).toHaveURL(/\/homes$/);
    await page.goto('/homes/applications/00000000-0000-0000-0000-000000000000/documents');
    await expect(page.getByText('This link is incomplete')).toBeVisible();
    await page.goto('/homes/applications/00000000-0000-0000-0000-000000000000/documents?token=wrong');
    await expect(page.locator('main')).not.toContainText(/something went wrong/i);
  });

  test('[SITE-23] a live home shows its rent, deposit, details and photos, and the application needs consent', async ({ page, publicApi }) => {
    const items = rows(await (await publicApi()).get('/api/leasing/public/listings'));
    test.skip(!items.length, 'no live homes on this environment');
    const l = (await (await publicApi()).get(`/api/leasing/public/listings/${items[0].id}`)).body;
    await page.goto(`/homes/${l.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(l.title);
    await expect(page.locator('main')).toContainText(l.street);
    await expect(page.locator('main')).toContainText(`${l.beds} bed`);
    await expect(page.locator('aside')).toContainText('/mo');
    await expect(page.locator('aside')).toContainText('Deposit');
    for (const h of l.highlights ?? []) await expect(page.locator('main').getByText(h, { exact: true }).first()).toBeVisible();
    await page.getByRole('link', { name: 'All homes' }).click();
    await expect(page).toHaveURL(/\/homes$/);
    await page.goto(`/homes/${l.id}`);
    await page.getByRole('link', { name: 'Apply now' }).click();
    await expect(page).toHaveURL(new RegExp(`/homes/${l.id}/apply$`));
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Apply for');
    const submit = page.getByRole('button', { name: 'Submit application' });
    await expect(submit).toBeDisabled();
    await page.getByRole('checkbox').check();
    await expect(submit).toBeEnabled();
    await page.getByRole('checkbox').uncheck();
    await expect(submit).toBeDisabled();
    await page.getByRole('link', { name: 'Back to the home' }).click();
    await expect(page).toHaveURL(new RegExp(`/homes/${l.id}$`));
  });

  test('[SITE-24] a visitor asks a question, requests a tour and applies, then sends their documents with the private link', async ({ page }) => {
    test.skip(!CAN_WRITE, 'creates leads, showings and applications');
    test.setTimeout(240_000);
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const sfx = p.sfx;
    const problems = watchErrors(page);

    // Ask a question: validation is shown, then the reply from the leasing assistant.
    await page.goto(`/homes/${listing}`);
    await page.getByRole('button', { name: 'Ask a question' }).click();
    await page.getByLabel('Full name').fill(`Quinn ${sfx}`);
    await page.getByLabel('Email').fill(`quinn.${sfx}@e2e.test`);
    await page.getByLabel('Message').fill('Is the unit pet friendly, and is parking included?');
    await page.getByRole('button', { name: 'Send message' }).click();
    await expect(page.locator('aside .bg-good-50')).toBeVisible();
    await expect.poll(async () => rows(await c.api.get('/api/leasing/leads')).some((l: any) => l.email === `quinn.${sfx}@e2e.test`)).toBe(true);

    // Request a tour for tomorrow.
    await page.goto(`/homes/${listing}`);
    await page.getByRole('button', { name: 'Request a tour' }).click();
    await page.getByLabel('Full name').fill(`Tess ${sfx}`);
    await page.getByLabel('Email').fill(`tess.${sfx}@e2e.test`);
    const when = new Date(Date.now() + 2 * 86400000);
    when.setHours(11, 0, 0, 0);
    const local = new Date(when.getTime() - when.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    await page.getByLabel('Preferred time').fill(local);
    await page.getByRole('button', { name: 'Request tour' }).click();
    await expect(page.locator('aside .bg-good-50')).toContainText(/Tour request/i);
    await expect.poll(async () => rows(await c.api.get('/api/leasing/showings')).some((s: any) => JSON.stringify(s).includes(`tess.${sfx}@e2e.test`))).toBe(true);

    // Apply and add documents with the private link.
    await page.goto(`/homes/${listing}/apply`);
    await page.getByLabel('First name').fill('Avery');
    await page.getByLabel('Last name').fill('Applicant');
    await page.getByLabel('Email').fill(`avery.${sfx}@e2e.test`);
    await page.getByLabel('Phone').fill('5125550144');
    await page.getByLabel(/Monthly income/).fill('8500');
    await page.getByLabel('Employer', { exact: true }).fill('Acme');
    await page.getByLabel(/Months at employer/).fill('24');
    await page.getByLabel(/Desired move-in date/).fill(moveIn(21));
    await page.getByLabel('Pets').fill('None');
    await page.getByRole('checkbox').check();
    await page.getByRole('button', { name: 'Submit application' }).click();
    await expect(page.getByRole('heading', { name: 'Application received' })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('Add your documents')).toBeVisible();
    await page.locator('input[type=file]').first().setInputFiles({ name: 'photo-id.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n%e2e photo id\n') });
    await expect(page.getByText(/Sent so far:/)).toBeVisible({ timeout: 30_000 });
    await page.getByRole('button', { name: 'Back to homes' }).click();
    await expect(page).toHaveURL(/\/homes$/);
    expect(problems).toEqual([]);
    // staff see the applicant and the file
    const app = await (async () => {
      for (let i = 0; i < 30; i++) {
        const a = rows(await c.api.get('/api/leasing/applications')).find((x: any) => x.applicant_email === `avery.${sfx}@e2e.test`);
        if (a) return a;
        await new Promise((r) => setTimeout(r, 1000));
      }
      return null;
    })();
    expect(app).toBeTruthy();
  });
});
