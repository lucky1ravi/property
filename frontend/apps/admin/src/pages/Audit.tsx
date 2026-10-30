import { useEffect, useState, type ReactNode } from 'react';
import { motion } from 'framer-motion';
import { PageHeader, Panel, DataTable, type Column, Select, useApi, fmt, Stat, StatStrip, Button, Field, Input, Modal, Badge, useAction, api, TrendChart, Skeleton, US_STATE_OPTIONS, Toggle, ConfirmDialog, RowActions } from '@propertyos/ui';

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

const COMPANY_STATUSES = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'SUSPENDED', label: 'Suspended' },
  { value: 'DELETED', label: 'Deleted' },
];

function CompanyStatusFilter({ value, onChange }: { value: string[]; onChange: (value: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const label = COMPANY_STATUSES.filter((status) => value.includes(status.value)).map((status) => status.label).join(', ');
  const toggle = (status: string) => {
    if (value.includes(status)) {
      if (value.length > 1) onChange(value.filter((current) => current !== status));
    } else onChange([...value, status]);
  };
  return <div className="relative">
    <Button variant="secondary" size="sm" type="button" aria-expanded={open} aria-haspopup="menu" onClick={() => setOpen(!open)}>Status: {label}</Button>
    {open && <div role="menu" className="absolute right-0 z-20 mt-1 w-48 rounded-md border border-limestone-300 bg-white p-2 shadow-lg">
      <p className="px-1 pb-1 text-xs font-semibold text-graphite-600">Show companies with status</p>
      {COMPANY_STATUSES.map((status) => <label key={status.value} className="flex cursor-pointer items-center gap-2 rounded px-1 py-1.5 text-sm text-graphite-800 hover:bg-limestone-100">
        <input type="checkbox" checked={value.includes(status.value)} onChange={() => toggle(status.value)} className="h-4 w-4 accent-federal-900" />
        {status.label}
      </label>)}
    </div>}
  </div>;
}

function CompanyDetails({ org, onClose }: { org: any; onClose: () => void }) {
  const { data: d, isLoading } = useApi<any>(`/api/identity/platform/orgs/${org.id}`);
  const row = (label: string, value: any) => <div className="flex justify-between gap-4 py-1.5 text-sm"><dt className="text-graphite-600">{label}</dt><dd className="text-right font-semibold text-graphite-900">{value ?? 'Not set'}</dd></div>;
  const pc = d?.primaryContact;
  return (
    <Modal open onClose={onClose} title={org.name} footer={<Button variant="primary" onClick={onClose}>Close</Button>}>
      {isLoading || !d ? <p className="text-sm text-graphite-600">Loading…</p> : (
        <div className="space-y-5">
          <section><h3 className="text-sm font-extrabold text-graphite-950">Company</h3>
            <dl className="mt-1 divide-y divide-limestone-200">
              {row('Legal name', d.legal_name)}{row('State', d.state)}{row('Plan', d.plan)}
              {row('Status', d.status === 'ACTIVE' ? 'Active' : d.status === 'SUSPENDED' ? `Suspended — ${d.status_reason || 'no reason was recorded'}` : 'Deleted')}
              {d.status === 'SUSPENDED' && d.status_changed_at && row('Suspended on', `${fmt.date(d.status_changed_at)} by ${d.status_changed_by ?? 'PropertyOS'}`)}
              {d.status === 'DELETED' && d.deleted_at && row('Deleted on', `${fmt.date(d.deleted_at)} by ${d.deleted_by ?? 'PropertyOS'}`)}{row('Created', fmt.date(d.created_at))}
              {row('Roles', d.roleCount)}
            </dl></section>
          <section><h3 className="text-sm font-extrabold text-graphite-950">Primary contact</h3>
            {pc ? <dl className="mt-1 divide-y divide-limestone-200">
              {row('Name', pc.name)}{row('Title', pc.title)}
              {row('Email', <a className="text-federal-800 underline" href={`mailto:${pc.email}`}>{pc.email}</a>)}
              {row('Phone', pc.phone)}
              {row('Last signed in', pc.last_login_at ? fmt.date(pc.last_login_at) : (pc.must_change_password ? 'Hasn’t signed in yet' : 'Never'))}
            </dl> : <p className="text-sm text-graphite-600">This company has no administrator yet.</p>}
          </section>
          {d.administrators.length > 1 && <section><h3 className="text-sm font-extrabold text-graphite-950">All administrators</h3>
            <ul className="mt-1 divide-y divide-limestone-200 text-sm">{d.administrators.map((a: any) => <li key={a.id} className="flex justify-between py-1.5"><span>{a.name}</span><span className="text-graphite-600">{a.email}</span></li>)}</ul></section>}
          <section><h3 className="text-sm font-extrabold text-graphite-950">People</h3>
            <dl className="mt-1 divide-y divide-limestone-200">
              {row('Users', d.userStats.total)}{row('Active accounts', d.userStats.active)}{row('Signed in, last 7 days', d.userStats.active_7d)}
              {row('Waiting for first sign-in', d.userStats.awaiting_first_sign_in)}
              {row('Last sign-in', d.userStats.last_sign_in ? fmt.date(d.userStats.last_sign_in) : 'None yet')}
            </dl></section>
          <section><h3 className="text-sm font-extrabold text-graphite-950">Features switched on</h3>
            <p className="mt-1 text-sm text-graphite-700">{d.enabledFeatures.length ? d.enabledFeatures.join(', ') : 'None'}</p></section>
        </div>)}
    </Modal>);
}

type FeatureChanges = Record<string, boolean | null>;

function CompanyEditor({ org, plans, saving, onSave, onSuspend, onReactivate, onClose }: { org: any; plans: any[]; saving: boolean; onSave: (changes: any, features: FeatureChanges) => void; onSuspend: () => void; onReactivate: () => void; onClose: () => void }) {
  const initial = { name: org.name ?? '', legalName: org.legal_name ?? '', state: org.state ?? '', plan: org.plan ?? '' };
  const [form, setForm] = useState(initial);
  const [staged, setStaged] = useState<FeatureChanges>({});
  useEffect(() => { setForm({ name: org.name ?? '', legalName: org.legal_name ?? '', state: org.state ?? '', plan: org.plan ?? '' }); setStaged({}); }, [org]);
  const { data: features, isLoading } = useApi<any[]>(`/api/identity/platform/orgs/${org.id}/features`);
  const stage = (feature: string, value: boolean | null) => setStaged((c) => ({ ...c, [feature]: value }));
  const stateOf = (f: any) => {
    if (!(f.feature in staged)) return { override: f.override !== null, enabled: f.enabled };
    const v = staged[f.feature];
    return v === null ? { override: false, enabled: f.inPlan } : { override: true, enabled: v };
  };
  const changed = Object.keys(staged).length;
  return <Modal open onClose={onClose} size="lg" title={`Edit ${org.name}`} footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={saving} disabled={!form.name.trim() || !form.state || !form.plan} onClick={() => onSave(form, staged)}>Save changes{changed ? ` (${changed} feature${changed > 1 ? 's' : ''})` : ''}</Button></>}>
    <div className="space-y-6">
      <section className="space-y-4">
        <h3 className="text-sm font-extrabold text-graphite-950">Company details</h3>
        <Field label="Company name" required><Input value={form.name} maxLength={160} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="Legal name"><Input value={form.legalName} maxLength={200} onChange={(e) => setForm({ ...form, legalName: e.target.value })} /></Field>
        <div className="grid grid-cols-2 gap-3"><Field label="State" required><Select value={form.state} onChange={(e) => setForm({ ...form, state: e.target.value })}>{US_STATE_OPTIONS.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}</Select></Field><Field label="Plan" required><Select value={form.plan} onChange={(e) => setForm({ ...form, plan: e.target.value })}>{!plans.some((p) => p.plan_key === org.plan) && org.plan && <option value={org.plan}>{org.plan}</option>}{plans.map((p) => <option key={p.plan_key} value={p.plan_key}>{p.name}</option>)}</Select></Field></div>
      </section>
      <section>
        <h3 className="text-sm font-extrabold text-graphite-950">Features</h3>
        <p className="mb-3 mt-1 text-sm text-graphite-600">Turn a feature off and its screens disappear and its requests are refused for everyone in this company. Turn on a feature the plan doesn’t include to give it as an add-on. Changes apply when you save.</p>
        {isLoading || !features ? <Skeleton className="h-64" /> : (
          <ul className="divide-y divide-limestone-200 rounded border border-limestone-200">{features.map((f) => {
            const [name, desc] = FEATURE_NAMES[f.feature] ?? [f.feature, ''];
            const st = stateOf(f);
            return (
              <motion.li layout key={f.feature} className={`flex items-center gap-3 px-3 py-2.5 ${f.feature in staged ? 'bg-federal-50' : ''}`}>
                <span className="min-w-0 flex-1"><span className="block text-sm font-semibold text-graphite-900">{name}</span><span className="block text-xs text-graphite-600">{desc}</span></span>
                {st.override ? <button type="button" className="text-xs font-semibold text-federal-800 underline" onClick={() => stage(f.feature, null)}>Use plan default</button>
                  : <Badge tone={f.inPlan ? 'neutral' : 'warn'}>{f.inPlan ? 'In plan' : 'Not in plan'}</Badge>}
                {st.override && <Badge tone="brand">Set by you</Badge>}
                <Toggle checked={st.enabled} onChange={(v) => stage(f.feature, v)} label={`${name} for ${org.name}`} />
              </motion.li>);
          })}</ul>)}
      </section>
      <section className="rounded-md border border-limestone-300 bg-limestone-50 p-4"><div className="flex items-center justify-between gap-4"><div><h3 className="text-sm font-bold text-graphite-950">Account access</h3><p className="mt-1 text-sm text-graphite-600">{org.status === 'ACTIVE' ? 'Suspend access for everyone in this company.' : 'Restore access for this company.'}</p></div>{org.status === 'ACTIVE' ? <Button variant="danger" onClick={onSuspend}>Suspend</Button> : <Button variant="secondary" onClick={onReactivate}>Reactivate</Button>}</div></section>
    </div>
  </Modal>;
}

export function Platform() {
  const { data: s } = useApi<any>('/api/identity/platform/summary');
  const [statuses, setStatuses] = useState<string[]>(['ACTIVE']);
  const orgQuery = statuses.map((status) => `status=${encodeURIComponent(status)}`).join('&');
  const { data: orgs, isLoading } = useApi<any[]>(`/api/identity/platform/orgs?${orgQuery}`);
  const { data: planList } = useApi<any[]>('/api/identity/platform/plans');
  const planOptions = (planList ?? []).map((p) => <option key={p.plan_key} value={p.plan_key}>{p.name}</option>);
  const [open, setOpen] = useState(false); const [f, setF] = useState({ name: '', state: 'TX', plan: 'GROWTH', adminName: '', adminEmail: '' });
  const [created, setCreated] = useState<{ email: string; password: string } | null>(null);
  const create = useAction(() => api.post<{ temporaryPassword: string }>('/api/identity/platform/orgs', f), { success: 'Organisation created', invalidate: ['/api/identity/platform'], onSuccess: (r) => { setOpen(false); setCreated({ email: f.adminEmail, password: r.temporaryPassword }); setF({ name: '', state: 'TX', plan: 'GROWTH', adminName: '', adminEmail: '' }); } });
  const [details, setDetails] = useState<any | null>(null);
  const [editing, setEditing] = useState<any | null>(null);
  const [suspend, setSuspend] = useState<any | null>(null);
  const [deleting, setDeleting] = useState<any | null>(null);
  const [why, setWhy] = useState('');
  const setOrg = useAction((v: { id: string; name?: string; legalName?: string; state?: string; plan?: string; status?: string; reason?: string; features?: Record<string, boolean | null> }) => api.put(`/api/identity/platform/orgs/${v.id}`, { name: v.name, legalName: v.legalName, state: v.state, plan: v.plan, status: v.status, reason: v.reason }).then(async (r) => { if (v.features && Object.keys(v.features).length) await api.put(`/api/identity/platform/orgs/${v.id}/features`, v.features); return r; }), { success: 'Company updated', invalidate: ['/api/identity/platform'], onSuccess: () => { setEditing(null); setSuspend(null); setWhy(''); } });
  const remove = useAction((id: string) => api.del(`/api/identity/platform/orgs/${id}`), { success: 'Company deleted', invalidate: ['/api/identity/platform'], onSuccess: () => setDeleting(null) });
  const statusLabel = (status: string) => status === 'ACTIVE' ? 'Active' : status === 'SUSPENDED' ? 'Suspended' : 'Deleted';
  const cols: Column<any>[] = [{ key: 'name', header: 'Organisation' }, { key: 'state', header: 'State' }, { key: 'plan', header: 'Plan' }, { key: 'status', header: 'Status', filter: false, render: (r) => <Badge tone={r.status === 'ACTIVE' ? 'good' : r.status === 'SUSPENDED' ? 'crit' : 'neutral'}>{statusLabel(r.status)}</Badge> }, { key: 'user_count', header: 'Users', align: 'right' }, { key: 'created_at', header: 'Created', render: (r) => fmt.date(r.created_at) },
    { key: 'manage', header: '', render: (r) => <RowActions name={r.name} onView={() => setDetails(r)} onEdit={r.status !== 'DELETED' ? () => setEditing(r) : undefined} onDelete={r.status !== 'DELETED' ? () => setDeleting(r) : undefined} /> }];
  return (<><PageHeader title="Platform" description="Every company on PropertyOS. Data stays isolated per company." actions={<Button variant="primary" onClick={() => setOpen(true)}>Onboard company</Button>} />
    {s && <StatStrip className="mb-6"><Stat label="Companies" value={s.organizations} emphasis /><Stat label="Users" value={s.users} /><Stat label="Active, last 7 days" value={s.activeUsers7d} /><Stat label="Config changes, 30 days" value={s.configChanges30d} /></StatStrip>}
    <Panel padded={false}><DataTable rows={orgs} columns={cols} rowKey={(r) => r.id} loading={isLoading} toolbar={<CompanyStatusFilter value={statuses} onChange={setStatuses} />} /></Panel>
    <Modal open={!!created} onClose={() => setCreated(null)} title="Give this to the administrator" footer={<Button variant="primary" onClick={() => setCreated(null)}>Done</Button>}>
      <p className="text-sm text-graphite-700">They sign in with this email and temporary password, then choose their own. PropertyOS shows the password only now, so copy it before you close this.</p>
      <dl className="mt-3 space-y-2 rounded bg-limestone-100 p-3 text-sm">
        <div><dt className="text-xs text-graphite-600">Email</dt><dd className="font-semibold text-graphite-900">{created?.email}</dd></div>
        <div><dt className="text-xs text-graphite-600">Temporary password</dt><dd className="select-all font-mono text-base font-bold text-graphite-950">{created?.password}</dd></div>
      </dl>
      <div className="mt-3"><Button variant="secondary" onClick={() => created && navigator.clipboard?.writeText(`Email: ${created.email}\nTemporary password: ${created.password}`)}>Copy both</Button></div>
    </Modal>
    {details && <CompanyDetails org={details} onClose={() => setDetails(null)} />}
    {editing && <CompanyEditor org={editing} plans={planList ?? []} saving={setOrg.isPending} onSave={(changes, feats) => setOrg.mutate({ id: editing.id, ...changes, features: feats })} onSuspend={() => { setEditing(null); setSuspend(editing); }} onReactivate={() => setOrg.mutate({ id: editing.id, status: 'ACTIVE' })} onClose={() => setEditing(null)} />}
    <ConfirmDialog open={!!deleting} title={`Delete ${deleting?.name ?? ''}?`} confirmLabel="Delete company" tone="danger" loading={remove.isPending}
      body="This removes the company from normal platform use immediately. Its data is retained for audit purposes, but its users can no longer sign in."
      onConfirm={() => deleting && remove.mutate(deleting.id)} onClose={() => setDeleting(null)} />
    <Modal open={!!suspend} onClose={() => { setSuspend(null); setWhy(''); }} title={`Suspend ${suspend?.name ?? ''}?`}
      footer={<><Button variant="quiet" onClick={() => { setSuspend(null); setWhy(''); }}>Cancel</Button><Button variant="danger" loading={setOrg.isPending} disabled={!why.trim()} onClick={() => suspend && setOrg.mutate({ id: suspend.id, status: 'SUSPENDED', reason: why })}>Suspend company</Button></>}>
      <p className="text-sm text-graphite-700">Everyone in this company, including owners, residents and vendors, is signed out within seconds and can’t sign in again. Their public listings are hidden and scheduled reminders stop. Nothing is deleted, and you can reactivate at any time.</p>
      <div className="mt-3"><Field label="Reason" required hint="Kept in the audit log and shown on the company’s details."><Input value={why} maxLength={300} onChange={(e) => setWhy(e.target.value)} placeholder="For example: unpaid invoice, security review, requested by the customer" /></Field></div>
    </Modal>
    <Modal open={open} onClose={() => setOpen(false)} title="Onboard a company" footer={<><Button variant="quiet" onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" loading={create.isPending} disabled={!f.name || !f.adminEmail || !f.adminName} onClick={() => create.mutate(undefined)}>Create company</Button></>}>
      <div className="space-y-3"><Field label="Company name" required><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <div className="grid grid-cols-2 gap-3"><Field label="State"><Select value={f.state} onChange={(e) => setF({ ...f, state: e.target.value })}>{US_STATE_OPTIONS.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}</Select></Field><Field label="Plan"><Select value={f.plan} onChange={(e) => setF({ ...f, plan: e.target.value })}>{planOptions}</Select></Field></div>
        <Field label="Administrator name" required><Input value={f.adminName} onChange={(e) => setF({ ...f, adminName: e.target.value })} /></Field><Field label="Administrator email" required><Input type="email" value={f.adminEmail} onChange={(e) => setF({ ...f, adminEmail: e.target.value })} /></Field></div></Modal></>);
}
