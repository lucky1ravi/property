import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, rows } from '../../src/support/api';
import { onboardCompany } from '../../src/support/company';
import { buildPortfolio, publishListing, until } from '../../src/support/builders';

/**
 * Answering a lead. When staff send the reply to a prospect (who has no account), it is emailed to the address they gave, and the email shows in the
 * company's delivery log with its subject and whether it was sent.
 */
test.describe('Lead replies by email', () => {
  needsWrites();
  test.setTimeout(180_000);

  test('[MAIL-01] sending a reply to a lead emails the prospect, and the delivery log shows it as sent', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const email = `tessa.${p.sfx}@example.com`;
    const lead = (await (await Api.create()).post(`/api/leasing/public/listings/${listing}/enquiries`,
      { name: 'Tessa Nguyen', email, message: 'I toured yesterday and love it. How do I apply?' })).body;
    expect(lead.id).toBeTruthy();
    expect((await c.api.post(`/api/leasing/leads/${lead.id}/respond`, { reply: 'Hi Tessa, thanks for touring. Apply here today and we will move fast.' })).status).toBe(200);
    expect((await c.api.get(`/api/leasing/leads`)).body.rows?.find?.((l: any) => l.id === lead.id)?.status ?? 'RESPONDED').toBe('RESPONDED');

    const mail = await until(async () => rows(await c.api.get('/api/workflow/deliveries')).find((d: any) => d.channel === 'EMAIL' && d.recipient === email),
      (d) => !!d && d.status !== 'QUEUED');
    expect(mail, 'an email to the prospect').toBeTruthy();
    expect(mail.status).toBe('SENT');
    expect(mail.subject).toMatch(/^Re: your enquiry about /);
  });
});
