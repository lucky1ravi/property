import { useMemo, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, BarChart3, Building2, CalendarClock, DoorOpen, Filter, HandCoins, Hammer, Receipt, ScrollText, Star, Truck, Wallet, FileText, TrendingUp } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { PageHeader, Panel, DataTable, type Column, Stat, StatStrip, Badge, Stars, Select, Input, Field, EmptyState, Skeleton, Button, useApi, usePagedApi, useSession, personaOf, fmt, type Persona } from '@propertyos/ui';

/**
 * The report centre. Every report is described once here: where its data comes from, what to ask the reader (period, grouping), the headline
 * figures, the columns, and where a row leads to next. The page that shows them is the same for all, so a new report is one entry.
 *
 * Drill-down: a row's link opens the next report with the choice carried in the address (for example the property), so a figure can be
 * followed from the portfolio down to the single payment behind it, and the browser's back button comes back up.
 */

type Money = (v: unknown) => string;
const usd0: Money = (v) => fmt.usd(Number(v ?? 0));
const usd: Money = (v) => fmt.usd(Number(v ?? 0), true);

interface Param { name: string; label: string; type: 'date' | 'select'; options?: { value: string; label: string }[]; fallback?: string }
interface Drill { label: string; report: string; params: (row: any) => Record<string, string>; context?: (row: any) => string }
interface Report {
  key: string; title: string; group: 'Portfolio' | 'Leasing' | 'Money' | 'Repairs'; blurb: string; icon: LucideIcon; endpoint: string; personas: Persona[];
  params?: Param[]; sort?: { key: string; dir: 'asc' | 'desc' };
  kpis: (s: any, p: Record<string, string>) => { label: string; value: ReactNode; sub?: string; emphasis?: boolean }[];
  columns: (c: { property: (id: string) => string }) => Column<any>[]; drills?: Drill[];
}

const STAFF: Persona[] = ['admin', 'manager'];
const MONEY: Persona[] = ['admin', 'manager', 'accountant', 'owner'];
const LEASING: Persona[] = ['admin', 'manager', 'broker', 'agent'];
const OWNER_AND_STAFF: Persona[] = ['admin', 'manager', 'owner'];
const monthsBack = (n: number) => { const d = new Date(); d.setMonth(d.getMonth() - n, 1); return d.toISOString().slice(0, 10); };
const today = () => new Date().toISOString().slice(0, 10);
const PERIOD: Param[] = [{ name: 'from', label: 'From', type: 'date' }, { name: 'to', label: 'To', type: 'date' }];

export const REPORTS: Report[] = [
  { key: 'rent-roll', title: 'Rent roll', group: 'Portfolio', icon: DoorOpen, endpoint: '/api/leasing/reports/rent-roll', personas: [...OWNER_AND_STAFF, 'accountant', 'broker', 'agent'],
    blurb: 'Every unit with who lives in it, the lease dates, the rent, and the gap between that rent and what the unit could earn.',
    kpis: (s) => [{ label: 'Units', value: s.units }, { label: 'Occupied', value: s.occupied }, { label: 'Monthly rent', value: usd0(s.monthly_rent), emphasis: true }, { label: 'Market potential', value: usd0(s.market_potential) }, { label: 'Gap to market', value: usd0(s.gap_to_market), sub: 'rent not being earned' }],
    columns: () => [
      { key: 'property', header: 'Property' }, { key: 'unit', header: 'Unit' },
      { key: 'status', header: 'Status', render: (r) => <Badge tone={r.status === 'OCCUPIED' ? 'good' : 'warn'}>{r.status === 'OCCUPIED' ? 'Occupied' : 'Vacant'}</Badge> },
      { key: 'tenant_name', header: 'Resident', render: (r) => r.tenant_name ?? '–' },
      { key: 'end_date', header: 'Lease ends', hideOnMobile: true, value: (r) => r.end_date ?? '', render: (r) => (r.end_date ? fmt.date(r.end_date) : '–') },
      { key: 'rent', header: 'Rent', align: 'right', value: (r) => Number(r.rent ?? 0), render: (r) => (r.rent == null ? '–' : usd0(r.rent)) },
      { key: 'market_rent', header: 'Market rent', align: 'right', hideOnMobile: true, value: (r) => Number(r.market_rent), render: (r) => usd0(r.market_rent) },
      { key: 'gap_to_market', header: 'Gap', align: 'right', value: (r) => Number(r.gap_to_market), render: (r) => usd0(r.gap_to_market) },
    ] },
  { key: 'occupancy', title: 'Occupancy and vacancy', group: 'Portfolio', icon: Building2, endpoint: '/api/leasing/reports/occupancy', personas: [...OWNER_AND_STAFF, 'accountant', 'broker', 'agent'], sort: { key: 'occupancy_pct', dir: 'asc' },
    blurb: 'How full each property is, what the empty units cost every month, and how long they have been empty.',
    kpis: (s) => [{ label: 'Units', value: s.units }, { label: 'Occupancy', value: `${s.occupancy_pct}%`, emphasis: true }, { label: 'Vacant', value: s.vacant }, { label: 'Lost each month', value: usd0(s.vacancy_loss), sub: 'at market rent' }],
    columns: () => [
      { key: 'property', header: 'Property', render: (r) => <><span className="font-semibold">{r.property}</span><span className="block text-xs text-graphite-600">{r.city}, {r.state}</span></> },
      { key: 'units', header: 'Units', align: 'right' }, { key: 'occupied', header: 'Occupied', align: 'right' }, { key: 'vacant', header: 'Vacant', align: 'right', render: (r) => (Number(r.vacant) ? <Badge tone="warn">{r.vacant}</Badge> : '0') },
      { key: 'occupancy_pct', header: 'Occupancy', align: 'right', value: (r) => Number(r.occupancy_pct), render: (r) => <Badge tone={Number(r.occupancy_pct) >= 95 ? 'good' : Number(r.occupancy_pct) >= 85 ? 'warn' : 'crit'}>{r.occupancy_pct}%</Badge> },
      { key: 'vacancy_loss', header: 'Lost each month', align: 'right', value: (r) => Number(r.vacancy_loss), render: (r) => usd0(r.vacancy_loss) },
      { key: 'avg_days_vacant', header: 'Avg. days empty', align: 'right', hideOnMobile: true },
    ],
    drills: [{ label: 'Every unit', report: 'rent-roll', params: (r) => ({ propertyId: r.property_id }), context: (r) => r.property }, { label: 'Empty units', report: 'vacancies', params: (r) => ({ propertyId: r.property_id }), context: (r) => r.property }] },
  { key: 'vacancies', title: 'Vacant units', group: 'Portfolio', icon: DoorOpen, endpoint: '/api/leasing/reports/vacancies', personas: [...OWNER_AND_STAFF, 'broker', 'agent'], sort: { key: 'days_vacant', dir: 'desc' },
    blurb: 'Units standing empty, longest first, with whether they are listed.',
    kpis: (s) => [{ label: 'Vacant units', value: s.vacant, emphasis: true }, { label: 'Lost each month', value: usd0(s.lost_per_month) }, { label: 'Average days empty', value: s.avg_days_vacant }],
    columns: () => [
      { key: 'property', header: 'Property' }, { key: 'unit', header: 'Unit' }, { key: 'beds', header: 'Beds', align: 'right' }, { key: 'baths', header: 'Baths', align: 'right', hideOnMobile: true },
      { key: 'sqft', header: 'Sq ft', align: 'right', hideOnMobile: true }, { key: 'market_rent', header: 'Market rent', align: 'right', value: (r) => Number(r.market_rent), render: (r) => usd0(r.market_rent) },
      { key: 'days_vacant', header: 'Days empty', align: 'right', render: (r) => <Badge tone={Number(r.days_vacant) > 30 ? 'crit' : Number(r.days_vacant) > 14 ? 'warn' : 'neutral'}>{r.days_vacant}</Badge> },
      { key: 'listing_status', header: 'Listing', render: (r) => (r.listing_status ? fmt.title(r.listing_status) : <Badge tone="warn">Not listed</Badge>) },
    ] },
  { key: 'expirations', title: 'Lease expirations', group: 'Leasing', icon: CalendarClock, endpoint: '/api/leasing/reports/expirations', personas: [...OWNER_AND_STAFF, 'broker', 'agent'], sort: { key: 'end_date', dir: 'asc' },
    blurb: 'Leases ending soon, with whether a renewal has been offered. The rent at risk is what leaves if nobody renews.',
    params: [{ name: 'days', label: 'Ending within', type: 'select', fallback: '120', options: [30, 60, 90, 120, 180, 365].map((d) => ({ value: String(d), label: `${d} days` })) }],
    kpis: (s) => [{ label: 'Leases ending', value: s.ending, emphasis: true }, { label: 'Within 30 days', value: s.within_30 }, { label: 'Renewal offered', value: s.renewal_offered }, { label: 'Rent at risk', value: usd0(s.monthly_rent_at_risk), sub: 'per month' }],
    columns: () => [
      { key: 'property', header: 'Property' }, { key: 'unit', header: 'Unit' }, { key: 'tenant_name', header: 'Resident' },
      { key: 'end_date', header: 'Ends', value: (r) => r.end_date, render: (r) => fmt.date(r.end_date) },
      { key: 'days_left', header: 'Days left', align: 'right', render: (r) => <Badge tone={Number(r.days_left) <= 30 ? 'crit' : Number(r.days_left) <= 60 ? 'warn' : 'neutral'}>{r.days_left}</Badge> },
      { key: 'rent', header: 'Rent', align: 'right', value: (r) => Number(r.rent), render: (r) => usd0(r.rent) },
      { key: 'status', header: 'Status', render: (r) => <Badge tone={r.status === 'RENEWAL_OFFERED' ? 'good' : r.status === 'NOTICE_GIVEN' ? 'crit' : 'neutral'}>{fmt.title(r.status)}</Badge> },
    ] },
  { key: 'funnel', title: 'Leasing funnel', group: 'Leasing', icon: Filter, endpoint: '/api/leasing/reports/funnel', personas: LEASING, params: PERIOD,
    blurb: 'How enquiries become leases, by property: enquiries, tours, applications, approvals and signed leases.',
    kpis: (s) => [{ label: 'Enquiries', value: s.enquiries }, { label: 'Tours', value: s.tours }, { label: 'Applications', value: s.applications }, { label: 'Approved', value: s.approved }, { label: 'Leases', value: s.leases, emphasis: true, sub: s.enquiryToLeasePct ? `${s.enquiryToLeasePct}% of enquiries` : undefined }],
    columns: () => [
      { key: 'property', header: 'Property' }, { key: 'enquiries', header: 'Enquiries', align: 'right' }, { key: 'tours', header: 'Tours', align: 'right' }, { key: 'tours_attended', header: 'Attended', align: 'right', hideOnMobile: true },
      { key: 'applications', header: 'Applications', align: 'right' }, { key: 'approved', header: 'Approved', align: 'right' }, { key: 'leases', header: 'Leases', align: 'right' },
    ] },
  { key: 'ratings', title: 'Resident ratings', group: 'Leasing', icon: Star, endpoint: '/api/leasing/reports/ratings', personas: OWNER_AND_STAFF, sort: { key: 'average', dir: 'asc' },
    blurb: 'What residents say about each property and unit. The lowest-rated come first, so you see where to act.',
    kpis: (s) => [{ label: 'Ratings', value: s.ratings, emphasis: true }, { label: 'Average', value: Number(s.average ?? 0).toFixed(2) }, { label: 'Low (1 or 2 stars)', value: s.low }],
    columns: () => [
      { key: 'subject_type', header: 'Of', render: (r) => (r.subject_type === 'UNIT' ? 'Unit' : 'Property') },
      { key: 'property', header: 'Property' }, { key: 'unit', header: 'Unit', render: (r) => r.unit ?? '–' },
      { key: 'average', header: 'Rating', value: (r) => Number(r.average), render: (r) => <Stars value={r.average} count={r.ratings} /> },
      { key: 'low_ratings', header: 'Low ratings', align: 'right' }, { key: 'latest', header: 'Latest', hideOnMobile: true, value: (r) => r.latest, render: (r) => fmt.date(r.latest) },
    ] },
  { key: 'receivables', title: 'Who owes what', group: 'Money', icon: HandCoins, endpoint: '/api/billing/reports/receivables', personas: ['admin', 'manager', 'accountant'], sort: { key: 'balance', dir: 'desc' },
    blurb: 'Every resident with a balance, and how old it is. Start with the oldest.',
    kpis: (s) => [{ label: 'Outstanding', value: usd0(s.outstanding), emphasis: true }, { label: 'Overdue', value: usd0(s.overdue) }, { label: 'Residents overdue', value: s.residents_overdue }, { label: 'Over 60 days', value: usd0(s.over_60_days) }],
    columns: () => [
      { key: 'tenant_name', header: 'Resident', render: (r) => <><span className="font-semibold">{r.tenant_name}</span><span className="block text-xs text-graphite-600">{r.unit_label}</span></> },
      { key: 'balance', header: 'Balance', align: 'right', value: (r) => Number(r.balance), render: (r) => <b>{usd(r.balance)}</b> },
      { key: 'not_due', header: 'Not yet due', align: 'right', hideOnMobile: true, value: (r) => Number(r.not_due), render: (r) => usd0(r.not_due) },
      { key: 'days_1_30', header: '1-30', align: 'right', value: (r) => Number(r.days_1_30), render: (r) => usd0(r.days_1_30) },
      { key: 'days_31_60', header: '31-60', align: 'right', value: (r) => Number(r.days_31_60), render: (r) => usd0(r.days_31_60) },
      { key: 'days_61_90', header: '61-90', align: 'right', hideOnMobile: true, value: (r) => Number(r.days_61_90), render: (r) => usd0(r.days_61_90) },
      { key: 'days_over_90', header: 'Over 90', align: 'right', hideOnMobile: true, value: (r) => Number(r.days_over_90), render: (r) => (Number(r.days_over_90) ? <Badge tone="crit">{usd0(r.days_over_90)}</Badge> : '$0') },
      { key: 'oldest_due', header: 'Oldest due', hideOnMobile: true, value: (r) => r.oldest_due, render: (r) => fmt.date(r.oldest_due) },
    ],
    drills: [{ label: 'Account', report: 'transactions', params: (r) => ({ tenantId: r.tenant_id }), context: (r) => r.tenant_name }] },
  { key: 'income', title: 'Income and expenses', group: 'Money', icon: TrendingUp, endpoint: '/api/billing/reports/income', personas: MONEY, params: PERIOD,
    blurb: 'What each property charged, collected and spent, month by month, and what was left.',
    kpis: (s) => [{ label: 'Charged', value: usd0(s.charged) }, { label: 'Collected', value: usd0(s.collected), sub: `${s.collectionRatePct ?? 0}% of charged` }, { label: 'Expenses', value: usd0(s.expenses) }, { label: 'Kept', value: usd0(s.net), emphasis: true }],
    columns: ({ property }) => [
      { key: 'property_id', header: 'Property', render: (r) => property(r.property_id) }, { key: 'month', header: 'Month' },
      { key: 'charged', header: 'Charged', align: 'right', value: (r) => Number(r.charged), render: (r) => usd0(r.charged) },
      { key: 'collected', header: 'Collected', align: 'right', value: (r) => Number(r.collected), render: (r) => usd0(r.collected) },
      { key: 'expenses', header: 'Expenses', align: 'right', value: (r) => Number(r.expenses), render: (r) => usd0(r.expenses) },
      { key: 'net', header: 'Kept', align: 'right', value: (r) => Number(r.net), render: (r) => <b className={Number(r.net) < 0 ? 'text-crit-700' : ''}>{usd0(r.net)}</b> },
    ],
    drills: [{ label: 'Transactions', report: 'transactions', params: (r) => ({ propertyId: r.property_id, month: r.month }), context: (r) => r.month }] },
  { key: 'transactions', title: 'Transactions', group: 'Money', icon: Receipt, endpoint: '/api/billing/reports/transactions', personas: MONEY, sort: { key: 'date', dir: 'desc' },
    blurb: 'Every payment, charge and expense behind a figure.',
    kpis: (s) => [{ label: 'Collected', value: usd(s.collected), emphasis: true }, { label: 'Charged', value: usd(s.charged) }, { label: 'Expenses', value: usd(s.expenses) }],
    columns: () => [
      { key: 'date', header: 'Date', value: (r) => r.date, render: (r) => fmt.date(r.date) },
      { key: 'type', header: 'Type', render: (r) => <Badge tone={r.type === 'PAYMENT' ? 'good' : r.type === 'EXPENSE' ? 'warn' : 'brand'}>{fmt.title(r.type)}</Badge> },
      { key: 'who', header: 'Resident', render: (r) => r.who ?? '–' }, { key: 'description', header: 'What' },
      { key: 'amount', header: 'Amount', align: 'right', value: (r) => Number(r.amount), render: (r) => usd(r.amount) },
      { key: 'status', header: 'Status', hideOnMobile: true, render: (r) => fmt.title(String(r.status)) },
    ] },
  { key: 'payments', title: 'Payments', group: 'Money', icon: Wallet, endpoint: '/api/billing/reports/payments', personas: ['admin', 'manager', 'accountant'], params: PERIOD,
    blurb: 'How rent is being paid: by month, method and whether it was autopay, and how often payments fail.',
    kpis: (s) => [{ label: 'Collected', value: usd0(s.collected), emphasis: true }, { label: 'Payments', value: s.succeeded }, { label: 'Failed', value: s.failed, sub: `${s.failure_pct ?? 0}% of attempts` }, { label: 'Processing fees', value: usd(s.fees) }],
    columns: () => [
      { key: 'month', header: 'Month' }, { key: 'method', header: 'Method', render: (r) => (r.method === 'ACH' ? 'Bank transfer' : r.method === 'CARD' ? 'Card' : r.method) },
      { key: 'source', header: 'How', render: (r) => (r.source === 'AUTOPAY' ? 'Autopay' : 'Paid by hand') }, { key: 'payments', header: 'Payments', align: 'right' },
      { key: 'collected', header: 'Collected', align: 'right', value: (r) => Number(r.collected), render: (r) => usd0(r.collected) },
      { key: 'failed', header: 'Failed', align: 'right', render: (r) => (Number(r.failed) ? <Badge tone="crit">{r.failed}</Badge> : '0') },
      { key: 'fees', header: 'Fees', align: 'right', hideOnMobile: true, value: (r) => Number(r.fees), render: (r) => usd(r.fees) },
    ] },
  { key: 'fees', title: 'Fees and charges', group: 'Money', icon: FileText, endpoint: '/api/billing/reports/fees', personas: ['admin', 'manager', 'accountant'], params: PERIOD,
    blurb: 'Late fees, returned-payment fees and other charges: how much was charged, how much was collected and how much was cancelled. Includes sales tax.',
    kpis: (s) => [{ label: 'Charged', value: usd0(s.charged), emphasis: true }, { label: 'Collected', value: usd0(s.collected) }, { label: 'Cancelled', value: usd0(s.cancelled) }, { label: 'Sales tax', value: usd(s.tax) }],
    columns: () => [
      { key: 'month', header: 'Month' }, { key: 'kind', header: 'Charge', render: (r) => fmt.title(r.kind) }, { key: 'charges', header: 'Count', align: 'right' },
      { key: 'charged', header: 'Charged', align: 'right', value: (r) => Number(r.charged), render: (r) => usd0(r.charged) }, { key: 'collected', header: 'Collected', align: 'right', value: (r) => Number(r.collected), render: (r) => usd0(r.collected) },
      { key: 'cancelled', header: 'Cancelled', align: 'right', hideOnMobile: true, value: (r) => Number(r.cancelled), render: (r) => usd0(r.cancelled) },
      { key: 'tax', header: 'Tax', align: 'right', hideOnMobile: true, value: (r) => Number(r.tax), render: (r) => usd(r.tax) },
    ] },
  { key: 'costs', title: 'Repair costs', group: 'Repairs', icon: Hammer, endpoint: '/api/maintenance/reports/costs', personas: MONEY.concat('broker'), sort: { key: 'total_cost', dir: 'desc' },
    blurb: 'What repairs cost and how fast they are done. Group by property, category, vendor or priority, then open the jobs behind any line.',
    params: [{ name: 'by', label: 'Group by', type: 'select', fallback: 'property', options: [{ value: 'property', label: 'Property' }, { value: 'category', label: 'Category' }, { value: 'vendor', label: 'Vendor' }, { value: 'priority', label: 'Priority' }] }, ...PERIOD],
    kpis: (s) => [{ label: 'Jobs', value: s.jobs }, { label: 'Completed', value: s.completed }, { label: 'Total cost', value: usd0(s.total_cost), emphasis: true }, { label: 'Average job', value: usd(s.avg_cost) }, { label: 'Late (SLA)', value: s.sla_breaches }, { label: 'Emergencies', value: s.emergencies }],
    columns: () => [
      { key: 'name', header: 'Name', render: (r) => <span className="font-semibold">{fmt.title(String(r.name))}</span> }, { key: 'jobs', header: 'Jobs', align: 'right' }, { key: 'completed', header: 'Completed', align: 'right' },
      { key: 'total_cost', header: 'Total cost', align: 'right', value: (r) => Number(r.total_cost), render: (r) => usd0(r.total_cost) },
      { key: 'avg_cost', header: 'Average', align: 'right', value: (r) => Number(r.avg_cost), render: (r) => usd(r.avg_cost) },
      { key: 'avg_days_to_finish', header: 'Days to finish', align: 'right', hideOnMobile: true },
      { key: 'sla_breaches', header: 'Late', align: 'right', render: (r) => (Number(r.sla_breaches) ? <Badge tone="warn">{r.sla_breaches}</Badge> : '0') },
      { key: 'emergencies', header: 'Emergencies', align: 'right', hideOnMobile: true },
    ],
    drills: [{ label: 'Jobs', report: 'jobs', context: (r) => String(r.name), params: (r) => ({}) }] },
  { key: 'jobs', title: 'Repair jobs', group: 'Repairs', icon: Hammer, endpoint: '/api/maintenance/reports/jobs', personas: MONEY.concat('broker'), params: PERIOD, sort: { key: 'created_at', dir: 'desc' },
    blurb: 'The repair jobs behind a figure, newest first.',
    kpis: (s) => [{ label: 'Jobs', value: s.jobs, emphasis: true }, { label: 'Cost', value: usd0(s.total_cost) }, { label: 'Estimated', value: usd0(s.estimated) }],
    columns: () => [
      { key: 'title', header: 'Job', render: (r) => <Link to={`/app/maintenance/${r.id}`} className="font-semibold text-federal-800 underline">{r.title}</Link> },
      { key: 'property_name', header: 'Property' }, { key: 'unit_label', header: 'Unit', hideOnMobile: true }, { key: 'category', header: 'Category', render: (r) => fmt.title(r.category) },
      { key: 'priority', header: 'Priority', render: (r) => <Badge tone={r.priority === 'EMERGENCY' ? 'crit' : r.priority === 'HIGH' ? 'warn' : 'neutral'}>{fmt.title(r.priority)}</Badge> },
      { key: 'status', header: 'Status', render: (r) => fmt.title(r.status) }, { key: 'vendor_name', header: 'Vendor', hideOnMobile: true, render: (r) => r.vendor_name ?? '–' },
      { key: 'final_cost', header: 'Cost', align: 'right', value: (r) => Number(r.final_cost ?? 0), render: (r) => (r.final_cost == null ? '–' : usd(r.final_cost)) },
    ] },
  { key: 'vendors', title: 'Vendor scorecard', group: 'Repairs', icon: Truck, endpoint: '/api/maintenance/reports/vendors', personas: STAFF.concat('broker'), params: PERIOD, sort: { key: 'completed', dir: 'desc' },
    blurb: 'Every vendor side by side: jobs, cost against estimate, how often they are on time, and what people rate them.',
    kpis: (s) => [{ label: 'Vendors', value: s.vendors, emphasis: true }, { label: 'Active', value: s.active }],
    columns: () => [
      { key: 'name', header: 'Vendor', render: (r) => <><span className="font-semibold">{r.name}</span>{!r.active && <Badge tone="neutral">Archived</Badge>}</> },
      { key: 'jobs', header: 'Jobs', align: 'right' }, { key: 'completed', header: 'Completed', align: 'right' },
      { key: 'total_cost', header: 'Total cost', align: 'right', value: (r) => Number(r.total_cost), render: (r) => usd0(r.total_cost) },
      { key: 'cost_vs_estimate', header: 'Cost vs estimate', align: 'right', hideOnMobile: true, value: (r) => Number(r.cost_vs_estimate), render: (r) => (Number(r.cost_vs_estimate) ? `${Math.round(Number(r.cost_vs_estimate) * 100)}%` : '–') },
      { key: 'on_time_pct', header: 'On time', align: 'right', value: (r) => Number(r.on_time_pct), render: (r) => <Badge tone={Number(r.on_time_pct) >= 90 ? 'good' : Number(r.on_time_pct) >= 75 ? 'warn' : 'crit'}>{r.on_time_pct}%</Badge> },
      { key: 'rating', header: 'Rating', value: (r) => Number(r.rating), render: (r) => <Stars value={r.rating} count={r.ratings} /> },
    ],
    drills: [{ label: 'Jobs', report: 'jobs', params: (r) => ({ vendorId: r.vendor_id }), context: (r) => r.name }] },
];

const GROUPS: Report['group'][] = ['Portfolio', 'Leasing', 'Money', 'Repairs'];
const GROUP_BLURB: Record<string, string> = { Portfolio: 'The buildings, the units and who is in them.', Leasing: 'Enquiries, leases ending, and what residents think.', Money: 'Who owes what, what came in, what went out.', Repairs: 'What repairs cost and who does them well.' };

export function ReportHub() {
  const { session } = useSession();
  const persona = session ? personaOf(session) : undefined;
  const mine = REPORTS.filter((r) => !persona || r.personas.includes(persona));
  return (<>
    <PageHeader title="Reports" description="Pick a report. Every report can be filtered, sorted, searched and downloaded, and most let you click a line to see what is behind it." />
    {mine.length === 0 ? <EmptyState icon={<BarChart3 className="h-8 w-8" />} title="No reports for your role" body="Your balance, payments and requests are under Payments and Maintenance." /> :
      GROUPS.map((g) => { const items = mine.filter((r) => r.group === g); return items.length === 0 ? null : (
        <section key={g} className="mb-8">
          <h2 className="text-lg font-bold text-graphite-950">{g}</h2><p className="mb-3 text-sm text-graphite-600">{GROUP_BLURB[g]}</p>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((r) => (
              <Link key={r.key} to={r.key} className="group flex gap-3 rounded-md border border-limestone-200 bg-white p-4 shadow-panel transition hover:border-federal-300 hover:shadow-lift">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800"><r.icon size={20} aria-hidden /></span>
                <span className="min-w-0"><span className="block text-sm font-bold text-graphite-950 group-hover:underline">{r.title}</span><span className="mt-0.5 block text-xs text-graphite-600">{r.blurb}</span></span>
              </Link>))}
          </div>
        </section>); })}
  </>);
}

/** Property names by id, for reports that only carry the id. */
function usePropertyNames() {
  const { data } = useApi<any>('/api/leasing/properties?page=1&size=200');
  return useMemo(() => {
    const m = new Map<string, string>();
    for (const p of (data?.rows ?? data ?? []) as any[]) m.set(p.id, p.name);
    return (id: string) => m.get(id) ?? 'Property';
  }, [data]);
}

export function ReportPage() {
  const { key } = useParams();
  const report = REPORTS.find((r) => r.key === key);
  const [search, setSearch] = useSearchParams();
  const nav = useNavigate();
  const property = usePropertyNames();
  const query = useMemo(() => {
    const q = new URLSearchParams();
    if (!report) return q;
    for (const p of report.params ?? []) {
      const v = search.get(p.name) ?? (p.name === 'from' ? (report.key === 'funnel' ? '' : monthsBack(report.key === 'costs' || report.key === 'jobs' || report.key === 'vendors' ? 12 : 5)) : p.name === 'to' ? today() : p.fallback ?? '');
      if (v) q.set(p.name, v);
    }
    for (const [k, v] of search.entries()) if (!['context', 'from', 'to', 'days', 'by'].includes(k) && v) q.set(k, v);
    return q;
  }, [report, search]);
  const qs = query.toString();
  const list = usePagedApi<any>(report ? `${report.endpoint}${qs ? `?${qs}` : ''}` : null, report?.sort ? { initialSort: report.sort } : {});
  if (!report) return <EmptyState title="We couldn’t find that report" action={<Link to=".."><Button>All reports</Button></Link>} />;
  const summary = list.data?.summary;
  const context = search.get('context');
  const setParam = (name: string, value: string) => { const n = new URLSearchParams(search); if (value) n.set(name, value); else n.delete(name); setSearch(n, { replace: true }); };
  const go = (d: Drill, row: any) => { const p = new URLSearchParams(d.params(row)); if (d.context) p.set('context', d.context(row)); if (report.key === 'costs' && d.report === 'jobs') { const by = search.get('by') ?? 'property'; const id = row.group_id; if (by === 'property') p.set('propertyId', id); else if (by === 'vendor') p.set('vendorId', id); else if (by === 'category') p.set('category', id); else p.set('priority', id); } nav(`/app/insights/${d.report}?${p.toString()}`); };
  const cols = report.columns({ property });
  const withDrill: Column<any>[] = report.drills ? [...cols, { key: '__drill', header: '', render: (r) => <div className="flex justify-end gap-1.5">{report.drills!.map((d) => <Button key={d.label} size="sm" variant="secondary" onClick={(e) => { e.stopPropagation(); go(d, r); }}>{d.label}</Button>)}</div> }] : cols;
  const kpis = summary ? report.kpis(summary, Object.fromEntries(query.entries())) : [];
  return (<>
    <PageHeader title={report.title} description={report.blurb} back={<button type="button" onClick={() => nav(-1)} className="inline-flex items-center gap-1 text-sm font-semibold text-federal-900 hover:underline"><ArrowLeft size={14} aria-hidden />{context ? 'Back' : 'All reports'}</button>}
      meta={context ? <Badge tone="brand">{context}</Badge> : undefined} />
    {(report.params ?? []).length > 0 && <div className="mb-4 flex flex-wrap items-end gap-3">
      {report.params!.map((p) => { const v = query.get(p.name) ?? ''; return (
        <Field key={p.name} label={p.label}>
          {p.type === 'date' ? <Input type="date" value={v} onChange={(e) => setParam(p.name, e.target.value)} aria-label={p.label} />
            : <Select aria-label={p.label} value={v} onChange={(e) => setParam(p.name, e.target.value)}>{p.options!.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</Select>}
        </Field>); })}
    </div>}
    {!summary ? <Skeleton className="mb-6 h-24" /> : kpis.length > 0 && <StatStrip className="mb-6">{kpis.map((k) => <Stat key={k.label} label={k.label} value={k.value} sub={k.sub} emphasis={k.emphasis} />)}</StatStrip>}
    <Panel padded={false}><DataTable rows={list.rows} server={list.server} columns={withDrill} rowKey={(r) => r.id ?? r.unit_id ?? r.lease_id ?? r.tenant_id ?? r.vendor_id ?? r.group_id ?? `${r.property_id}-${r.month}-${r.method ?? r.kind ?? ''}-${r.source ?? ''}-${r.subject_id ?? ''}-${r.type ?? ''}-${r.ref ?? ''}`}
      loading={list.isLoading} exportName={report.key} initialSort={report.sort} empty={{ title: 'Nothing to show', body: 'There is no data for these choices. Try a wider period.' }} /></Panel>
    <p className="mt-3 flex items-center gap-1.5 text-xs text-graphite-500"><ScrollText size={12} aria-hidden />Figures follow what your role is allowed to see. Download the table with the export button.</p>
  </>);
}
