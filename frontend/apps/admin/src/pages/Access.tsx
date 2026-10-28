import { useMemo, useState } from 'react';
import clsx from 'clsx';
import {
  PageHeader, Panel, Button, StatusPill, DataTable, type Column, Tabs, Modal, Field, Input, Select, Textarea, Badge, Skeleton, EmptyState, ConfirmDialog,
  useApi, useAction, api, fmt, useSession,
} from '@propertyos/ui';

const inv = ['/api/identity/roles', '/api/identity/users'];

function UserModal({ user, roles, onClose }: { user: any | 'new' | null; roles: any[]; onClose: () => void }) {
  const isNew = user === 'new';
  const [f, setF] = useState<any>(null);
  if (user && f === null) setTimeout(() => setF(isNew ? { name: '', email: '', title: '', roleIds: [] } : { name: user.name, title: user.title ?? '', status: user.status, roleIds: (user.roles ?? []).map((r: any) => r.id) }), 0);
  const close = () => { setF(null); onClose(); };
  const save = useAction(() => (isNew ? api.post('/api/identity/users', f) : api.put(`/api/identity/users/${user.id}`, f)), { success: isNew ? 'User invited' : 'User updated', invalidate: inv, onSuccess: close });
  const toggle = (id: string) => setF({ ...f, roleIds: f.roleIds.includes(id) ? f.roleIds.filter((x: string) => x !== id) : [...f.roleIds, id] });
  return (
    <Modal open={!!user && !!f} onClose={close} title={isNew ? 'Add a user' : user?.name ?? ''} footer={<><Button variant="quiet" onClick={close}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!f?.name || (isNew && !f?.email)} onClick={() => save.mutate(undefined)}>{isNew ? 'Send invite' : 'Save'}</Button></>}>
      {f && <div className="space-y-3">
        <Field label="Full name" required><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        {isNew && <Field label="Work email" required><Input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>}
        <Field label="Job title"><Input value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Field>
        {!isNew && <Field label="Status"><Select value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><option value="ACTIVE">Active</option><option value="SUSPENDED">Suspended</option></Select></Field>}
        <fieldset><legend className="mb-1.5 text-sm font-semibold text-graphite-800">Roles</legend>
          <div className="grid gap-1.5 sm:grid-cols-2">{roles.map((r) => (
            <label key={r.id} className="flex items-center gap-2 rounded border border-limestone-200 px-3 py-2 text-sm"><input type="checkbox" checked={f.roleIds.includes(r.id)} onChange={() => toggle(r.id)} /><span className="h-2.5 w-2.5 rounded-full" style={{ background: r.color }} />{r.name}</label>))}</div></fieldset>
      </div>}
    </Modal>
  );
}

function Users({ roles }: { roles: any[] }) {
  const { can } = useSession();
  const { data, isLoading } = useApi<any[]>('/api/identity/users');
  const [u, setU] = useState<any>(null);
  const cols: Column<any>[] = [
    { key: 'name', header: 'Name', render: (r) => (<div><div className="font-semibold">{r.name}</div><div className="text-xs text-graphite-500">{r.email}</div></div>) },
    { key: 'roles', header: 'Roles', value: (r) => (r.roles ?? []).map((x: any) => x.name).join(', '), render: (r) => <div className="flex flex-wrap gap-1">{(r.roles ?? []).map((x: any) => <Badge key={x.id}>{x.name}</Badge>)}</div> },
    { key: 'persona', header: 'Type', hideOnMobile: true },
    { key: 'status', header: 'Status', render: (r) => <StatusPill status={r.status} /> },
  ];
  return (<Panel title="People" padded={false} actions={can('USER:CREATE') && <Button size="sm" variant="primary" onClick={() => setU('new')}>Add user</Button>}>
    <DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} onRowClick={(r) => can('USER:EDIT') && setU(r)} exportName="users" />
    <UserModal user={u} roles={roles} onClose={() => setU(null)} /></Panel>);
}

/** The permission matrix: features down, actions across. Unlimited custom roles, each with its own grants. */
function Matrix({ role, catalog, onSaved }: { role: any; catalog: any; onSaved: () => void }) {
  const { can } = useSession();
  const editable = can('ROLE:CONFIGURE');
  const initial = useMemo(() => new Set<string>((role.permissions ?? []).map((p: any) => `${p.feature}:${p.action}`)), [role]);
  const [sel, setSel] = useState<Set<string>>(initial);
  const [limits, setLimits] = useState<Record<string, string>>(() => Object.fromEntries((role.permissions ?? []).filter((p: any) => p.max_amount != null).map((p: any) => [`${p.feature}:${p.action}`, String(p.max_amount)])));
  const dirty = sel.size !== initial.size || [...sel].some((k) => !initial.has(k)) || JSON.stringify(limits) !== JSON.stringify(Object.fromEntries((role.permissions ?? []).filter((p: any) => p.max_amount != null).map((p: any) => [`${p.feature}:${p.action}`, String(p.max_amount)])));
  const save = useAction(() => api.put(`/api/identity/roles/${role.id}/permissions`, [...sel].map((k) => { const [feature, action] = k.split(':'); return { feature, action, maxAmount: limits[k] ? Number(limits[k]) : null }; })), { success: 'Permissions saved', invalidate: inv, onSuccess: onSaved });
  const modules: string[] = [...new Set<string>(catalog.features.map((f: any) => f.module))];
  const toggle = (k: string) => { const n = new Set(sel); n.has(k) ? n.delete(k) : n.add(k); setSel(n); };
  const rowAll = (f: any, on: boolean) => { const n = new Set(sel); f.actions.forEach((a: string) => (on ? n.add(`${f.key}:${a}`) : n.delete(`${f.key}:${a}`))); setSel(n); };
  return (
    <div>
      <div className="mb-3 flex items-center justify-between gap-3">
        <p className="text-sm text-graphite-600">{role.system_role ? 'A built-in role. You can adjust it or copy it into a new role.' : 'A custom role.'} {sel.size} permissions granted.</p>
        {editable && <Button variant="primary" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate(undefined)}>Save changes</Button>}
      </div>
      <div className="overflow-x-auto rounded-md border border-limestone-200">
        <table className="w-full min-w-[760px] border-collapse text-sm">
          <thead className="bg-limestone-100 text-xs text-graphite-600"><tr><th className="px-4 py-2 text-left font-semibold">Feature</th>{catalog.actions.map((a: string) => <th key={a} className="px-2 py-2 text-center font-semibold">{a.charAt(0) + a.slice(1).toLowerCase()}</th>)}<th className="px-2 py-2 text-center font-semibold">All</th></tr></thead>
          <tbody>
            {modules.map((m) => (<>
              <tr key={m} className="bg-limestone-50"><td colSpan={catalog.actions.length + 2} className="px-4 py-1.5 text-xs font-bold text-graphite-700">{m}</td></tr>
              {catalog.features.filter((f: any) => f.module === m && (!f.platformOnly || role.scope === 'PLATFORM')).map((f: any) => (
                <tr key={f.key} className="border-t border-limestone-200">
                  <td className="px-4 py-2 text-graphite-900">{f.label}</td>
                  {catalog.actions.map((a: string) => { const k = `${f.key}:${a}`; const has = f.actions.includes(a);
                    return <td key={a} className="px-2 py-2 text-center">{has ? <input type="checkbox" aria-label={`${f.label} ${a}`} disabled={!editable} checked={sel.has(k)} onChange={() => toggle(k)} /> : <span className="text-limestone-300">–</span>}</td>; })}
                  <td className="px-2 py-2 text-center">{editable && <button className="text-xs font-semibold text-federal-900 hover:underline" onClick={() => rowAll(f, !f.actions.every((a: string) => sel.has(`${f.key}:${a}`)))}>{f.actions.every((a: string) => sel.has(`${f.key}:${a}`)) ? 'Clear' : 'All'}</button>}</td>
                </tr>))}
            </>))}
          </tbody>
        </table>
      </div>
      <Panel title="Approval limits" description="Cap the dollar amount this role can approve, per action." className="mt-4">
        <div className="grid gap-3 sm:grid-cols-3">{['INVOICE:APPROVE', 'PAYMENT:APPROVE', 'WORK_ORDER:APPROVE'].filter((k) => sel.has(k)).map((k) => (
          <Field key={k} label={k.replace(':', ' · ').replace('_', ' ').toLowerCase()} hint="Blank means no limit"><Input type="number" disabled={!editable} value={limits[k] ?? ''} onChange={(e) => setLimits({ ...limits, [k]: e.target.value })} /></Field>))}
          {!['INVOICE:APPROVE', 'PAYMENT:APPROVE', 'WORK_ORDER:APPROVE'].some((k) => sel.has(k)) && <p className="text-sm text-graphite-500">This role has no approval permissions.</p>}</div>
      </Panel>
    </div>
  );
}

function FieldRules({ role, catalog }: { role: any; catalog: any }) {
  const { can } = useSession();
  const [rules, setRules] = useState<Record<string, string>>(role.field_rules ?? {});
  const save = useAction(() => api.put(`/api/identity/roles/${role.id}`, { fieldRules: Object.fromEntries(Object.entries(rules).filter(([, v]) => v !== 'VISIBLE')) }), { success: 'Field rules saved', invalidate: inv });
  return (
    <Panel title="Sensitive fields" description="Choose what this role sees for personal and financial data. Masked shows the last digits only." className="mt-4"
      actions={can('ROLE:CONFIGURE') && <Button size="sm" variant="primary" loading={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button>}>
      <div className="grid gap-3 sm:grid-cols-2">{catalog.fields.map((f: any) => (
        <Field key={f.key} label={f.label}><Select disabled={!can('ROLE:CONFIGURE')} value={rules[f.key] ?? 'VISIBLE'} onChange={(e) => setRules({ ...rules, [f.key]: e.target.value })}><option value="VISIBLE">Visible</option><option value="MASKED">Masked</option><option value="HIDDEN">Hidden</option></Select></Field>))}</div>
    </Panel>
  );
}

function Roles({ roles }: { roles: any[] }) {
  const { can } = useSession();
  const { data: catalog } = useApi<any>('/api/identity/catalog');
  const [id, setId] = useState<string>(''); const [creating, setCreating] = useState(false); const [del, setDel] = useState(false);
  const [n, setN] = useState({ name: '', description: '', scope: 'ORG', copyFromRoleId: '' });
  const role = roles.find((r) => r.id === id) ?? roles[0];
  const create = useAction(() => api.post<any>('/api/identity/roles', { ...n, copyFromRoleId: n.copyFromRoleId || undefined }), { success: 'Role created', invalidate: inv, onSuccess: (r) => { setCreating(false); setId(r.id); setN({ name: '', description: '', scope: 'ORG', copyFromRoleId: '' }); } });
  const remove = useAction(() => api.del(`/api/identity/roles/${role.id}`), { success: 'Role deleted', invalidate: inv, onSuccess: () => { setDel(false); setId(''); } });
  if (!catalog || !role) return <Skeleton className="h-96" />;
  return (
    <div className="grid gap-6 lg:grid-cols-[260px_1fr]">
      <Panel title="Roles" padded={false} actions={can('ROLE:CONFIGURE') && <Button size="sm" onClick={() => setCreating(true)}>New role</Button>}>
        <ul>{roles.map((r) => (
          <li key={r.id}><button onClick={() => setId(r.id)} className={clsx('flex w-full items-center gap-3 border-l-2 px-4 py-2.5 text-left text-sm', r.id === role.id ? 'border-federal-900 bg-federal-50' : 'border-transparent hover:bg-limestone-50')}>
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: r.color }} /><span className="min-w-0 flex-1"><span className="block truncate font-semibold">{r.name}</span><span className="text-xs text-graphite-500">{r.user_count} {r.user_count === 1 ? "user" : "users"} · {r.permission_count} permissions</span></span></button></li>))}</ul>
      </Panel>
      <div>
        <PageHeader title={role.name} description={role.description} actions={can('ROLE:CONFIGURE') && !role.system_role && <Button variant="danger" onClick={() => setDel(true)}>Delete role</Button>} />
        <Matrix key={role.id + role.version + role.permission_count} role={role} catalog={catalog} onSaved={() => undefined} />
        <FieldRules key={role.id + role.version} role={role} catalog={catalog} />
      </div>
      <Modal open={creating} onClose={() => setCreating(false)} title="New role" footer={<><Button variant="quiet" onClick={() => setCreating(false)}>Cancel</Button><Button variant="primary" disabled={!n.name} loading={create.isPending} onClick={() => create.mutate(undefined)}>Create role</Button></>}>
        <div className="space-y-3"><Field label="Name" required><Input value={n.name} onChange={(e) => setN({ ...n, name: e.target.value })} placeholder="e.g. Regional manager" /></Field>
          <Field label="Description"><Textarea rows={2} value={n.description} onChange={(e) => setN({ ...n, description: e.target.value })} /></Field>
          <Field label="Data scope" hint="Which records this role can see."><Select value={n.scope} onChange={(e) => setN({ ...n, scope: e.target.value })}>{Object.entries(catalog.scopes).map(([k, label]) => <option key={k} value={k}>{String(label)}</option>)}</Select></Field>
          <Field label="Start from" hint="Copy another role’s permissions, then adjust."><Select value={n.copyFromRoleId} onChange={(e) => setN({ ...n, copyFromRoleId: e.target.value })}><option value="">Nothing (no permissions)</option>{roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</Select></Field></div>
      </Modal>
      <ConfirmDialog open={del} title="Delete this role?" body="People who only have this role will lose access." confirmLabel="Delete role" tone="danger" loading={remove.isPending} onClose={() => setDel(false)} onConfirm={() => remove.mutate(undefined)} />
    </div>
  );
}

export function Access() {
  const [tab, setTab] = useState<'users' | 'roles'>('users');
  const { data: roles } = useApi<any[]>('/api/identity/roles');
  return (<><PageHeader title="Users and roles" description="Create any number of roles and choose exactly which features and actions each can use." />
    <Tabs tabs={[{ key: 'users', label: 'Users' }, { key: 'roles', label: 'Roles and permissions', count: roles?.length }]} value={tab} onChange={setTab} className="mb-6" />
    {!roles ? <Skeleton className="h-64" /> : tab === 'users' ? <Users roles={roles} /> : <Roles roles={roles} />}</>);
}
