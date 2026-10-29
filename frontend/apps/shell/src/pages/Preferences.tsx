import { useEffect, useState } from 'react';
import { PageHeader, Panel, Button, Toggle, Skeleton, useApi, useAction, api } from '@propertyos/ui';

/** Each person chooses, per kind of alert, whether it reaches their inbox, email or text message. */
export function Preferences() {
  const { data, isLoading } = useApi<any>('/api/workflow/notifications/preferences');
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => { if (data) setRows(data.categories); }, [data]);
  const save = useAction(() => api.put('/api/workflow/notifications/preferences', rows), { success: 'Preferences saved', invalidate: ['/api/workflow/notifications'] });
  const set = (key: string, field: string, v: boolean) => setRows(rows.map((r) => (r.key === key ? { ...r, [field]: v } : r)));
  const dirty = data && JSON.stringify(rows) !== JSON.stringify(data.categories);
  return (<>
    <PageHeader title="Notification preferences" description="Choose how you hear about each kind of update."
      actions={<Button variant="primary" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate(undefined)}>Save changes</Button>} />
    {isLoading || !data ? <Skeleton className="h-64" /> : (
      <Panel padded={false}>
        <div className="hidden grid-cols-[1fr_90px_90px_90px] gap-2 border-b border-limestone-200 bg-limestone-50 px-4 py-2 text-xs font-semibold text-graphite-600 sm:grid"><span>Update</span><span className="text-center">In app</span><span className="text-center">Email</span><span className="text-center">Text</span></div>
        <ul className="divide-y divide-limestone-200">{rows.map((r) => (
          <li key={r.key} className="grid items-center gap-2 px-4 py-3 sm:grid-cols-[1fr_90px_90px_90px]">
            <span><span className="block text-sm font-semibold text-graphite-900">{r.label}</span><span className="block text-xs text-graphite-500">{r.description}</span></span>
            {([['inApp', 'In app'], ['email', 'Email'], ['sms', 'Text']] as const).map(([f, l]) => (
              <span key={f} className="flex items-center justify-between sm:justify-center"><span className="text-xs text-graphite-600 sm:hidden">{l}</span><Toggle checked={r[f]} onChange={(v) => set(r.key, f, v)} label={`${r.label}: ${l}`} /></span>))}
          </li>))}</ul>
        <p className="border-t border-limestone-200 px-4 py-3 text-xs text-graphite-500">{data.note} Text messages are only sent for urgent alerts. Email and text are simulated in this demo environment.</p>
      </Panel>)}
  </>);
}
