import { useState } from 'react';
import { Link, NavLink, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import {
  MicroApp, PageHeader, Panel, Stat, StatStrip, Button, StatusPill, Badge, DataTable, type Column, Modal, Field, Input, Select, Textarea, KeyValues, Timeline,
  AiInsight, SlaClock, WorkflowTrack, RankBars, EmptyState, Skeleton, Toggle, useApi, useAction, api, fmt, useSession, personaOf, type WorkflowAction,
} from '@propertyos/ui';

const BASE = '/app/maintenance';
const inv = ['/api/maintenance'];
const STEPS = [{ key: 'TRIAGED', label: 'Triaged' }, { key: 'AWAITING_APPROVAL', label: 'Approval' }, { key: 'ASSIGNED', label: 'Assigned' }, { key: 'SCHEDULED', label: 'Scheduled' }, { key: 'IN_PROGRESS', label: 'In progress' }, { key: 'COMPLETED', label: 'Completed' }, { key: 'VERIFIED', label: 'Verified' }];
const prioTone = (p: string) => (p === 'EMERGENCY' ? 'crit' : p === 'HIGH' ? 'warn' : 'neutral');

const SubNav = () => {
  const { can, session } = useSession();
  const staff = personaOf(session) !== 'tenant';
  const items: [string, string, boolean][] = [['', 'Work orders', true], ['vendors', 'Vendors', staff && can('VENDOR:VIEW')], ['recurring', 'Preventive maintenance', staff]];
  return (
    <nav className="mb-6 flex gap-1 overflow-x-auto border-b border-limestone-200">
      {items.filter((i) => i[2]).map(([k, label]) => (
        <NavLink key={k} to={`${BASE}/${k}`} end={k === ''} className={({ isActive }) => `-mb-px whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-semibold ${isActive ? 'border-federal-900 text-federal-900' : 'border-transparent text-graphite-500 hover:text-graphite-800'}`}>{label}</NavLink>
      ))}
    </nav>
  );
};

function List() {
  const nav = useNavigate(); const { can, session } = useSession();
  const staff = personaOf(session) !== 'tenant' && personaOf(session) !== 'vendor';
  const { data, isLoading } = useApi<any[]>('/api/maintenance/work-orders');
  const { data: st } = useApi<any>(staff ? '/api/maintenance/stats' : null);
  const [creating, setCreating] = useState(false);
  const cols: Column<any>[] = [
    { key: 'priority', header: 'Priority', width: '110px', render: (w) => <Badge tone={prioTone(w.priority) as any}>{w.priority}</Badge> },
    { key: 'title', header: 'Request', render: (w) => (<div><div className="font-semibold">{w.title}</div><div className="text-xs text-graphite-500">{w.property_name} · {w.unit_label}</div></div>) },
    { key: 'status', header: 'Status', render: (w) => <StatusPill status={w.status} /> },
    { key: 'vendor_name', header: 'Vendor', hideOnMobile: true, render: (w) => w.vendor_name ?? '—' },
    { key: 'sla', header: 'Due', render: (w) => <SlaClock dueAt={w.sla_due_at} done={['COMPLETED', 'VERIFIED', 'CLOSED', 'CANCELLED'].includes(w.status)} /> },
  ];
  return (<>
    <PageHeader title="Maintenance" description="Requests are triaged and routed to the right vendor automatically. Emergencies are dispatched immediately."
      actions={can('WORK_ORDER:CREATE') && <Button variant="primary" onClick={() => setCreating(true)}>New request</Button>} />
    <SubNav />
    {st && <StatStrip className="mb-6"><Stat label="Open requests" value={st.open_orders} emphasis /><Stat label="Emergencies" value={st.open_emergencies} /><Stat label="Awaiting owner approval" value={st.awaiting_approval} />
      <Stat label="Past SLA" value={st.sla_breached} /><Stat label="Avg. resolution" value={`${Math.round(st.avg_resolution_hours ?? 0)} h`} /><Stat label="Cost this month" value={fmt.usd(st.cost_this_month)} /></StatStrip>}
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(w) => w.id} loading={isLoading} onRowClick={(w) => nav(`${BASE}/${w.id}`)} exportName="work-orders" initialSort={{ key: 'priority', dir: 'desc' }}
      empty={{ title: 'No requests', body: 'Submitted requests will appear here.' }} /></Panel>
    {st?.by_category?.length > 0 && <div className="mt-6 grid gap-6 lg:grid-cols-2"><Panel title="Requests by category"><RankBars data={st.by_category} label="category" value="count" /></Panel><Panel title="Requests by property"><RankBars data={st.by_property} label="property_name" value="count" /></Panel></div>}
    <NewRequest open={creating} onClose={() => setCreating(false)} />
  </>);
}

function NewRequest({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { session } = useSession(); const nav = useNavigate();
  const tenant = personaOf(session) === 'tenant';
  const { data: leases } = useApi<any[]>(open && !tenant ? '/api/leasing/leases?status=ACTIVE' : null);
  const [f, setF] = useState<any>({ tenantId: '', title: '', description: '', permissionToEnter: true });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const go = useAction(() => api.post<any>('/api/maintenance/work-orders', { ...f, tenantId: f.tenantId || undefined }), { success: 'Request submitted', invalidate: inv, onSuccess: (r) => { onClose(); if (r?.id) nav(`${BASE}/${r.id}`); } });
  return (<Modal open={open} onClose={onClose} title="New maintenance request" footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={go.isPending} disabled={!f.title || (!tenant && !f.tenantId)} onClick={() => go.mutate(undefined)}>Submit</Button></>}>
    <div className="space-y-3">
      {!tenant && <Field label="Resident" required><Select value={f.tenantId} onChange={set('tenantId')}><option value="">Choose a resident</option>{(leases ?? []).map((l) => <option key={l.id} value={l.tenant_id}>{l.tenant_name} · {l.property_name} {l.unit_label}</option>)}</Select></Field>}
      <Field label="What’s wrong?" required><Input value={f.title} onChange={set('title')} placeholder="e.g. Kitchen sink is leaking" /></Field>
      <Field label="Details" hint="We triage by urgency and cost. If you smell gas or see smoke, leave and call 911 first."><Textarea rows={4} value={f.description} onChange={set('description')} /></Field>
      <Toggle checked={f.permissionToEnter} onChange={(v) => setF({ ...f, permissionToEnter: v })} label="OK to enter if I’m not home" />
    </div></Modal>);
}

type Dlg = null | { action: WorkflowAction };

function Detail() {
  const { id } = useParams(); const { session } = useSession();
  const staff = personaOf(session) !== 'tenant';
  const { data: w, isLoading } = useApi<any>(`/api/maintenance/work-orders/${id}`);
  const { data: vendors } = useApi<any[]>(staff ? '/api/maintenance/vendors' : null);
  const [dlg, setDlg] = useState<Dlg>(null); const [v, setV] = useState<any>({}); const [msg, setMsg] = useState(''); const [internal, setInternal] = useState(false);
  const act = useAction((x: { a: string; body: any }) => api.post(`/api/maintenance/work-orders/${id}/actions/${x.a}`, x.body), { success: 'Updated', invalidate: inv, onSuccess: () => { setDlg(null); setV({}); } });
  const post = useAction(() => api.post(`/api/maintenance/work-orders/${id}/comments`, { body: msg, internal }), { success: 'Comment added', invalidate: inv, onSuccess: () => setMsg('') });
  if (isLoading || !w) return <Skeleton className="h-96" />;
  const t = w.ai_triage;
  const needsForm = ['ASSIGN', 'SCHEDULE', 'COMPLETE', 'VERIFY', 'APPROVE'];
  const submit = () => {
    const a = dlg!.action.action; let body: any = { reason: v.reason };
    if (a === 'ASSIGN') body.vendorId = v.vendorId;
    if (a === 'APPROVE' && v.amount) body.amount = Number(v.amount);
    if (a === 'SCHEDULE') body.scheduledFor = new Date(v.when).toISOString();
    if (a === 'COMPLETE') body = { ...body, cost: Number(v.cost), notes: v.notes };
    if (a === 'VERIFY' && v.rating) body.rating = Number(v.rating);
    act.mutate({ a, body });
  };
  const A = dlg?.action.action;
  return (<>
    <PageHeader title={w.title} back={<Link to={BASE} className="text-sm font-semibold text-federal-900">All requests</Link>}
      meta={<><Badge tone={prioTone(w.priority) as any}>{w.priority}</Badge><StatusPill status={w.status} /><span>{w.property_name} · {w.unit_label}</span></>}
      actions={<>{(w.actions as WorkflowAction[] ?? []).map((a) => (
        <Button key={a.action} size="sm" variant={/CANCEL|REJECT|DECLINE/.test(a.action) ? 'danger' : ['APPROVE', 'ASSIGN', 'COMPLETE', 'VERIFY'].includes(a.action) ? 'primary' : 'secondary'}
          onClick={() => (needsForm.includes(a.action) || a.requiresReason ? setDlg({ action: a }) : act.mutate({ a: a.action, body: {} }))}>{a.label}</Button>))}</>} />
    <div className="mb-6"><WorkflowTrack steps={STEPS} current={w.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
      <div className="space-y-6">
        <Panel title="Details"><p className="mb-4 max-w-[70ch] text-graphite-800">{w.description || 'No description provided.'}</p>
          <KeyValues cols={3} items={[{ label: 'Resident', value: w.tenant_name }, { label: 'Category', value: w.category }, { label: 'Vendor', value: w.vendor_name ?? 'Not assigned' }, { label: 'Estimate', value: w.estimate ? fmt.usd(w.estimate) : '—' }, { label: 'Permission to enter', value: w.permission_to_enter ? 'Yes' : 'No' }, { label: 'Response due', value: <SlaClock dueAt={w.sla_due_at} done={['COMPLETED', 'VERIFIED', 'CLOSED'].includes(w.status)} /> }]} /></Panel>
        <Panel title="Conversation">
          <ul className="mb-4 space-y-3">{(w.comments ?? []).length === 0 && <li className="text-sm text-graphite-500">No messages yet.</li>}{(w.comments ?? []).map((c: any) => (
            <li key={c.id} className={`rounded px-3 py-2 text-sm ${c.internal ? 'bg-warn-50' : 'bg-limestone-100'}`}><div className="mb-0.5 text-xs text-graphite-500">{c.author_name} · {fmt.date(c.created_at)}{c.internal && ' · internal note'}</div>{c.body}</li>))}</ul>
          <Textarea rows={2} value={msg} onChange={(e) => setMsg(e.target.value)} placeholder="Write a message" />
          <div className="mt-2 flex items-center justify-between">{staff ? <Toggle checked={internal} onChange={setInternal} label="Internal note (hidden from resident)" /> : <span />}<Button variant="primary" size="sm" disabled={!msg.trim()} loading={post.isPending} onClick={() => post.mutate(undefined)}>Send</Button></div></Panel>
      </div>
      <div className="space-y-6">
        {t && <AiInsight title="AI triage" confidence={t.confidence} tone={t.safetyRisk ? 'crit' : 'neutral'} reasons={t.tips}><p>{t.summary}</p><p className="mt-2 text-xs text-graphite-500">{t.guardrail}</p></AiInsight>}
        <Panel title="History"><Timeline items={w.timeline} /></Panel>
      </div>
    </div>
    <Modal open={!!dlg} onClose={() => setDlg(null)} title={dlg?.action.label ?? ''} size="sm" footer={<><Button variant="quiet" onClick={() => setDlg(null)}>Cancel</Button><Button variant="primary" loading={act.isPending} onClick={submit}
      disabled={(A === 'ASSIGN' && !v.vendorId) || (A === 'SCHEDULE' && !v.when) || (A === 'COMPLETE' && !v.cost) || (dlg?.action.requiresReason && !v.reason)}>{dlg?.action.label}</Button></>}>
      <div className="space-y-3">
        {A === 'ASSIGN' && <Field label="Vendor"><Select value={v.vendorId ?? ''} onChange={(e) => setV({ ...v, vendorId: e.target.value })}><option value="">Choose a vendor</option>{(vendors ?? []).filter((x) => x.active).map((x) => <option key={x.id} value={x.id}>{x.name} · {(x.trades ?? []).join(', ')} · {x.rating}★</option>)}</Select></Field>}
        {A === 'APPROVE' && <Field label="Approved amount (USD)" hint={`Defaults to the estimate${w.estimate ? ` of ${fmt.usd(w.estimate)}` : ''}.`}><Input type="number" value={v.amount ?? ''} onChange={(e) => setV({ ...v, amount: e.target.value })} /></Field>}
        {A === 'SCHEDULE' && <Field label="Visit date and time" required><Input type="datetime-local" value={v.when ?? ''} onChange={(e) => setV({ ...v, when: e.target.value })} /></Field>}
        {A === 'COMPLETE' && <><Field label="Final cost (USD)" required><Input type="number" value={v.cost ?? ''} onChange={(e) => setV({ ...v, cost: e.target.value })} /></Field><Field label="Work notes"><Textarea rows={3} value={v.notes ?? ''} onChange={(e) => setV({ ...v, notes: e.target.value })} /></Field></>}
        {A === 'VERIFY' && <Field label="How was the work?"><Select value={v.rating ?? ''} onChange={(e) => setV({ ...v, rating: e.target.value })}><option value="">No rating</option>{[5, 4, 3, 2, 1].map((n) => <option key={n} value={n}>{n} stars</option>)}</Select></Field>}
        {dlg?.action.requiresReason && <Field label="Reason" required hint="Saved in the audit trail."><Textarea rows={3} value={v.reason ?? ''} onChange={(e) => setV({ ...v, reason: e.target.value })} /></Field>}
      </div></Modal>
  </>);
}

function Vendors() {
  const { data, isLoading } = useApi<any[]>('/api/maintenance/vendors');
  const cols: Column<any>[] = [
    { key: 'name', header: 'Vendor', render: (r) => (<div><div className="font-semibold">{r.name}</div><div className="text-xs text-graphite-500">{r.contact} · {r.phone}</div></div>) },
    { key: 'trades', header: 'Trades', value: (r) => (r.trades ?? []).join(', ') },
    { key: 'rating', header: 'Rating', align: 'right', value: (r) => Number(r.rating), render: (r) => `${r.rating} ★` },
    { key: 'jobs_completed', header: 'Jobs', align: 'right', hideOnMobile: true },
    { key: 'avg_job_cost', header: 'Avg. cost', align: 'right', hideOnMobile: true, value: (r) => Number(r.avg_job_cost), render: (r) => fmt.usd(r.avg_job_cost) },
    { key: 'open_jobs', header: 'Open', align: 'right' },
    { key: 'insurance_expires', header: 'Insurance to', hideOnMobile: true, render: (r) => fmt.date(r.insurance_expires) },
  ];
  return (<><PageHeader title="Vendors" description="Ranked automatically on rating, price, response time and current workload." /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} exportName="vendors" /></Panel></>);
}

function Recurring() {
  const { data, isLoading } = useApi<any[]>('/api/maintenance/recurring');
  const run = useAction(() => api.post<any>('/api/maintenance/recurring/run'), { success: 'Due tasks turned into requests', invalidate: inv });
  const cols: Column<any>[] = [
    { key: 'title', header: 'Task' }, { key: 'property_name', header: 'Property', hideOnMobile: true }, { key: 'category', header: 'Category' },
    { key: 'every_months', header: 'Every', render: (r) => `${r.every_months} months` }, { key: 'next_due', header: 'Next due', render: (r) => fmt.date(r.next_due) },
  ];
  return (<><PageHeader title="Preventive maintenance" description="Scheduled inspections and filter changes cost far less than emergency repairs." actions={<Button onClick={() => run.mutate(undefined)} loading={run.isPending}>Create due requests now</Button>} /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} /></Panel></>);
}

export default function App() {
  return (
    <MicroApp name="maintenance">
      <Routes>
        <Route index element={<List />} />
        <Route path="vendors" element={<Vendors />} />
        <Route path="recurring" element={<Recurring />} />
        <Route path=":id" element={<Detail />} />
      </Routes>
    </MicroApp>
  );
}
