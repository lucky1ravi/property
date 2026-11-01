import { expect, type Page } from '@playwright/test';
import { Api, rows } from './api';

/**
 * Helpers for the long journeys: signing in through the real form, getting out of the way of the first-time tour,
 * and moving the billing clock forward to show what living in a home for a few months looks like.
 */

/** Marks the welcome tour, page-tour offers and getting-started list as already seen, so a test is not about them. */
export async function quietGuide(token: string) {
  const api = await Api.create(token);
  await api.put('/api/identity/me/ui-state', { guide: { welcome: true, noCoach: true, checklistHidden: true } });
}

/** Closes the welcome dialog if it appears. A person signing in for the first time sees it. */
export async function dismissWelcome(page: Page) {
  const dialog = page.getByRole('dialog', { name: /Welcome to PropertyOS/ });
  // It appears about a second after the workspace loads, so wait for it rather than looking once.
  const shown = await dialog.waitFor({ state: 'visible', timeout: 4000 }).then(() => true, () => false);
  if (shown) await dialog.getByRole('button', { name: /explore on my own/i }).click();
  await expect(dialog).toBeHidden();
}

/** Signs in the way a person does: the sign-in form with an email and password. Returns once the workspace is showing. */
export async function signInThroughScreen(page: Page, email: string, password: string) {
  await page.goto('/signin');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(/\/app/);
}

/** First sign-in with a temporary password: choose a new one on screen. */
export async function firstSignInThroughScreen(page: Page, email: string, temporary: string, newPassword: string) {
  await page.goto('/signin');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password').fill(temporary);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByLabel(/^New password/).fill(newPassword);
  await page.getByLabel('Confirm new password').fill(newPassword);
  await page.getByRole('button', { name: 'Save password and sign in' }).click();
  await page.waitForURL(/\/app/);
}

/** Runs the billing cycle as if it were the given day (local and demo environments only). */
export async function cycleOn(api: Api, day: Date | string) {
  const asOf = typeof day === 'string' ? day : day.toISOString().slice(0, 10);
  const r = await api.post('/api/billing/run-cycle', { asOf });
  if (r.status !== 200) throw new Error(`billing cycle for ${asOf} failed: ${r.status} ${JSON.stringify(r.body)}`);
  return r.body as { rentInvoicesCreated: number; lateFeesCharged: number; autopayCollected: number; period: string };
}

/** The first day of the month `months` after the month of `from`. */
export function firstOfMonth(from: Date | string, months: number): Date {
  const d = new Date(typeof from === 'string' ? from + 'T00:00:00Z' : from);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + months, 1));
}

export function addDays(d: Date, days: number): Date {
  return new Date(d.getTime() + days * 86400000);
}

/** All rows of a list, following the pages. */
export async function everyRow(api: Api, path: string): Promise<any[]> {
  const out: any[] = [];
  for (let page = 1; page < 50; page++) {
    const r = await api.get(`${path}${path.includes('?') ? '&' : '?'}page=${page}&size=100`);
    const got = rows(r);
    out.push(...got);
    if (got.length < 100) break;
  }
  return out;
}
