import { Link, Route, Routes, useNavigate } from 'react-router-dom';
import { AiTools } from './AiTools';
import { motion } from 'framer-motion';
import {
  MicroApp, PageHeader, Panel, Stat, StatStrip, Button, StatusPill, Badge, AiInsight, DataTable, type Column, TrendChart, CompareChart, RankBars, EmptyState, Skeleton,
  useApi, useAction, api, fmt, useSession, personaOf, SlaClock, type Persona,
} from '@propertyos/ui';

const go = (p: string) => (p.startsWith('/app') ? p : '/app' + p);
const Rise = ({ children, i = 0 }: { children: React.ReactNode; i?: number }) => <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.05, duration: 0.35 }}>{children}</motion.div>;

function greeting(name?: string) {
  const h = new Date().getHours();
  return `${h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'}${name ? ', ' + name.split(' ')[0] : ''}`;
}

function Opps({ limit = 3 }: { limit?: number }) {
  const nav = useNavigate();
  const { data } = useApi<{ items: any[] }>('/api/ai/opportunities');
  const items = (data?.items ?? []).slice(0, limit);
  if (!items.length) return null;
  return (<Panel title="Where to earn more" description="Found by AI from your own numbers. Reasoning shown for each.">
    <div className="space-y-3">{items.map((o, i) => <AiInsight key={i} title={o.title} confidence={o.confidence} footer={<Button size="sm" variant="primary" onClick={() => nav(go(o.path))}>{o.actionLabel}</Button>}>{o.detail}</AiInsight>)}</div></Panel>);
}

function Portfolio({ persona }: { persona: Persona }) {
  const { session, can } = useSession();
  const nav = useNavigate();
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const { data: bill } = useApi<any>('/api/billing/summary');
  const { data: aging } = useApi<any>('/api/billing/aging');
  const { data: mt } = useApi<any>('/api/maintenance/stats');
  const { data: auto } = useApi<any>(can('AUTOMATION:VIEW') ? '/api/workflow/automation/summary' : null, { retry: false });
  const units = (props ?? []).reduce((s, p) => s + p.units, 0), occ = (props ?? []).reduce((s, p) => s + p.occupied, 0);
  const roll = (props ?? []).reduce((s, p) => s + Number(p.rent_roll), 0), market = (props ?? []).reduce((s, p) => s + Number(p.market_rent_total), 0);
  const owner = persona === 'owner';
  const trend = (bill?.trend ?? []).map((t: any) => ({ ...t, billed: Number(t.billed), collected: Number(t.collected) }));
  return (<>
    <PageHeader title={greeting(session?.name)} description={owner ? 'How your properties are performing.' : `Portfolio overview for ${session?.organization.name}.`} />
    <Rise><StatStrip className="mb-6">
      <Stat label="Occupancy" value={units ? fmt.pct((occ / units) * 100) : '—'} emphasis sub={`${occ} of ${units} units`} />
      <Stat label="Monthly rent roll" value={fmt.usd(roll)} sub={market > roll ? `${fmt.usd(market - roll)} below market` : undefined} />
      {bill && <Stat label="Collected this month" value={fmt.usd(bill.collected_this_month)} sub={`${fmt.pct(bill.collection_rate)} collection rate`} />}
      {bill && <Stat label="Overdue" value={fmt.usd(bill.overdue_total)} sub={`${bill.delinquent_tenants} residents`} />}
      {mt && <Stat label="Open repairs" value={mt.open_orders} sub={`${mt.open_emergencies} emergencies`} />}
    </StatStrip></Rise>
    <div className="grid gap-6 xl:grid-cols-[1fr_420px]">
      <div className="space-y-6">
        <Rise i={1}><Panel title="Rent billed and collected">{trend.length ? <CompareChart data={trend} x="month" bars={[{ key: 'billed', label: 'Billed' }, { key: 'collected', label: 'Collected' }]} /> : <Skeleton className="h-56" />}</Panel></Rise>
        <Rise i={2}><Panel title="Properties" padded={false}><DataTable searchable={false} rows={props} rowKey={(p) => p.id} onRowClick={() => nav('/app/leasing/properties')} columns={[
          { key: 'name', header: 'Property', render: (p: any) => <b>{p.name}</b> }, { key: 'occ', header: 'Occupied', align: 'right', render: (p: any) => `${p.occupied}/${p.units}` },
          { key: 'rent_roll', header: 'Rent roll', align: 'right', render: (p: any) => fmt.usd(p.rent_roll) }, { key: 'vacant', header: 'Vacant', align: 'right', render: (p: any) => (p.vacant ? <Badge tone="warn">{p.vacant}</Badge> : '0') }]} /></Panel></Rise>
      </div>
      <div className="space-y-6">
        {persona !== 'accountant' && <Rise i={2}><Opps /></Rise>}
        {auto && <Rise i={3}><Panel title="Automation"><div className="text-3xl font-extrabold tabular-nums">{auto.hours_saved_30d} h</div><p className="mt-1 text-sm text-graphite-600">saved over the last 30 days by {auto.active_rules} active rules ({auto.runs_30d} runs).</p><Link to="/app/admin/automations" className="mt-3 inline-block text-sm font-semibold text-federal-900 hover:underline">Manage automations</Link></Panel></Rise>}
        {aging && <Rise i={4}><Panel title="Overdue rent by age"><RankBars data={[{ k: '1–30 days', v: Number(aging.d1_30) }, { k: '31–60 days', v: Number(aging.d31_60) }, { k: '61+ days', v: Number(aging.d61_plus) }]} label="k" value="v" format="usd" /></Panel></Rise>}
      </div>
    </div>
  </>);
}

function OwnerHome() {
  const { session } = useSession();
  const { data: wos } = useApi<any[]>('/api/maintenance/work-orders?status=AWAITING_APPROVAL');
  const { data: apps } = useApi<any[]>('/api/leasing/applications?status=OWNER_REVIEW');
  const { data: st } = useApi<any[]>('/api/billing/statements');
  const approve = useAction((id: string) => api.post(`/api/maintenance/work-orders/${id}/actions/APPROVE`, {}), { success: 'Approved', invalidate: ['/api/maintenance'] });
  const nav = useNavigate();
  const pending = (wos?.length ?? 0) + (apps?.length ?? 0);
  return (<>
    {pending > 0 && <Panel title={`${pending} decision${pending > 1 ? 's' : ''} waiting for you`} tone="alert" className="mb-6">
      <ul className="divide-y divide-limestone-200 text-sm">
        {(wos ?? []).map((w) => <li key={w.id} className="flex items-center justify-between gap-3 py-2"><span><b>{w.title}</b> <span className="text-graphite-500">{w.property_name} · estimate {fmt.usd(w.estimate)}</span></span><Button size="sm" variant="primary" loading={approve.isPending} onClick={() => approve.mutate(w.id)}>Approve</Button></li>)}
        {(apps ?? []).map((a) => <li key={a.id} className="flex items-center justify-between gap-3 py-2"><span><b>Application: {a.applicant_name}</b> <span className="text-graphite-500">{a.unit_label}</span></span><Button size="sm" onClick={() => nav(`/app/leasing/applications/${a.id}`)}>Review</Button></li>)}
      </ul></Panel>}
    <Portfolio persona="owner" />
    {(st?.length ?? 0) > 0 && <Panel className="mt-6" title="Latest statements" padded={false}><DataTable searchable={false} rows={st} rowKey={(r) => r.id} columns={[{ key: 'period', header: 'Period' }, { key: 'rent_collected', header: 'Rent collected', align: 'right', render: (r: any) => fmt.usd(r.rent_collected) }, { key: 'net_payout', header: 'Net payout', align: 'right', render: (r: any) => <b>{fmt.usd(r.net_payout, true)}</b> }]} /></Panel>}
  </>);
}

function TenantHome() {
  const { session } = useSession();
  const { data: my } = useApi<any>('/api/billing/my');
  const { data: lease } = useApi<any>('/api/leasing/leases/mine', { retry: false });
  const { data: wos } = useApi<any[]>('/api/maintenance/work-orders');
  const open = (wos ?? []).filter((w) => !['VERIFIED', 'CLOSED', 'CANCELLED'].includes(w.status));
  return (<>
    <PageHeader title={greeting(session?.name)} description={lease ? `${lease.property_name}, ${lease.unit_label}` : 'Your home at a glance.'} />
    <div className="grid gap-6 lg:grid-cols-3">
      <Rise><Panel title="Rent"><div className="text-[34px] font-extrabold leading-none tabular-nums">{fmt.usd(my?.balance ?? 0, true)}</div><p className="mt-2 text-sm text-graphite-600">{my?.nextDue ? `Due ${fmt.date(my.nextDue.due_date)}` : 'You’re all paid up'}</p><Link to="/app/payments"><Button className="mt-4" variant="primary">Pay rent</Button></Link></Panel></Rise>
      <Rise i={1}><Panel title="Repairs"><div className="text-[34px] font-extrabold leading-none tabular-nums">{open.length}</div><p className="mt-2 text-sm text-graphite-600">open request{open.length === 1 ? '' : 's'}</p><Link to="/app/maintenance"><Button className="mt-4">Request a repair</Button></Link></Panel></Rise>
      <Rise i={2}><Panel title="Lease">{lease ? <><StatusPill status={lease.status} /><p className="mt-2 text-sm text-graphite-700">Ends {fmt.date(lease.end_date)}. Rent {fmt.usd(lease.rent)} a month.</p><Link to="/app/leasing/my-lease"><Button className="mt-4">View lease</Button></Link></> : <p className="text-sm text-graphite-600">No active lease.</p>}</Panel></Rise>
    </div>
    {open.length > 0 && <Panel className="mt-6" title="Your repair requests" padded={false}><DataTable searchable={false} rows={open} rowKey={(w) => w.id} columns={[{ key: 'title', header: 'Request' }, { key: 'status', header: 'Status', render: (w: any) => <StatusPill status={w.status} /> }, { key: 'sla', header: 'Due', render: (w: any) => <SlaClock dueAt={w.sla_due_at} /> }]} /></Panel>}
  </>);
}

function VendorHome() {
  const { session } = useSession(); const nav = useNavigate();
  const { data } = useApi<any[]>('/api/maintenance/work-orders');
  const mine = (data ?? []).filter((w) => !['VERIFIED', 'CLOSED', 'CANCELLED'].includes(w.status));
  return (<><PageHeader title={greeting(session?.name)} description="Jobs assigned to your company." />
    <Panel title="Open jobs" padded={false}><DataTable rows={mine} rowKey={(w) => w.id} onRowClick={(w) => nav(`/app/maintenance/${w.id}`)} columns={[
      { key: 'priority', header: 'Priority', render: (w: any) => <Badge tone={w.priority === 'EMERGENCY' ? 'crit' : 'neutral'}>{w.priority}</Badge> }, { key: 'title', header: 'Job', render: (w: any) => <div><b>{w.title}</b><div className="text-xs text-graphite-500">{w.property_name} · {w.unit_label}</div></div> },
      { key: 'status', header: 'Status', render: (w: any) => <StatusPill status={w.status} /> }, { key: 'sla', header: 'Due', render: (w: any) => <SlaClock dueAt={w.sla_due_at} /> }]} empty={{ title: 'No open jobs' }} /></Panel></>);
}

function PlatformHome() {
  const { data: s } = useApi<any>('/api/identity/platform/summary');
  return (<><PageHeader title="Platform overview" description="Companies and usage across PropertyOS." />
    {s && <StatStrip className="mb-6"><Stat label="Companies" value={s.organizations} emphasis /><Stat label="Users" value={s.users} /><Stat label="Active, 7 days" value={s.activeUsers7d} /></StatStrip>}
    <Link to="/app/admin/platform"><Button variant="primary">Manage companies</Button></Link></>);
}

function Home() {
  const { session } = useSession();
  if (!session) return <Skeleton className="h-64" />;
  const p = personaOf(session);
  if (p === 'tenant') return <TenantHome />;
  if (p === 'vendor') return <VendorHome />;
  if (p === 'platform') return <PlatformHome />;
  if (p === 'owner') return <OwnerHome />;
  return <Portfolio persona={p} />;
}

function Reports() {
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const { data: bill } = useApi<any>('/api/billing/summary');
  const { data: mt } = useApi<any>('/api/maintenance/stats');
  const { data: vendors } = useApi<any[]>('/api/maintenance/vendors', { retry: false });
  const trend = (bill?.trend ?? []).map((t: any) => ({ month: t.month, collected: Number(t.collected) }));
  const byProp = (props ?? []).map((p) => ({ name: p.name, rent: Number(p.rent_roll), vacancy: p.units ? Math.round((p.vacant / p.units) * 100) : 0 }));
  return (<><PageHeader title="Reports" description="Every table can be downloaded as a CSV file." />
    <div className="grid gap-6 lg:grid-cols-2">
      <Panel title="Rent roll by property"><RankBars data={byProp} label="name" value="rent" format="usd" /></Panel>
      <Panel title="Collections by month"><TrendChart data={trend} x="month" y="collected" /></Panel>
      <Panel title="Repair cost by category"><RankBars data={mt?.by_category ?? []} label="category" value="cost" format="usd" /></Panel>
      <Panel title="Repairs by property"><RankBars data={mt?.by_property ?? []} label="property_name" value="count" /></Panel>
    </div>
    <Panel className="mt-6" title="Vendor performance" padded={false}><DataTable rows={vendors} rowKey={(v) => v.id} exportName="vendors" columns={[
      { key: 'name', header: 'Vendor' }, { key: 'rating', header: 'Rating', align: 'right', value: (v: any) => Number(v.rating), render: (v: any) => `${v.rating} ★` }, { key: 'jobs_completed', header: 'Jobs', align: 'right' },
      { key: 'avg_job_cost', header: 'Avg. cost', align: 'right', value: (v: any) => Number(v.avg_job_cost), render: (v: any) => fmt.usd(v.avg_job_cost) }, { key: 'avg_response_hours', header: 'Response (h)', align: 'right' }]} /></Panel></>);
}

export default function App({ view }: { view?: 'home' | 'reports' }) {
  return <MicroApp name="insights">{view === 'reports' ? <Routes><Route path="ai" element={<AiTools />} /><Route path="*" element={<Reports />} /></Routes> : <Home />}</MicroApp>;
}
