import { useState } from 'react';
import { PageHeader, Panel, Button, Field, Input, Toggle, Skeleton, Tabs, Timeline, useApi, useAction, api, fmt, useSession, ConfirmDialog, Textarea } from '@propertyos/ui';

const inv = ['/api/identity/config'];
const human = (k: string) => k.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
const SECTIONS: [string, string, string][] = [
  ['features', 'Features', 'Switch whole modules on or off for your company.'],
  ['fees', 'Fees', 'Late fees, application fees and management fees.'],
  ['invoicing', 'Invoicing', 'When rent is invoiced, reminders and payment allocation.'],
  ['approvals', 'Approvals', 'Dollar limits that need an owner or manager sign-off.'],
  ['screening', 'Screening', 'Income, score and document rules for applicants.'],
  ['sla', 'Service levels', 'Response and resolution targets for maintenance and enquiries.'],
  ['renewals', 'Renewals', 'When to offer renewals and how much rent can rise.'],
  ['ai', 'AI', 'Turn each AI capability on or off and set its guardrails.'],
  ['notifications', 'Notifications', 'Channels and quiet hours.'],
  ['branding', 'Branding', 'Name and colours shown to residents and owners.'],
];

/** Renders any JSON config value as an editable form, so every setting is changeable without a deploy. */
function Node({ label, value, onChange, depth = 0 }: { label: string; value: any; onChange: (v: any) => void; depth?: number }) {
  if (typeof value === 'boolean') return <Toggle checked={value} onChange={onChange} label={label} />;
  if (typeof value === 'number') return <Field label={label}><Input type="number" step="any" value={value} onChange={(e) => onChange(e.target.value === '' ? 0 : Number(e.target.value))} /></Field>;
  if (typeof value === 'string') return <Field label={label}><Input value={value} onChange={(e) => onChange(e.target.value)} /></Field>;
  if (Array.isArray(value)) return <Field label={label} hint="One per line"><Textarea rows={Math.min(Math.max(value.length, 2), 6)} value={value.join('\n')} onChange={(e) => onChange(e.target.value.split('\n').map((x) => x.trim()).filter(Boolean).map((x) => (typeof value[0] === 'number' ? Number(x) : x)))} /></Field>;
  if (value && typeof value === 'object') {
    const leaves = Object.entries(value);
    return (
      <div className={depth ? 'rounded-md border border-limestone-200 p-4' : ''}>
        {depth > 0 && <h3 className="mb-3 text-sm font-bold text-graphite-900">{label}</h3>}
        <div className="grid gap-4 sm:grid-cols-2">
          {leaves.map(([k, v]) => (typeof v === 'object' && !Array.isArray(v) && v !== null
            ? <div key={k} className="sm:col-span-2"><Node label={human(k)} value={v} depth={depth + 1} onChange={(nv) => onChange({ ...value, [k]: nv })} /></div>
            : <div key={k} className={Array.isArray(v) ? 'sm:col-span-2' : ''}><Node label={human(k)} value={v} depth={depth + 1} onChange={(nv) => onChange({ ...value, [k]: nv })} /></div>))}
        </div>
      </div>
    );
  }
  return null;
}

export function Configuration() {
  const { can } = useSession();
  const editable = can('SETTINGS:CONFIGURE');
  const { data: cfg } = useApi<any>('/api/identity/config');
  const { data: hist } = useApi<any[]>('/api/identity/config/history');
  const [sec, setSec] = useState('features'); const [draft, setDraft] = useState<Record<string, any>>({}); const [reset, setReset] = useState(false);
  const save = useAction(() => api.put(`/api/identity/config/${sec}?summary=${encodeURIComponent('Updated ' + sec + ' in the admin console')}`, draft[sec]), { success: 'Saved. The change applies immediately.', invalidate: ['/api/identity', '/api/billing', '/api/leasing'], onSuccess: () => setDraft((d) => { const n = { ...d }; delete n[sec]; return n; }) });
  const rollback = useAction((v: number) => api.post(`/api/identity/config/rollback/${v}`), { success: 'Rolled back', invalidate: ['/api/identity'] });
  const resetDemo = useAction(() => api.post<any>('/api/identity/demo/reset'), { success: (r) => r.message, onSuccess: () => setReset(false) });
  if (!cfg) return <Skeleton className="h-96" />;
  const value = draft[sec] ?? cfg[sec];
  const dirty = draft[sec] !== undefined;
  const meta = SECTIONS.find((s) => s[0] === sec)!;
  return (<>
    <PageHeader title="Configuration" description="Fees, approvals, screening, service levels, AI and branding. Changes apply immediately and are versioned." />
    <div className="grid gap-6 lg:grid-cols-[220px_1fr]">
      <nav aria-label="Settings sections" className="flex gap-1 overflow-x-auto lg:flex-col">{SECTIONS.filter((s) => cfg[s[0]]).map(([k, label]) => (
        <button key={k} onClick={() => setSec(k)} className={`whitespace-nowrap rounded px-3 py-2 text-left text-sm font-semibold ${sec === k ? 'bg-federal-900 text-white' : 'text-graphite-700 hover:bg-limestone-200/60'}`}>{label}</button>))}</nav>
      <div className="space-y-6">
        <Panel title={meta[1]} description={meta[2]} actions={editable && <><Button variant="quiet" disabled={!dirty} onClick={() => setDraft((d) => { const n = { ...d }; delete n[sec]; return n; })}>Discard</Button><Button variant="primary" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate(undefined)}>Save changes</Button></>}>
          <fieldset disabled={!editable}><Node label={meta[1]} value={value} onChange={(v) => setDraft({ ...draft, [sec]: v })} /></fieldset>
        </Panel>
        <Panel title="Change history" description="Every change is recorded. Roll back to any earlier version.">
          <ul className="divide-y divide-limestone-200 text-sm">{(hist ?? []).slice(0, 8).map((h) => (
            <li key={h.id} className="flex items-center justify-between gap-3 py-2"><span><b>v{h.version}</b> · {h.summary} <span className="text-graphite-500">by {h.changed_by}, {fmt.date(h.created_at)}</span></span>
              {editable && h.version > 1 && <Button size="sm" variant="quiet" onClick={() => rollback.mutate(h.version)}>Restore</Button>}</li>))}</ul>
        </Panel>
        {editable && <Panel title="Demo data" description="Put this organisation’s sample data back to its starting state in every service."><Button variant="danger" onClick={() => setReset(true)}>Reset demo data</Button></Panel>}
      </div>
    </div>
    <ConfirmDialog open={reset} title="Reset demo data?" body="All records for this organisation are replaced with the sample portfolio. This takes about 10 seconds." confirmLabel="Reset" tone="danger" loading={resetDemo.isPending} onClose={() => setReset(false)} onConfirm={() => resetDemo.mutate(undefined)} />
  </>);
}
