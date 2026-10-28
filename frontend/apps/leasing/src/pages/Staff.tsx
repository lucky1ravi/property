import { useState } from 'react';
import { Link, NavLink, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import {
  PageHeader, Panel, Button, StatusPill, Stat, StatStrip, DataTable, type Column, Tabs, KeyValues, ActionBar, WorkflowTrack, Timeline, Field, Input,
  Modal, AiInsight, ConfidenceMeter, EmptyState, Skeleton, Section, useApi, useAction, api, fmt, useSession, Textarea, Badge,
} from '@propertyos/ui';
import { QuickLink, QuickViewProvider } from './QuickView';
import { useBase, APPLICATION_STEPS, LEASE_STEPS, LISTING_STEPS, labelOf } from '../common';

const SubNav = () => {
  const base = useBase();
  const { can } = useSession();
  const items = [['', 'Overview'], ['listings', 'Listings'], ['leads', 'Leads'], ['showings', 'Showings'], ['applications', 'Applications'], ['leases', 'Leases'], ['properties', 'Properties']];
  return (
    <nav className="mb-6 flex gap-1 overflow-x-auto border-b border-limestone-200">
      {items.filter(([k]) => k !== 'properties' || can('PROPERTY:VIEW')).map(([k, label]) => (
        <NavLink key={k} to={`${base}/${k}`} end={k === ''} className={({ isActive }) => `-mb-px whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-semibold ${isActive ? 'border-federal-900 text-federal-900' : 'border-transparent text-graphite-500 hover:text-graphite-800'}`}>{label}</NavLink>
      ))}
    </nav>
  );
};

function Overview() {
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const { data: apps } = useApi<any[]>('/api/leasing/applications');
  const { data: leads } = useApi<any[]>('/api/leasing/leads');
  const units = (props ?? []).reduce((s, p) => s + p.units, 0), occ = (props ?? []).reduce((s, p) => s + p.occupied, 0);
  const roll = (props ?? []).reduce((s, p) => s + Number(p.rent_roll), 0);
  return (
    <>
      <PageHeader title="Leasing" description="From first enquiry to signed lease, with screening and follow-up automated." />
      <SubNav />
      <StatStrip className="mb-6">
        <Stat label="Occupancy" value={units ? fmt.pct((occ / units) * 100) : '—'} emphasis sub={`${occ} of ${units} units`} />
        <Stat label="Monthly rent roll" value={fmt.usd(roll)} />
        <Stat label="Open applications" value={(apps ?? []).filter((a) => !['APPROVED', 'REJECTED', 'WITHDRAWN'].includes(a.status)).length} />
        <Stat label="New leads" value={(leads ?? []).filter((l) => l.status === 'NEW').length} />
      </StatStrip>
      <Properties embedded />
    </>
  );
}

function Properties({ embedded }: { embedded?: boolean }) {
  const { data, isLoading } = useApi<any[]>('/api/leasing/properties');
  const cols: Column<any>[] = [
    { key: 'name', header: 'Property', render: (p) => (<div><QuickLink kind="property" id={p.id}>{p.name}</QuickLink><div className="text-xs text-graphite-500">{p.street}, {p.city}, {p.state}</div></div>) },
    { key: 'owner_name', header: 'Owner', hideOnMobile: true, render: (p) => <QuickLink kind="owner" id={p.owner_id} className="font-medium">{p.owner_name}</QuickLink> },
    { key: 'units', header: 'Units', align: 'right' },
    { key: 'vacant', header: 'Vacant', align: 'right', render: (p) => (p.vacant ? <Badge tone="warn">{p.vacant}</Badge> : '0') },
    { key: 'rent_roll', header: 'Rent roll', align: 'right', value: (p) => Number(p.rent_roll), render: (p) => fmt.usd(p.rent_roll) },
    { key: 'live_listings', header: 'Live listings', align: 'right', hideOnMobile: true },
  ];
  const t = <Panel title="Properties" padded={false}><DataTable rows={data} columns={cols} rowKey={(p) => p.id} loading={isLoading} exportName="properties" empty={{ title: 'No properties yet' }} /></Panel>;
  return embedded ? t : <><PageHeader title="Properties" /><SubNav />{t}</>;
}

function Listings() {
  const nav = useNavigate(); const base = useBase();
  const { data, isLoading } = useApi<any[]>('/api/leasing/listings');
  const cols: Column<any>[] = [
    { key: 'title', header: 'Listing', render: (l) => (<div><div className="font-semibold">{l.title}</div><div className="text-xs text-graphite-500">Available {fmt.shortDate(l.available_date)}</div></div>) },
    { key: 'status', header: 'Status', render: (l) => <StatusPill status={l.status} /> },
    { key: 'rent', header: 'Rent', align: 'right', value: (l) => Number(l.rent), render: (l) => fmt.usd(l.rent) },
    { key: 'views', header: 'Views', align: 'right', hideOnMobile: true },
    { key: 'enquiries', header: 'Enquiries', align: 'right', hideOnMobile: true },
  ];
  return (<><PageHeader title="Listings" description="Draft with AI help, review, then publish to the public site." /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(l) => l.id} loading={isLoading} onRowClick={(l) => nav(`${base}/listings/${l.id}`)} exportName="listings" /></Panel></>);
}

function ListingDetail() {
  const { id } = useParams(); const base = useBase();
  const { data: l, isLoading } = useApi<any>(`/api/leasing/listings/${id}`);
  const [rent, setRent] = useState('');
  const refresh = ['/api/leasing/listings'];
  const assist = useAction(() => api.post<any>(`/api/leasing/listings/${id}/ai-assist`, {}), { success: 'AI draft applied', invalidate: refresh });
  const act = useAction((v: { a: string; reason?: string }) => api.post(`/api/leasing/listings/${id}/actions/${v.a}`, { reason: v.reason }), { success: 'Listing updated', invalidate: refresh });
  const save = useAction(() => api.put(`/api/leasing/listings/${id}`, { rent: Number(rent) }), { success: 'Rent updated', invalidate: refresh, onSuccess: () => setRent('') });
  if (isLoading || !l) return <Skeleton className="h-96" />;
  const rs = l.rent_suggestion;
  return (<>
    <PageHeader title={l.title} back={<Link to={`${base}/listings`} className="text-sm font-semibold text-federal-900">All listings</Link>}
      meta={<><StatusPill status={l.status} /><span><QuickLink kind="property" id={l.property_id}>{l.property_name}</QuickLink> · Unit {l.unit_number}</span></>}
      actions={<><Button icon={<Sparkles size={14} />} loading={assist.isPending} onClick={() => assist.mutate(undefined)}>Draft with AI</Button><ActionBar actions={l.actions} loading={act.isPending} primary={['PUBLISH', 'APPROVE']} onAction={(a, reason) => act.mutate({ a, reason })} /></>} />
    <div className="mb-6"><WorkflowTrack steps={LISTING_STEPS} current={l.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
      <div className="space-y-6">
        <Panel title="Description"><p className="max-w-[70ch] leading-relaxed text-graphite-800">{l.description}</p>{l.ai_written && <p className="mt-3 text-xs text-graphite-500">Written with AI and reviewed by your team before publishing.</p>}</Panel>
        <Panel title="Unit"><KeyValues cols={3} items={[{ label: 'Bedrooms', value: l.beds }, { label: 'Bathrooms', value: l.baths }, { label: 'Size', value: `${l.sqft} sqft` }, { label: 'Rent', value: fmt.usd(l.rent) }, { label: 'Market rent', value: fmt.usd(l.market_rent) }, { label: 'Views / enquiries', value: `${l.views} / ${l.enquiries}` }]} /></Panel>
      </div>
      <div className="space-y-6">
        {rs && <AiInsight title="Rent suggestion" confidence={0.7} reasons={[rs.reason]}
          footer={<Button size="sm" variant="primary" onClick={() => { setRent(String(rs.suggested)); }}>Use {fmt.usd(rs.suggested)}</Button>}>
          Suggested {fmt.usd(rs.suggested)} against {fmt.usd(rs.current)} today ({rs.monthlyDelta >= 0 ? '+' : ''}{fmt.usd(rs.monthlyDelta)} a month).
        </AiInsight>}
        <Panel title="Change rent"><Field label="New monthly rent (USD)"><Input type="number" value={rent} onChange={(e) => setRent(e.target.value)} /></Field><Button className="mt-3" variant="primary" disabled={!rent} loading={save.isPending} onClick={() => save.mutate(undefined)}>Save rent</Button></Panel>
      </div>
    </div>
  </>);
}

function Leads() {
  const { data, isLoading } = useApi<any[]>('/api/leasing/leads');
  const [open, setOpen] = useState<any>(null); const [reply, setReply] = useState('');
  const send = useAction(() => api.post(`/api/leasing/leads/${open.id}/respond`, { reply }), { success: 'Reply sent', invalidate: ['/api/leasing/leads'], onSuccess: () => setOpen(null) });
  const cols: Column<any>[] = [
    { key: 'name', header: 'Prospect', render: (l) => (<div><div className="font-semibold">{l.name}</div><div className="text-xs text-graphite-500">{l.email}</div></div>) },
    { key: 'listing_title', header: 'Interested in', hideOnMobile: true },
    { key: 'ai_score', header: 'AI score', align: 'right', render: (l) => <ConfidenceMeter value={(l.ai_score ?? 0) / 100} /> },
    { key: 'status', header: 'Status', render: (l) => <StatusPill status={l.status} /> },
    { key: 'created_at', header: 'Received', value: (l) => l.created_at, render: (l) => fmt.date(l.created_at) },
  ];
  return (<><PageHeader title="Leads" description="Enquiries are scored and answered instantly; review the reply before it goes out." /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(l) => l.id} loading={isLoading} initialSort={{ key: 'ai_score', dir: 'desc' }} onRowClick={(l) => { setOpen(l); setReply(l.ai_reply ?? ''); }} /></Panel>
    <Modal open={!!open} onClose={() => setOpen(null)} title={open?.name ?? ''} footer={<><Button variant="quiet" onClick={() => setOpen(null)}>Close</Button><Button variant="primary" loading={send.isPending} onClick={() => send.mutate(undefined)}>Send reply</Button></>}>
      <p className="mb-3 text-sm text-graphite-700">“{open?.message}”</p>
      <Field label="Reply drafted by AI" hint="Edit before sending."><Textarea rows={6} value={reply} onChange={(e) => setReply(e.target.value)} /></Field>
    </Modal></>);
}

function Showings() {
  const { data, isLoading } = useApi<any[]>('/api/leasing/showings');
  const act = useAction((v: { id: string; a: string }) => api.post(`/api/leasing/showings/${v.id}/actions/${v.a}`, {}), { success: 'Showing updated', invalidate: ['/api/leasing/showings'] });
  const cols: Column<any>[] = [
    { key: 'scheduled_at', header: 'When', value: (s) => s.scheduled_at, render: (s) => new Date(s.scheduled_at).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' }) },
    { key: 'prospect_name', header: 'Prospect' }, { key: 'listing_title', header: 'Home', hideOnMobile: true },
    { key: 'status', header: 'Status', render: (s) => <StatusPill status={s.status} /> },
    { key: 'a', header: '', align: 'right', render: (s) => <div className="flex justify-end gap-2"><ActionBar actions={s.actions} primary={['CONFIRM']} onAction={(a) => act.mutate({ id: s.id, a })} /></div> },
  ];
  return (<><PageHeader title="Showings" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(s) => s.id} loading={isLoading} empty={{ title: 'No showings scheduled' }} /></Panel></>);
}

function Applications() {
  const nav = useNavigate(); const base = useBase();
  const { data, isLoading } = useApi<any[]>('/api/leasing/applications');
  const cols: Column<any>[] = [
    { key: 'applicant_name', header: 'Applicant', render: (a) => (<div><div className="font-semibold">{a.applicant_name}</div><div className="text-xs text-graphite-500">{a.unit_label}</div></div>) },
    { key: 'status', header: 'Status', render: (a) => <StatusPill status={a.status} /> },
    { key: 'recommendation', header: 'AI recommends', render: (a) => (a.recommendation ? <Badge tone={a.recommendation === 'APPROVE' ? 'good' : a.recommendation === 'DECLINE' ? 'crit' : 'warn'}>{labelOf(a.recommendation)}</Badge> : '—') },
    { key: 'monthly_income', header: 'Income', align: 'right', hideOnMobile: true, value: (a) => Number(a.monthly_income), render: (a) => fmt.usd(a.monthly_income) },
    { key: 'credit_score', header: 'Credit', align: 'right', hideOnMobile: true, render: (a) => a.credit_score ?? '—' },
    { key: 'submitted_at', header: 'Submitted', value: (a) => a.submitted_at, render: (a) => fmt.date(a.submitted_at) },
  ];
  return (<><PageHeader title="Applications" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(a) => a.id} loading={isLoading} onRowClick={(a) => nav(`${base}/applications/${a.id}`)} initialSort={{ key: 'submitted_at', dir: 'desc' }} /></Panel></>);
}

function ApplicationDetail() {
  const { id } = useParams(); const base = useBase(); const nav = useNavigate();
  const { data: a, isLoading } = useApi<any>(`/api/leasing/applications/${id}`);
  const inv = ['/api/leasing/applications'];
  const screen = useAction(() => api.post(`/api/leasing/applications/${id}/screen`, {}), { success: 'Screening complete', invalidate: inv });
  const act = useAction((v: { a: string; reason?: string }) => api.post(`/api/leasing/applications/${id}/actions/${v.a}`, { reason: v.reason }), { success: 'Application updated', invalidate: inv });
  const mkLease = useAction(() => api.post<any>(`/api/leasing/applications/${id}/lease`, {}), { success: 'Lease drafted', invalidate: inv, onSuccess: (r) => nav(`${base}/leases/${r.id}`) });
  if (isLoading || !a) return <Skeleton className="h-96" />;
  const s = a.screening;
  const canScreen = ['SUBMITTED', 'DOCS_PENDING'].includes(a.status);
  return (<>
    <PageHeader title={a.applicant_name} back={<Link to={`${base}/applications`} className="text-sm font-semibold text-federal-900">All applications</Link>}
      meta={<><StatusPill status={a.status} /><span>{a.unit_label}</span></>}
      actions={<>{canScreen && <Button variant="primary" loading={screen.isPending} onClick={() => screen.mutate(undefined)}>Run screening</Button>}
        <ActionBar actions={a.actions} loading={act.isPending} primary={['APPROVE']} onAction={(x, reason) => act.mutate({ a: x, reason })} />
        {a.status === 'APPROVED' && !a.lease && <Button variant="primary" loading={mkLease.isPending} onClick={() => mkLease.mutate(undefined)}>Create lease</Button>}
        {a.lease && <Link to={`${base}/leases/${a.lease.id}`}><Button>Open lease</Button></Link>}</>} />
    <div className="mb-6"><WorkflowTrack steps={APPLICATION_STEPS} current={a.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
      <div className="space-y-6">
        <Panel title="Applicant"><KeyValues cols={3} items={[
          { label: 'Email', value: a.applicant_email }, { label: 'Monthly income', value: fmt.usd(a.monthly_income) }, { label: 'Employer', value: a.employer },
          { label: 'Months employed', value: a.months_employed }, { label: 'Occupants', value: a.occupants }, { label: 'Move-in', value: fmt.date(a.move_in_date) },
          { label: 'Rent applied for', value: fmt.usd(a.listing_rent) }, { label: 'Credit score', value: a.credit_score ?? 'Not run' }, { label: 'Background', value: a.background_clear == null ? 'Not run' : a.background_clear ? 'Clear' : 'Needs review' },
        ]} /></Panel>
        {a.decision_reason && <Panel title="Decision"><p className="text-sm text-graphite-800">{a.decision_reason}</p></Panel>}
      </div>
      <div>
        {s ? <AiInsight title={`Screening: ${labelOf(s.recommendation)}`} confidence={(s.score ?? 0) / 100} tone={s.recommendation === 'APPROVE' ? 'good' : s.recommendation === 'DECLINE' ? 'crit' : 'warn'}
          reasons={s.reasons?.length ? s.reasons : ['Meets income, credit and background criteria']}>
          <p>{s.summary}</p><p className="mt-2 text-xs text-graphite-500">{s.guardrail}</p>
        </AiInsight> : <EmptyState title="Not screened yet" body="Run screening to check income, credit and background." />}
      </div>
    </div>
  </>);
}

function Leases() {
  const nav = useNavigate(); const base = useBase();
  const { data, isLoading } = useApi<any[]>('/api/leasing/leases');
  const cols: Column<any>[] = [
    { key: 'tenant_name', header: 'Resident', render: (l) => (<div><div className="font-semibold">{l.tenant_name}</div><div className="text-xs text-graphite-500"><QuickLink kind="property" id={l.property_id} className="font-medium">{l.property_name}</QuickLink> · {l.unit_label}</div></div>) },
    { key: 'status', header: 'Status', render: (l) => <StatusPill status={l.status} /> },
    { key: 'rent', header: 'Rent', align: 'right', value: (l) => Number(l.rent), render: (l) => fmt.usd(l.rent) },
    { key: 'end_date', header: 'Ends', value: (l) => l.end_date, render: (l) => fmt.date(l.end_date) },
    { key: 'days_left', header: 'Days left', align: 'right', hideOnMobile: true, render: (l) => (l.status === 'ACTIVE' ? <span className={l.days_left <= 60 ? 'font-semibold text-warn-700' : ''}>{l.days_left}</span> : '—') },
  ];
  return (<><PageHeader title="Leases" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(l) => l.id} loading={isLoading} onRowClick={(l) => nav(`${base}/leases/${l.id}`)} initialSort={{ key: 'end_date', dir: 'asc' }} /></Panel></>);
}

function LeaseView({ mine }: { mine?: boolean }) {
  const params = useParams(); const base = useBase();
  const path = mine ? '/api/leasing/leases/mine' : `/api/leasing/leases/${params.id}`;
  const { data: l, isLoading, error } = useApi<any>(path, { retry: false });
  const [sig, setSig] = useState('');
  const inv = ['/api/leasing/leases'];
  const act = useAction((v: { a: string; reason?: string; body?: any }) => api.post(`/api/leasing/leases/${l.id}/actions/${v.a}`, { reason: v.reason, ...(v.body ?? {}) }), { success: 'Lease updated', invalidate: inv });
  const renew = useAction(() => api.post<any>(`/api/leasing/leases/${l.id}/renewal`, {}), { success: 'Renewal offer prepared', invalidate: inv });
  if (isLoading) return <Skeleton className="h-96" />;
  if (error || !l) return <EmptyState title="No lease found" body="When you have an active lease it appears here." />;
  const canSign = mine && l.status === 'SENT';
  const rn = l.renewal_notices;
  return (<>
    <PageHeader title={mine ? 'My lease' : `${l.tenant_name} · ${l.unit_label}`} back={!mine && <Link to={`${base}/leases`} className="text-sm font-semibold text-federal-900">All leases</Link>}
      meta={<><StatusPill status={l.status} /><span><QuickLink kind="property" id={l.property_id}>{l.property_name}</QuickLink></span></>}
      actions={!mine ? <>{l.status === 'ACTIVE' && <Button loading={renew.isPending} onClick={() => renew.mutate(undefined)}>Prepare renewal</Button>}
        <ActionBar actions={l.actions} loading={act.isPending} primary={['SEND', 'COUNTERSIGN']} onAction={(a, reason) => act.mutate({ a, reason })} /></> : undefined} />
    <div className="mb-6"><WorkflowTrack steps={LEASE_STEPS} current={l.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
      <Panel title="Lease agreement"><pre className="max-h-[560px] overflow-y-auto whitespace-pre-wrap font-sans text-sm leading-relaxed text-graphite-800">{l.document_text}</pre></Panel>
      <div className="space-y-6">
        <Panel title="Terms"><KeyValues cols={1} items={[{ label: 'Rent', value: fmt.usd(l.rent) }, { label: 'Deposit', value: fmt.usd(l.deposit) }, { label: 'Term', value: `${fmt.date(l.start_date)} to ${fmt.date(l.end_date)}` }, { label: 'State', value: l.state },
          { label: 'Tenant signed', value: l.tenant_signed_at ? fmt.date(l.tenant_signed_at) : 'Not yet' }, { label: 'Countersigned', value: l.manager_signed_at ? fmt.date(l.manager_signed_at) : 'Not yet' }]} /></Panel>
        {canSign && <Panel title="Sign your lease" tone="tinted"><Field label={`Type your full legal name: ${l.tenant_name}`}><Input value={sig} onChange={(e) => setSig(e.target.value)} /></Field>
          <Button className="mt-3 w-full" variant="primary" disabled={!sig} loading={act.isPending} onClick={() => act.mutate({ a: 'TENANT_SIGN', body: { signature: sig } })}>Sign lease</Button></Panel>}
        {rn && <AiInsight title="Renewal offer" confidence={0.8}>{typeof rn === 'string' ? rn : JSON.stringify(rn)}</AiInsight>}
      </div>
    </div>
  </>);
}

export function Staff() {
  return (
    <QuickViewProvider>
    <Routes>
      <Route index element={<Overview />} />
      <Route path="properties" element={<Properties />} />
      <Route path="listings" element={<Listings />} />
      <Route path="listings/:id" element={<ListingDetail />} />
      <Route path="leads" element={<Leads />} />
      <Route path="showings" element={<Showings />} />
      <Route path="applications" element={<Applications />} />
      <Route path="applications/:id" element={<ApplicationDetail />} />
      <Route path="leases" element={<Leases />} />
      <Route path="leases/:id" element={<LeaseView />} />
      <Route path="my-lease" element={<LeaseView mine />} />
    </Routes>
    </QuickViewProvider>
  );
}
