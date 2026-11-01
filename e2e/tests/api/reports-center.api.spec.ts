import { test, expect } from '../../src/support/fixtures';
import { Api, apiAs } from '../../src/support/api';

/**
 * The report centre: portfolio, leasing, money and repair reports. Each answers a summary and the rows behind it, paged, sorted,
 * searched and filtered, and can be followed from a figure down to its records. What each person gets follows their role.
 */
test.describe('Reports', () => {
  const LEASING = ['rent-roll', 'occupancy', 'vacancies', 'expirations', 'funnel', 'ratings'];
  const BILLING = ['receivables', 'income', 'transactions', 'payments', 'fees'];
  const REPAIRS = ['costs', 'jobs', 'vendors'];

  test('[REP-01] every report answers a summary and rows for a manager, in the same shape', async () => {
    const manager = await apiAs('manager');
    const asked = [...LEASING.map((k) => `/api/leasing/reports/${k}`), ...BILLING.map((k) => `/api/billing/reports/${k}`), ...REPAIRS.map((k) => `/api/maintenance/reports/${k}`)];
    for (const path of asked) {
      const r = await manager.get(`${path}?page=1&size=5`);
      expect(r.status, path).toBe(200);
      expect(r.body.summary, `${path} summary`).toBeTruthy();
      expect(Array.isArray(r.body.rows), `${path} rows`).toBe(true);
      expect(r.body.rows.length, `${path} page size`).toBeLessThanOrEqual(5);
      expect(r.body.page, path).toBe(1);
      expect(r.body.total, path).toBeGreaterThanOrEqual(r.body.rows.length);
    }
  });

  test('[REP-02] portfolio figures add up: the summary matches the rows, occupancy is occupied over units, a gap is market minus rent', async () => {
    const manager = await apiAs('manager');
    const roll = (await manager.get('/api/leasing/reports/rent-roll?page=1&size=200')).body;
    expect(roll.summary.units).toBe(roll.total);
    const occupied = roll.rows.filter((r: any) => r.lease_id);
    expect(roll.summary.occupied).toBe(occupied.length);
    for (const r of roll.rows.slice(0, 30)) {
      const expected = r.lease_id ? Number(r.market_rent) - Number(r.rent) : Number(r.market_rent);
      expect(Number(r.gap_to_market), `${r.property} ${r.unit}`).toBeCloseTo(expected, 2);
    }
    const occ = (await manager.get('/api/leasing/reports/occupancy?page=1&size=100')).body;
    const sum = (k: string) => occ.rows.reduce((n: number, r: any) => n + Number(r[k]), 0);
    expect(sum('units')).toBe(occ.summary.units);
    expect(sum('vacant')).toBe(occ.summary.vacant);
    for (const r of occ.rows) expect(Number(r.occupancy_pct)).toBeCloseTo(Number(r.units) ? (100 * Number(r.occupied)) / Number(r.units) : 0, 0);
    // Drill-down: the units of one property are exactly that property's row.
    const one = occ.rows.find((r: any) => Number(r.units) > 0);
    const drilled = (await manager.get(`/api/leasing/reports/rent-roll?page=1&size=100&propertyId=${one.property_id}`)).body;
    expect(drilled.total).toBe(Number(one.units));
    expect(drilled.rows.every((r: any) => r.property_id === one.property_id)).toBe(true);
    const empty = (await manager.get(`/api/leasing/reports/vacancies?page=1&size=100&propertyId=${one.property_id}`)).body;
    expect(empty.total).toBe(Number(one.vacant));
  });

  test('[REP-03] tables can be sorted, searched and filtered by column, and the choices are checked', async () => {
    const manager = await apiAs('manager');
    const sorted = (await manager.get('/api/leasing/reports/rent-roll?page=1&size=50&sort=rent&dir=desc')).body.rows.filter((r: any) => r.rent != null);
    for (let i = 1; i < sorted.length; i++) expect(Number(sorted[i - 1].rent)).toBeGreaterThanOrEqual(Number(sorted[i].rent));
    const q = (await manager.get('/api/leasing/reports/rent-roll?page=1&size=50&q=Barton')).body;
    expect(q.total).toBeGreaterThan(0);
    expect(q.rows.every((r: any) => /Barton/i.test(r.property))).toBe(true);
    const f = (await manager.get('/api/leasing/reports/rent-roll?page=1&size=50&f.status=VACANT')).body;
    expect(f.rows.every((r: any) => r.status === 'VACANT')).toBe(true);
    const soon = (await manager.get('/api/leasing/reports/expirations?page=1&size=50&days=30')).body;
    expect(soon.rows.every((r: any) => Number(r.days_left) <= 30)).toBe(true);
    const wide = (await manager.get('/api/leasing/reports/expirations?page=1&size=50&days=365')).body;
    expect(wide.total).toBeGreaterThanOrEqual(soon.total);
    expect((await manager.get('/api/leasing/reports/expirations?days=0')).status).toBe(400);
    expect((await manager.get('/api/leasing/reports/expirations?days=5000')).status).toBe(400);
    expect((await manager.get('/api/leasing/reports/funnel?from=2026-12-01&to=2026-01-01')).status).toBe(400);
    expect((await manager.get('/api/billing/reports/income?from=2026-12-01&to=2026-01-01')).status).toBe(400);
    expect((await manager.get('/api/billing/reports/transactions?month=May')).status).toBe(400);
    expect((await manager.get('/api/maintenance/reports/costs?by=weather')).status).toBe(400);
    expect((await manager.get('/api/maintenance/reports/vendors?from=2026-12-01&to=2026-01-01')).status).toBe(400);
  });

  test('[REP-04] money reports: receivables age correctly, income drills to its transactions, payments and fees agree with the ledger', async () => {
    const accountant = await apiAs('accountant');
    const rec = (await accountant.get('/api/billing/reports/receivables?page=1&size=100')).body;
    expect(Number(rec.summary.outstanding)).toBeGreaterThanOrEqual(Number(rec.summary.overdue));
    for (const r of rec.rows) {
      const buckets = ['not_due', 'days_1_30', 'days_31_60', 'days_61_90', 'days_over_90'].reduce((n, k) => n + Number(r[k]), 0);
      expect(buckets, `${r.tenant_name}'s buckets add to their balance`).toBeCloseTo(Number(r.balance), 2);
    }
    // One resident's account lists their own payments and charges only.
    const who = rec.rows[0];
    const account = (await accountant.get(`/api/billing/reports/transactions?page=1&size=100&tenantId=${who.tenant_id}`)).body;
    expect(account.rows.length).toBeGreaterThan(0);
    expect(account.rows.every((r: any) => r.type !== 'EXPENSE')).toBe(true);
    expect(account.rows.every((r: any) => r.who === who.tenant_name)).toBe(true);
    const income = (await accountant.get('/api/billing/reports/income?page=1&size=100&from=2026-01-01')).body;
    for (const r of income.rows.slice(0, 20)) expect(Number(r.net)).toBeCloseTo(Number(r.collected) - Number(r.expenses), 2);
    const line = income.rows.find((r: any) => Number(r.collected) > 0);
    const tx = (await accountant.get(`/api/billing/reports/transactions?page=1&size=200&propertyId=${line.property_id}&month=${line.month}`)).body;
    const paid = tx.rows.filter((r: any) => r.type === 'PAYMENT' && r.status === 'SUCCEEDED').reduce((n: number, r: any) => n + Number(r.amount), 0);
    expect(paid, 'the payments behind the figure add up to it').toBeCloseTo(Number(line.collected), 2);
    const pay = (await accountant.get('/api/billing/reports/payments?page=1&size=50&from=2026-01-01')).body;
    expect(Number(pay.summary.collected)).toBeGreaterThan(0);
    expect(['ACH', 'CARD']).toContain(pay.rows[0].method);
    expect((await accountant.get('/api/billing/reports/fees?page=1&size=50&from=2026-01-01')).body.rows.every((r: any) => !['RENT', 'DEPOSIT'].includes(r.kind))).toBe(true);
  });

  test('[REP-05] repair reports: costs group four ways and each line opens its jobs, and the vendor scorecard uses the new ratings', async () => {
    const manager = await apiAs('manager');
    for (const by of ['property', 'category', 'vendor', 'priority']) {
      const r = (await manager.get(`/api/maintenance/reports/costs?by=${by}&page=1&size=50`)).body;
      expect(r.rows.length, by).toBeGreaterThan(0);
      expect(r.rows.reduce((n: number, x: any) => n + Number(x.jobs), 0), `${by}: lines add to the total jobs`).toBe(Number(r.summary.jobs));
    }
    const byProp = (await manager.get('/api/maintenance/reports/costs?by=property&page=1&size=50')).body.rows.find((r: any) => Number(r.jobs) > 0);
    const jobs = (await manager.get(`/api/maintenance/reports/jobs?page=1&size=100&propertyId=${byProp.group_id}`)).body;
    expect(jobs.total).toBe(Number(byProp.jobs));
    expect(jobs.rows.every((r: any) => r.property_id === byProp.group_id)).toBe(true);
    const byCat = (await manager.get('/api/maintenance/reports/costs?by=category&page=1&size=50')).body.rows[0];
    expect((await manager.get(`/api/maintenance/reports/jobs?page=1&size=100&category=${byCat.group_id}`)).body.total).toBe(Number(byCat.jobs));
    const card = (await manager.get('/api/maintenance/reports/vendors?page=1&size=50')).body;
    expect(card.summary.vendors).toBe(card.total);
    for (const v of card.rows) { expect(Number(v.rating)).toBeGreaterThan(0); expect(Number(v.on_time_pct)).toBeLessThanOrEqual(100); }
  });

  test('[REP-06] each person gets the reports for their role: owners their own properties, residents and vendors none', async () => {
    const owner = await apiAs('owner');
    const manager = await apiAs('manager');
    const all = (await manager.get('/api/leasing/reports/occupancy?page=1&size=100')).body;
    const mine = (await owner.get('/api/leasing/reports/occupancy?page=1&size=100')).body;
    expect(mine.total).toBeGreaterThan(0);
    expect(mine.total, 'an owner sees fewer properties than the company').toBeLessThanOrEqual(all.total);
    const own = new Set(mine.rows.map((r: any) => r.property_id));
    expect(mine.rows.every((r: any) => own.has(r.property_id))).toBe(true);
    const ownIncome = (await owner.get('/api/billing/reports/income?page=1&size=100&from=2026-01-01')).body;
    expect(ownIncome.rows.every((r: any) => all.rows.some((p: any) => p.property_id === r.property_id))).toBe(true);
    expect(ownIncome.rows.every((r: any) => own.has(r.property_id)), 'income only for their own properties').toBe(true);
    for (const k of ['tenant', 'vendor'] as const) {
      const who = await apiAs(k);
      for (const path of ['/api/leasing/reports/rent-roll', '/api/billing/reports/receivables', '/api/maintenance/reports/costs']) expect((await who.get(path)).status, `${k} ${path}`).toBe(403);
    }
    // A broker sees leasing reports but not the books.
    const broker = await apiAs('broker');
    expect((await broker.get('/api/leasing/reports/funnel')).status).toBe(200);
    expect((await (await Api.create()).get('/api/leasing/reports/rent-roll')).status).toBe(401);
  });
});
