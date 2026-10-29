import { useState } from 'react';
import { PageHeader, Panel, DataTable, type Column, Select, useApi, fmt, Stat, StatStrip, Button, Field, Input, Modal, Badge, useAction, api, TrendChart, Skeleton, US_STATE_OPTIONS, Toggle, ConfirmDialog } from '@propertyos/ui';

export function Audit() {
  const [type, setType] = useState('');
  const { data, isLoading } = useApi<any[]>(`/api/identity/audit?limit=300${type ? `&entityType=${type}` : ''}`);
  const cols: Column<any>[] = [
    { key: 'created_at', header: 'When', value: (r) => r.created_at, render: (r) => new Date(r.created_at).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' }) },
    { key: 'actor_name', header: 'Who' }, { key: 'entity_type', header: 'Record' }, { key: 'action', header: 'Action', render: (r) => <Badge>{r.action.replace(/_/g, ' ').toLowerCase()}</Badge> },
    { key: 'change', header: 'Change', hideOnMobile: true, value: (r) => `${r.from_state ?? ''} ${r.to_state ?? ''}`, render: (r) => (r.from_state || r.to_state ? `${r.from_state ?? '—'} → ${r.to_state ?? '—'}` : '') },
  ];
  return (<><PageHeader title="Audit log" description="Who did what and when. Entries can’t be edited or deleted." />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => String(r.id)} loading={isLoading} exportName="audit-log" initialSort={{ key: 'created_at', dir: 'desc' }}
      toolbar={<Select value={type} onChange={(e) => setType(e.target.value)} aria-label="Record type"><option value="">All records</option>{['User', 'Role', 'Organization', 'Invoice', 'Lease', 'Application', 'WorkOrder', 'Listing', 'Config'].map((t) => <option key={t}>{t}</option>)}</Select>} /></Panel></>);
}

const FEATURE_NAMES: Record<string, [string, string]> = {
  LEASING: ['Leasing', 'Listings, leads, showings, applications and leases'], PAYMENTS: ['Payments', 'Invoices, rent collection, owner statements, deposits'],
  MAINTENANCE: ['Maintenance', 'Work orders, vendors, preventive maintenance'], AUTOMATIONS: ['Automations', 'Rules and workflow designer'],
  AI_ASSISTANT: ['AI assistant', 'Chat assistant and AI suggestions'], BROKER_COCKPIT: ['Broker cockpit', 'Daily queue and productivity tools for brokers'],
  OPPORTUNITIES: ['Opportunities', 'Profit and growth insights'], OWNER_PORTAL: ['Owner portal', 'Owners sign in to see their properties'],
  VENDOR_PORTAL: ['Vendor portal', 'Vendors sign in to see their jobs'], RENEWALS: ['Renewals', 'Renewal offers and tracking'],
  RECURRING_MAINTENANCE: ['Preventive maintenance', 'Recurring maintenance schedules'], MESSAGING: ['Messages', 'Conversations between residents, owners, vendors and staff'],
  DOCUMENTS: ['Files and documents', 'Document library, uploads and photos'], ESIGN: ['E-signatures', 'Send documents for signing'],
  SELF_GUIDED_SHOWINGS: ['Self-guided showings', 'Prospects tour without an agent'], AI_VOICE_AGENT: ['AI voice agent', 'Answers calls from prospects'],
};

function CompanyFeatures({ org, onClose }: { org: any; onClose: () => void }) {
  const { data, isLoading } = useApi<any[]>(`/api/identity/platform/orgs/${org.id}/features`);
  const save = useAction((changes: Record<string, boolean | null>) => api.put(`/api/identity/platform/orgs/${org.id}/features`, changes), { success: 'Features updated', invalidate: ['/api/identity/platform'] });
  return (
    <Modal open onClose={onClose} size="lg" title={`Features for ${org.name}`} footer={<Button variant="quiet" onClick={onClose}>Done</Button>}>
      <p className="mb-3 text-sm text-graphite-600">{org.name} is on the {org.plan} plan. Turn a feature off and its screens disappear and its requests are refused for everyone in this company. Turn on a feature the plan doesn’t include to give it as an add-on.</p>
      {isLoading || !data ? <Skeleton className="h-64" /> : (
        <ul className="divide-y divide-limestone-200 rounded border border-limestone-200">{data.map((f) => {
          const [name, desc] = FEATURE_NAMES[f.feature] ?? [f.feature, ''];
          return (
            <li key={f.feature} className="flex items-center gap-3 px-3 py-2.5">
              <span className="min-w-0 flex-1"><span className="block text-sm font-semibold text-graphite-900">{name}</span><span className="block text-xs text-graphite-600">{desc}</span></span>
              {f.override !== null ? <button type="button" className="text-xs font-semibold text-federal-800 underline" onClick={() => save.mutate({ [f.feature]: null })}>Use plan default</button>
                : <Badge tone={f.inPlan ? 'neutral' : 'warn'}>{f.inPlan ? 'In plan' : 'Not in plan'}</Badge>}
              {f.override !== null && <Badge tone="brand">Set by you</Badge>}
              <Toggle checked={f.enabled} onChange={(v) => save.mutate({ [f.feature]: v })} label={`${name} for ${org.name}`} />
            </li>);
        })}</ul>)}
    </Modal>
  );
}

export function Platform() {
  const { data: s } = useApi<any>('/api/identity/platform/summary');
  const { data: orgs, isLoading } = useApi<any[]>('/api/identity/platform/orgs');
  const [open, setOpen] = useState(false); const [f, setF] = useState({ name: '', state: 'TX', plan: 'GROWTH', adminName: '', adminEmail: '' });
  const create = useAction(() => api.post('/api/identity/platform/orgs', f), { success: 'Organisation created', invalidate: ['/api/identity/platform'], onSuccess: () => setOpen(false) });
  const [features, setFeatures] = useState<any | null>(null);
  const [suspend, setSuspend] = useState<any | null>(null);
  const setOrg = useAction((v: { id: string; plan?: string; status?: string }) => api.put(`/api/identity/platform/orgs/${v.id}`, { plan: v.plan, status: v.status }), { success: 'Company updated', invalidate: ['/api/identity/platform'], onSuccess: () => setSuspend(null) });
  const cols: Column<any>[] = [{ key: 'name', header: 'Organisation' }, { key: 'state', header: 'State' }, { key: 'plan', header: 'Plan', render: (r) => <Select value={r.plan} onChange={(e) => setOrg.mutate({ id: r.id, plan: e.target.value })} aria-label={`Plan for ${r.name}`}><option value="STARTER">Starter</option><option value="GROWTH">Growth</option><option value="ENTERPRISE">Enterprise</option></Select> }, { key: 'status', header: 'Status', render: (r) => <Badge tone={r.status === 'ACTIVE' ? 'good' : 'crit'}>{r.status === 'ACTIVE' ? 'Active' : 'Suspended'}</Badge> }, { key: 'user_count', header: 'Users', align: 'right' }, { key: 'created_at', header: 'Created', render: (r) => fmt.date(r.created_at) },
    { key: 'manage', header: '', render: (r) => <span className="flex gap-2"><Button variant="secondary" onClick={() => setFeatures(r)}>Features</Button><Button variant="quiet" onClick={() => r.status === 'ACTIVE' ? setSuspend(r) : setOrg.mutate({ id: r.id, status: 'ACTIVE' })}>{r.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}</Button></span> }];
  return (<><PageHeader title="Platform" description="Every company on PropertyOS. Data stays isolated per company." actions={<Button variant="primary" onClick={() => setOpen(true)}>Onboard company</Button>} />
    {s && <StatStrip className="mb-6"><Stat label="Companies" value={s.organizations} emphasis /><Stat label="Users" value={s.users} /><Stat label="Active, last 7 days" value={s.activeUsers7d} /><Stat label="Config changes, 30 days" value={s.configChanges30d} /></StatStrip>}
    <Panel padded={false}><DataTable rows={orgs} columns={cols} rowKey={(r) => r.id} loading={isLoading} /></Panel>
    {features && <CompanyFeatures org={features} onClose={() => setFeatures(null)} />}
    <ConfirmDialog open={!!suspend} title={`Suspend ${suspend?.name ?? ''}?`} confirmLabel="Suspend company" tone="danger" loading={setOrg.isPending} onClose={() => setSuspend(null)} onConfirm={() => suspend && setOrg.mutate({ id: suspend.id, status: 'SUSPENDED' })}
      body="Nobody in this company will be able to sign in until you reactivate it. Their data is kept." />
    <Modal open={open} onClose={() => setOpen(false)} title="Onboard a company" footer={<><Button variant="quiet" onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" loading={create.isPending} disabled={!f.name || !f.adminEmail || !f.adminName} onClick={() => create.mutate(undefined)}>Create company</Button></>}>
      <div className="space-y-3"><Field label="Company name" required><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <div className="grid grid-cols-2 gap-3"><Field label="State"><Select value={f.state} onChange={(e) => setF({ ...f, state: e.target.value })}>{US_STATE_OPTIONS.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}</Select></Field><Field label="Plan"><Select value={f.plan} onChange={(e) => setF({ ...f, plan: e.target.value })}><option value="STARTER">Starter</option><option value="GROWTH">Growth</option><option value="ENTERPRISE">Enterprise</option></Select></Field></div>
        <Field label="Administrator name" required><Input value={f.adminName} onChange={(e) => setF({ ...f, adminName: e.target.value })} /></Field><Field label="Administrator email" required><Input type="email" value={f.adminEmail} onChange={(e) => setF({ ...f, adminEmail: e.target.value })} /></Field></div></Modal></>);
}
