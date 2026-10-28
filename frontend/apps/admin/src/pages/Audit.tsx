import { useState } from 'react';
import { PageHeader, Panel, DataTable, type Column, Select, useApi, fmt, Stat, StatStrip, Button, Field, Input, Modal, Badge, useAction, api, TrendChart, Skeleton } from '@propertyos/ui';

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

export function Platform() {
  const { data: s } = useApi<any>('/api/identity/platform/summary');
  const { data: orgs, isLoading } = useApi<any[]>('/api/identity/platform/orgs');
  const [open, setOpen] = useState(false); const [f, setF] = useState({ name: '', state: 'TX', plan: 'GROWTH', adminName: '', adminEmail: '' });
  const create = useAction(() => api.post('/api/identity/platform/orgs', f), { success: 'Organisation created', invalidate: ['/api/identity/platform'], onSuccess: () => setOpen(false) });
  const cols: Column<any>[] = [{ key: 'name', header: 'Organisation' }, { key: 'state', header: 'State' }, { key: 'plan', header: 'Plan', render: (r) => <Badge tone="brand">{r.plan}</Badge> }, { key: 'status', header: 'Status' }, { key: 'user_count', header: 'Users', align: 'right' }, { key: 'created_at', header: 'Created', render: (r) => fmt.date(r.created_at) }];
  return (<><PageHeader title="Platform" description="Every company on PropertyOS. Data stays isolated per company." actions={<Button variant="primary" onClick={() => setOpen(true)}>Onboard company</Button>} />
    {s && <StatStrip className="mb-6"><Stat label="Companies" value={s.organizations} emphasis /><Stat label="Users" value={s.users} /><Stat label="Active, last 7 days" value={s.activeUsers7d} /><Stat label="Config changes, 30 days" value={s.configChanges30d} /></StatStrip>}
    <Panel padded={false}><DataTable rows={orgs} columns={cols} rowKey={(r) => r.id} loading={isLoading} /></Panel>
    <Modal open={open} onClose={() => setOpen(false)} title="Onboard a company" footer={<><Button variant="quiet" onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" loading={create.isPending} disabled={!f.name || !f.adminEmail || !f.adminName} onClick={() => create.mutate(undefined)}>Create company</Button></>}>
      <div className="space-y-3"><Field label="Company name" required><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <div className="grid grid-cols-2 gap-3"><Field label="State"><Input maxLength={2} value={f.state} onChange={(e) => setF({ ...f, state: e.target.value.toUpperCase() })} /></Field><Field label="Plan"><Select value={f.plan} onChange={(e) => setF({ ...f, plan: e.target.value })}><option value="STARTER">Starter</option><option value="GROWTH">Growth</option><option value="ENTERPRISE">Enterprise</option></Select></Field></div>
        <Field label="Administrator name" required><Input value={f.adminName} onChange={(e) => setF({ ...f, adminName: e.target.value })} /></Field><Field label="Administrator email" required><Input type="email" value={f.adminEmail} onChange={(e) => setF({ ...f, adminEmail: e.target.value })} /></Field></div></Modal></>);
}
