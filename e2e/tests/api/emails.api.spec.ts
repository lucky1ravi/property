import { test, expect, needsWrites } from '../../src/support/fixtures';
import { Api, rows } from '../../src/support/api';
import { onboardCompany, roleId } from '../../src/support/company';
import { buildPortfolio, publishListing, apply, until } from '../../src/support/builders';
import { occupiedHome } from '../../src/support/scenarios';

/**
 * Emails. People are emailed wherever it is genuinely useful: a new account (with its one-time password, which is also still shown on screen), a reset, a
 * changed password, an applicant's application and decision, a prospect's tour, and a resident's notifications. Each shows in the company's delivery log, and the
 * log never shows a password.
 */
test.describe('Emails', () => {
  needsWrites();
  test.setTimeout(180_000);

  /** The company's email to this address with this subject, once it has been sent. */
  async function mailTo(api: Api, address: string, subject: string | RegExp) {
    return until(async () => rows(await api.get('/api/workflow/deliveries')).find((d: any) => d.channel === 'EMAIL' && d.recipient === address
        && (typeof subject === 'string' ? d.subject === subject : subject.test(d.subject))),
      (d) => !!d && d.status === 'SENT');
  }

  test('[EMAIL-01] a new user gets a welcome email with the one-time password, the password is still shown on screen, and the log never shows it', async () => {
    const c = await onboardCompany();
    const email = `nora.${c.sfx}@e2e.test`;
    const made = await c.api.post('/api/identity/users', { email, name: 'Nora Newuser', roleIds: [await roleId(c.api, 'TENANT')] });
    expect(made.status).toBe(200);
    expect(made.body.temporaryPassword, 'still shown on screen').toBeTruthy();
    const mail = await mailTo(c.api, email, `Welcome to ${c.name} on PropertyOS`);
    expect(mail, 'the welcome email').toBeTruthy();
    expect(JSON.stringify(mail)).not.toContain(made.body.temporaryPassword);
    expect(mail.body, 'the log keeps a neutral summary, not the text').toMatch(/Sign-in details/);

    // A reset gives a new password on screen and in an email; choosing a password sends a security notice.
    const reset = await c.api.post(`/api/identity/users/${made.body.id}/reset-password`, {});
    expect(reset.body.temporaryPassword).toBeTruthy();
    await mailTo(c.api, email, 'Your PropertyOS password was reset');
    const pub = await Api.create();
    expect((await pub.post('/api/identity/public/change-password', { email, currentPassword: reset.body.temporaryPassword, newPassword: `Chosen-${c.sfx}-pass1` })).status).toBe(200);
    await mailTo(c.api, email, 'Your PropertyOS password was changed');
  });

  test('[EMAIL-02] a new company\'s first administrator is emailed their sign-in details when the company is onboarded', async () => {
    const c = await onboardCompany();
    const mail = await mailTo(c.api, c.adminEmail, `Welcome to ${c.name} on PropertyOS`);
    expect(mail.recipient).toBe(c.adminEmail);
    expect(mail.status).toBe('SENT');
  });

  test('[EMAIL-03] an applicant is emailed when they apply and when it is decided, and a prospect when they ask for a tour and when it is confirmed', async () => {
    const c = await onboardCompany();
    const p = await buildPortfolio(c);
    const listing = await publishListing(c.api, p.unitId);
    const pub = await Api.create();
    const applicant = `ann.${p.sfx}@example.com`;
    const applied = await apply(pub, listing, { first: 'Ann', last: 'Applicant', email: applicant });
    await mailTo(c.api, applicant, 'We received your application');
    await until(() => c.api.get(`/api/leasing/applications/${applied.body.id}`), (r) => r.body.status !== 'SUBMITTED');
    expect((await c.api.post(`/api/leasing/applications/${applied.body.id}/actions/REJECT`, { reason: 'Income too low' })).status).toBe(200);
    const declined = await mailTo(c.api, applicant, 'An update on your application');
    expect(JSON.stringify(declined), 'the reason is not put in the email').not.toContain('Income too low');

    const tourist = `tina.${p.sfx}@example.com`;
    const tour = await pub.post(`/api/leasing/public/listings/${listing}/showings`, { name: 'Tina Tourist', email: tourist, type: 'BROKER_LED', scheduledAt: new Date(Date.now() + 9 * 86400000).toISOString() });
    expect(tour.status).toBe(200);
    await mailTo(c.api, tourist, 'We got your tour request');
    expect((await c.api.post(`/api/leasing/showings/${tour.body.id}/actions/CONFIRM`, {})).status).toBe(200);
    await mailTo(c.api, tourist, 'Your tour is confirmed');
  });

  test('[EMAIL-04] a resident is emailed what the app tells them, such as that their lease is ready to sign', async () => {
    const { c, email } = await occupiedHome();
    const mail = await until(async () => rows(await c.api.get('/api/workflow/deliveries')).find((d: any) => d.channel === 'EMAIL' && d.recipient === email && /lease/i.test(d.subject)), (d) => !!d);
    expect(mail, 'an email about the lease').toBeTruthy();
    // Notifications to a person are held during their quiet hours (21:00 to 08:00 Central) and sent when they end, so at night the email is waiting.
    if (mail.status === 'QUEUED') expect(mail.note).toMatch(/quiet hours/i);
    else expect(mail.status).toBe('SENT');
  });
});
