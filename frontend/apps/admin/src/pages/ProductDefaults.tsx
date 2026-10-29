import { useState } from 'react';
import { PageHeader, Panel, Button, Skeleton, ConfirmDialog, useApi, useAction, api, useSession } from '@propertyos/ui';
import { Node, SECTIONS } from './Config';

const PRODUCT_SECTIONS = SECTIONS.filter((s) => s[0] !== 'branding' && s[0] !== 'stateRules');

/**
 * What every company starts with. The platform operator sets these once; a company can still change the settings
 * its own administrators are allowed to, and anything it changes stays its own.
 */
export function ProductDefaults() {
  const { can } = useSession();
  const editable = can('PLATFORM:CONFIGURE');
  const { data } = useApi<{ values: Record<string, any>; changed: string[]; companies: number }>('/api/identity/platform/defaults');
  const [sec, setSec] = useState('features');
  const [draft, setDraft] = useState<Record<string, any>>({});
  const [confirm, setConfirm] = useState(false);
  const inv = ['/api/identity'];
  const save = useAction(() => api.put(`/api/identity/platform/defaults/${sec}`, draft[sec]), {
    success: 'Saved. New and existing companies pick this up now.', invalidate: inv,
    onSuccess: () => setDraft((d) => { const n = { ...d }; delete n[sec]; return n; }),
  });
  const reset = useAction(() => api.del(`/api/identity/platform/defaults/${sec}`), {
    success: 'Back to the built-in default.', invalidate: inv,
    onSuccess: () => { setConfirm(false); setDraft((d) => { const n = { ...d }; delete n[sec]; return n; }); },
  });
  if (!data) return <Skeleton className="h-96" />;
  const shown = PRODUCT_SECTIONS.filter((s) => data.values[s[0]]);
  const meta = shown.find((s) => s[0] === sec) ?? shown[0];
  if (!meta) return null;
  const key = meta[0];
  const value = draft[key] ?? data.values[key];
  const dirty = draft[key] !== undefined;
  const changed = data.changed.includes(key);
  return (<>
    <PageHeader title="Product defaults" description={`The starting settings for every company on PropertyOS (${data.companies} today). A company can still adjust what its own admins are allowed to; anything it changes stays its own.`} />
    <div className="grid gap-6 lg:grid-cols-[220px_1fr]">
      <nav aria-label="Product setting sections" className="flex gap-1 overflow-x-auto lg:flex-col">{shown.map(([k, label]) => (
        <button key={k} onClick={() => setSec(k)} className={`whitespace-nowrap rounded px-3 py-2 text-left text-sm font-semibold ${key === k ? 'bg-federal-900 text-white' : 'text-graphite-700 hover:bg-limestone-200/60'}`}>
          {label}{data.changed.includes(k) && <span className="ml-2 text-2xs font-normal opacity-80">changed</span>}</button>))}</nav>
      <Panel title={meta[1]} description={meta[2].replace('your company', 'every company')}
        actions={editable && <>
          {changed && <Button variant="quiet" onClick={() => setConfirm(true)}>Back to built-in</Button>}
          <Button variant="quiet" disabled={!dirty} onClick={() => setDraft((d) => { const n = { ...d }; delete n[key]; return n; })}>Discard</Button>
          <Button variant="primary" disabled={!dirty} loading={save.isPending} onClick={() => { setSec(key); save.mutate(undefined as never); }}>Save for all companies</Button></>}>
        <fieldset disabled={!editable}><Node label={meta[1]} value={value} onChange={(v) => setDraft({ ...draft, [key]: v })} /></fieldset>
      </Panel>
    </div>
    <ConfirmDialog open={confirm} title="Back to the built-in default?" body={`${meta[1]} returns to what PropertyOS ships with, for every company that has not changed it.`}
      confirmLabel="Reset" tone="danger" loading={reset.isPending} onClose={() => setConfirm(false)} onConfirm={() => reset.mutate(undefined as never)} />
  </>);
}
