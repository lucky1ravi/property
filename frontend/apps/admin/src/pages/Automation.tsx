import { useState } from 'react';
import { PageHeader, Panel, Button, StatusPill, Stat, StatStrip, DataTable, type Column, Modal, Field, Input, Select, Textarea, Toggle, Badge, Skeleton, ConfirmDialog, useApi, useAction, api, fmt, useSession, Tabs, Timeline, RowActions } from '@propertyos/ui';

const inv = ['/api/workflow'];
const ROLES = ['PROPERTY_MANAGER', 'LEASING_AGENT', 'BROKER', 'ACCOUNTANT', 'ORG_ADMIN'];
const OPS: [string, string][] = [['eq', 'equals'], ['ne', 'is not'], ['gt', 'is above'], ['gte', 'is at least'], ['lt', 'is below'], ['lte', 'is at most'], ['contains', 'contains']];
const human = (e: string) => e.replace(/([A-Z])/g, ' $1').trim();

function RuleModal({ rule, onClose }: { rule: any | 'new' | null; onClose: () => void }) {
  const { data: events } = useApi<{ key: string }[]>(rule ? '/api/workflow/automation/events' : null);
  const blank = { name: '', description: '', triggerEvent: 'LeadCreated', conditions: [] as any[], actions: [{ type: 'NOTIFY', audience: 'ROLE', target: 'PROPERTY_MANAGER', title: '', body: '', priority: 'NORMAL' }], minutesSaved: 5 };
  const [f, setF] = useState<any>(null);
  if (rule && f === null) setTimeout(() => setF(rule === 'new' ? blank : { id: rule.id, name: rule.name, description: rule.description ?? '', triggerEvent: rule.trigger_event, conditions: rule.conditions ?? [], actions: rule.actions ?? [], minutesSaved: rule.minutes_saved ?? 5 }), 0);
  const close = () => { setF(null); onClose(); };
  const save = useAction(() => (f.id ? api.put(`/api/workflow/rules/${f.id}`, f) : api.post('/api/workflow/rules', { ...f, enabled: true })), { success: 'Automation saved', invalidate: inv, onSuccess: close });
  const a = f?.actions?.[0];
  const setA = (k: string, v: any) => setF({ ...f, actions: [{ ...a, [k]: v }, ...f.actions.slice(1)] });
  const setC = (i: number, k: string, v: any) => setF({ ...f, conditions: f.conditions.map((c: any, j: number) => (j === i ? { ...c, [k]: v } : c)) });
  return (
    <Modal open={!!rule && !!f} onClose={close} size="lg" title={f?.id ? 'Edit automation' : 'New automation'} footer={<><Button variant="quiet" onClick={close}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!f?.name || !a?.title} onClick={() => save.mutate(undefined)}>Save automation</Button></>}>
      {f && a && <div className="space-y-5">
        <div className="grid gap-3 sm:grid-cols-2"><Field label="Name" required><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label="Minutes saved per run"><Input type="number" min={0} value={f.minutesSaved} onChange={(e) => setF({ ...f, minutesSaved: Number(e.target.value) })} /></Field></div>
        <Field label="Description"><Input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
        <Panel title="When this happens"><Select value={f.triggerEvent} onChange={(e) => setF({ ...f, triggerEvent: e.target.value })}>{(events ?? []).map((e) => <option key={e.key} value={e.key}>{human(e.key)}</option>)}</Select>
          <div className="mt-3 space-y-2">{f.conditions.map((c: any, i: number) => (
            <div key={i} className="grid grid-cols-[1fr_140px_1fr_auto] gap-2"><Input placeholder="Field, e.g. score" value={c.field} onChange={(e) => setC(i, 'field', e.target.value)} />
              <Select value={c.op} onChange={(e) => setC(i, 'op', e.target.value)}>{OPS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</Select>
              <Input placeholder="Value" value={c.value} onChange={(e) => setC(i, 'value', e.target.value)} /><Button variant="quiet" onClick={() => setF({ ...f, conditions: f.conditions.filter((_: any, j: number) => j !== i) })}>Remove</Button></div>))}
            <Button size="sm" onClick={() => setF({ ...f, conditions: [...f.conditions, { field: '', op: 'gte', value: '' }] })}>Only if…</Button></div></Panel>
        <Panel title="Then do this">
          <div className="grid gap-3 sm:grid-cols-2"><Field label="Notify"><Select value={a.target} onChange={(e) => setA('target', e.target.value)}>{ROLES.map((r) => <option key={r} value={r}>{human(r.toLowerCase().replace(/_/g, ' '))}</option>)}</Select></Field>
            <Field label="Priority"><Select value={a.priority} onChange={(e) => setA('priority', e.target.value)}><option value="LOW">Low</option><option value="NORMAL">Normal</option><option value="HIGH">High</option></Select></Field></div>
          <Field label="Title" className="mt-3" required hint="Use {{field}} to insert event data."><Input value={a.title} onChange={(e) => setA('title', e.target.value)} /></Field>
          <Field label="Message" className="mt-3"><Textarea rows={2} value={a.body} onChange={(e) => setA('body', e.target.value)} /></Field></Panel>
      </div>}
    </Modal>
  );
}

export function Automations() {
  const { can } = useSession(); const editable = can('AUTOMATION:CONFIGURE');
  const { data: rules, isLoading } = useApi<any[]>('/api/workflow/rules');
  const { data: s } = useApi<any>('/api/workflow/automation/summary');
  const [edit, setEdit] = useState<any>(null); const [del, setDel] = useState<any>(null);
  const toggle = useAction((v: { id: string; on: boolean }) => api.post(`/api/workflow/rules/${v.id}/toggle`, { enabled: v.on }), { success: 'Updated', invalidate: inv });
  const remove = useAction(() => api.del(`/api/workflow/rules/${del.id}`), { success: 'Automation deleted', invalidate: inv, onSuccess: () => setDel(null) });
  const cols: Column<any>[] = [
    { key: 'name', header: 'Automation', render: (r) => (<div><div className="font-semibold">{r.name}</div><div className="text-xs text-graphite-500">{r.description}</div></div>) },
    { key: 'trigger_event', header: 'When', hideOnMobile: true, render: (r) => human(r.trigger_event) },
    { key: 'runs', header: 'Runs', align: 'right' },
    { key: 'enabled', header: 'On', align: 'center', render: (r) => <span onClick={(e) => e.stopPropagation()}><Toggle checked={r.enabled} disabled={!editable} onChange={(on) => toggle.mutate({ id: r.id, on })} /></span> },
    { key: 'a', header: '', align: 'right', render: (r) => editable && <RowActions name={r.name} onDelete={() => setDel(r)} /> },
  ];
  return (<>
    <PageHeader title="Automation rules" description="Routine follow-up that runs by itself. Each run is logged and counted toward time saved." actions={editable && <Button variant="primary" onClick={() => setEdit('new')}>New automation</Button>} />
    {s && <StatStrip className="mb-6"><Stat label="Active rules" value={s.active_rules} sub={`of ${s.total_rules}`} /><Stat label="Runs, last 30 days" value={s.runs_30d} /><Stat label="Failed runs" value={s.failed_30d} /><Stat label="Time saved" value={`${s.hours_saved_30d} h`} emphasis /></StatStrip>}
    <Panel padded={false}><DataTable rows={rules} columns={cols} rowKey={(r) => r.id} loading={isLoading} onRowClick={(r) => editable && setEdit(r)} /></Panel>
    {s?.recent?.length > 0 && <Panel title="Recent runs" className="mt-6"><ul className="divide-y divide-limestone-200 text-sm">{s.recent.map((r: any) => <li key={r.id} className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 py-2"><span className="min-w-0 break-words"><b>{r.rule_name}</b> <span className="text-graphite-500">{r.detail}</span></span><span className="flex shrink-0 items-center gap-2"><StatusPill status={r.status} /><span className="text-xs text-graphite-500">{fmt.date(r.created_at)}</span></span></li>)}</ul></Panel>}
    <RuleModal rule={edit} onClose={() => setEdit(null)} />
    <ConfirmDialog open={!!del} title="Delete this automation?" body={del?.name} confirmLabel="Delete" tone="danger" loading={remove.isPending} onClose={() => setDel(null)} onConfirm={() => remove.mutate(undefined)} />
  </>);
}

const CATS = ['OPEN', 'WAITING', 'DONE', 'CANCELLED'];

function Designer({ k, onBack }: { k: string; onBack: () => void }) {
  const { can } = useSession(); const editable = can('WORKFLOW:CONFIGURE');
  const { data } = useApi<any>(`/api/workflow/workflows/${k}`);
  const [def, setDef] = useState<any>(null);
  if (data && def === null) setTimeout(() => setDef(JSON.parse(JSON.stringify(data.current))), 0);
  const save = useAction(() => api.put(`/api/workflow/workflows/${k}`, def), { success: 'Workflow saved. New actions follow it immediately.', invalidate: inv });
  const reset = useAction(() => api.post(`/api/workflow/workflows/${k}/reset`), { success: 'Restored the standard workflow', invalidate: inv, onSuccess: () => setDef(null) });
  if (!def) return <Skeleton className="h-96" />;
  const setS = (i: number, p: any) => setDef({ ...def, states: def.states.map((s: any, j: number) => (j === i ? { ...s, ...p } : s)) });
  const setT = (i: number, p: any) => setDef({ ...def, transitions: def.transitions.map((t: any, j: number) => (j === i ? { ...t, ...p } : t)) });
  return (<>
    <PageHeader title={def.name} back={<button onClick={onBack} className="text-sm font-semibold text-federal-900">All workflows</button>} description="Rename steps, change who can move a record forward, set time targets and require a reason. Changes are validated before saving."
      actions={editable && <><Button variant="quiet" loading={reset.isPending} onClick={() => reset.mutate(undefined)}>Restore standard</Button><Button variant="primary" loading={save.isPending} onClick={() => save.mutate(undefined)}>Save workflow</Button></>} />
    <div className="space-y-6"><fieldset disabled={!editable} className="space-y-6">
      <Panel title="Steps" padded={false}><table className="w-full text-sm"><thead className="bg-limestone-100 text-xs text-graphite-600"><tr><th className="px-4 py-2 text-left">Step</th><th className="px-2 text-left">Label</th><th className="px-2 text-left">Kind</th><th className="px-2 text-left">Time target (hours)</th></tr></thead>
        <tbody>{def.states.map((s: any, i: number) => <tr key={s.key} className="border-t border-limestone-200"><td className="px-4 py-2 font-mono text-xs">{s.key}{s.key === def.initialState && <Badge tone="brand" className="ml-2">Start</Badge>}</td>
          <td className="px-2 py-1.5"><Input value={s.label} onChange={(e) => setS(i, { label: e.target.value })} /></td>
          <td className="px-2"><Select value={s.category} onChange={(e) => setS(i, { category: e.target.value })}>{CATS.map((c) => <option key={c}>{c}</option>)}</Select></td>
          <td className="w-40 px-2"><Input type="number" min={0} value={s.slaHours ?? ''} onChange={(e) => setS(i, { slaHours: e.target.value ? Number(e.target.value) : undefined })} /></td></tr>)}</tbody></table></Panel>
      <Panel title="Transitions" description="Who can move a record from one step to another." padded={false}><div className="overflow-x-auto"><table className="w-full min-w-[720px] text-sm"><thead className="bg-limestone-100 text-xs text-graphite-600"><tr><th className="px-4 py-2 text-left">Button label</th><th className="px-2 text-left">From → To</th><th className="px-2 text-left">Permission needed</th><th className="px-2 text-center">Reason</th></tr></thead>
        <tbody>{def.transitions.map((t: any, i: number) => <tr key={t.action} className="border-t border-limestone-200"><td className="px-4 py-1.5"><Input value={t.label} onChange={(e) => setT(i, { label: e.target.value })} /><div className="mt-0.5 font-mono text-[10px] text-graphite-500">{t.action}{t.system && ' · automatic'}</div></td>
          <td className="px-2 text-xs">{t.from.join(', ')} → <b>{t.to}</b></td>
          <td className="px-2">{t.system ? <span className="text-xs text-graphite-500">Automatic</span> : <Input value={t.permission ?? ''} placeholder="e.g. LISTING:EDIT" onChange={(e) => setT(i, { permission: e.target.value || undefined })} />}</td>
          <td className="px-2 text-center"><input type="checkbox" aria-label="Require a reason" checked={!!t.requiresReason} onChange={(e) => setT(i, { requiresReason: e.target.checked })} /></td></tr>)}</tbody></table></div></Panel>
    </fieldset>
      {(data?.history ?? []).length > 0 && <Panel title="Change history"><Timeline items={data.history.map((h: any) => ({ id: h.id ?? h.version, action: `Version ${h.version}`, actor_name: h.changed_by, created_at: h.created_at, details: h.summary }))} /></Panel>}</div>
  </>);
}

export function Workflows() {
  const [k, setK] = useState<string | null>(null);
  const { data, isLoading } = useApi<any[]>('/api/workflow/workflows');
  if (k) return <Designer k={k} onBack={() => setK(null)} />;
  const cols: Column<any>[] = [
    { key: 'name', header: 'Workflow' }, { key: 'states', header: 'Steps', align: 'right' }, { key: 'transitions', header: 'Transitions', align: 'right' },
    { key: 'customised', header: 'Status', render: (r) => (r.customised ? <Badge tone="brand">Customised v{r.version}</Badge> : <Badge>Standard</Badge>) },
  ];
  return (<><PageHeader title="Workflow designer" description="Every approval path in PropertyOS is configurable. Pick a workflow to change its steps, permissions and time targets." />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.key} loading={isLoading} searchable={false} onRowClick={(r) => setK(r.key)} /></Panel></>);
}
