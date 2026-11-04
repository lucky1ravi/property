import { useState } from 'react';
import { Copy, Plus, Send } from 'lucide-react';
import {
  PageHeader, Panel, DataTable, type Column, useApi, usePagedApi, useAction, api, fmt, Button, Badge, Field, Input, Select, Modal, Drawer, Tabs, ConfirmDialog, useSession, toast,
} from '@propertyos/ui';

/**
 * Webhooks: a company sends events (application submitted, lease sent, lease signed, payment received, work order created) to other systems as signed JSON.
 * The signing secret is shown once, when an endpoint is made or its secret is rotated.
 */

const STATUS_TONE: Record<string, 'good' | 'warn' | 'crit' | 'neutral'> = { SUCCEEDED: 'good', PENDING: 'warn', FAILED: 'crit' };
const STATUS_LABEL: Record<string, string> = { SUCCEEDED: 'Delivered', PENDING: 'Waiting to retry', FAILED: 'Failed' };
const host = (u: string) => { try { return new URL(u).host; } catch { return u; } };

async function copy(text: string, what: string) {
  try {
    await navigator.clipboard.writeText(text);
    toast(`${what} copied`, { tone: 'success' });
  } catch {
    toast('Could not copy. Select the text and copy it by hand.', { tone: 'warning' });
  }
}

function SecretDialog({ ep, onClose }: { ep: any; onClose: () => void }) {
  return (<Modal open onClose={onClose} title={`Signing secret for ${ep.name}`} footer={<Button variant="primary" onClick={onClose}>I have saved it</Button>}>
    <div className="space-y-3">
      <div className="rounded border border-warn-300 bg-warn-50 p-3 text-sm text-graphite-800" role="note">Copy the secret now. For your security it is not shown again. If you lose it, rotate it to make a new one.</div>
      <div className="flex items-center gap-2"><code className="min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded bg-limestone-100 px-2 py-1.5 text-xs" data-testid="webhook-secret">{ep.secret}</code>
        <Button size="sm" variant="secondary" icon={<Copy size={13} />} aria-label="Copy signing secret" onClick={() => copy(ep.secret, 'Signing secret')}>Copy</Button></div>
      <p className="text-xs text-graphite-600">Use it to check that an event really came from PropertyOS. The steps are under “How to verify an event” on the Endpoints tab.</p>
    </div>
  </Modal>);
}

function Editor({ ep, events, onClose, onSecret }: { ep?: any; events: any[]; onClose: () => void; onSecret: (e: any) => void }) {
  const [f, setF] = useState<any>({ name: ep?.name ?? '', url: ep?.url ?? '', events: ep?.events ?? [] as string[] });
  const toggle = (k: string) => setF({ ...f, events: f.events.includes(k) ? f.events.filter((x: string) => x !== k) : [...f.events, k] });
  const save = useAction(() => (ep ? api.put<any>(`/api/workflow/webhooks/${ep.id}`, f) : api.post<any>('/api/workflow/webhooks', f)), {
    success: ep ? 'Endpoint saved' : 'Endpoint added', invalidate: ['/api/workflow/webhooks'], onSuccess: (r: any) => { onClose(); if (!ep && r?.secret) onSecret(r); },
  });
  return (<Modal open onClose={onClose} title={ep ? 'Edit endpoint' : 'Add endpoint'} footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!f.name.trim() || !f.url.trim() || f.events.length === 0} onClick={() => save.mutate(undefined)}>{ep ? 'Save' : 'Add endpoint'}</Button></>}>
    <div className="space-y-3">
      <Field label="Name" required><Input value={f.name} maxLength={120} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="CRM" /></Field>
      <Field label="Address" required hint="Must start with https://. Addresses inside a private network are not allowed."><Input value={f.url} maxLength={500} onChange={(e) => setF({ ...f, url: e.target.value })} placeholder="https://hooks.example.com/propertyos" /></Field>
      <fieldset><legend className="mb-1 text-[13px] font-semibold text-graphite-800">Events to send <span className="text-crit-600" aria-hidden>*</span></legend>
        <div className="space-y-1.5">{events.map((e) => (
          <label key={e.key} className="flex cursor-pointer items-start gap-2 rounded border border-limestone-200 px-3 py-2 text-sm has-[:checked]:border-federal-700 has-[:checked]:bg-federal-50">
            <input type="checkbox" className="mt-0.5" checked={f.events.includes(e.key)} onChange={() => toggle(e.key)} aria-label={e.label} />
            <span><span className="font-semibold text-graphite-900">{e.label}</span> <code className="text-2xs text-graphite-500">{e.key}</code><span className="block text-xs text-graphite-600">{e.description}</span></span></label>))}</div></fieldset>
    </div>
  </Modal>);
}

function TestDialog({ ep, onClose, onLog }: { ep: any; onClose: () => void; onLog: () => void }) {
  const [event, setEvent] = useState<string>(ep.events[0]);
  const [res, setRes] = useState<any>(null);
  const send = useAction(() => api.post<any>(`/api/workflow/webhooks/${ep.id}/test`, { event }), { silent: true, invalidate: ['/api/workflow/webhooks'], onSuccess: setRes });
  return (<Modal open onClose={onClose} title={`Send a test event to ${ep.name}`} footer={<><Button variant="quiet" onClick={onClose}>Close</Button>{res && <Button variant="secondary" onClick={onLog}>View in delivery log</Button>}<Button variant="primary" icon={<Send size={14} />} loading={send.isPending} onClick={() => { setRes(null); send.mutate(undefined); }}>Send test event</Button></>}>
    <div className="space-y-3">
      <Field label="Event" hint="A made-up example, signed and shaped like a real one and marked test: true."><Select value={event} onChange={(e) => setEvent(e.target.value)}>{ep.events.map((k: string) => <option key={k} value={k}>{k}</option>)}</Select></Field>
      {send.error && <p className="text-sm text-crit-700" role="alert">{(send.error as any)?.message ?? 'The test could not be sent.'}</p>}
      {res && <div className="rounded border border-limestone-200 p-3 text-sm" data-testid="test-result" aria-live="polite">
        <div className="flex items-center gap-2"><Badge tone={STATUS_TONE[res.status] ?? 'neutral'}>{STATUS_LABEL[res.status] ?? res.status}</Badge>
          <span>{res.response_code ? `The receiver answered ${res.response_code}` : 'No answer from the receiver'}{res.duration_ms ? ` in ${res.duration_ms} ms` : ''}.</span></div>
        {res.last_error && <p className="mt-2 text-xs text-graphite-700">{res.last_error}</p>}</div>}
    </div>
  </Modal>);
}

function DeliveryDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const { data: d } = useApi<any>(`/api/workflow/webhooks/deliveries/${id}`);
  const { can } = useSession();
  const retry = useAction(() => api.post(`/api/workflow/webhooks/deliveries/${id}/retry`, {}), { success: 'Queued to be tried again now', invalidate: ['/api/workflow/webhooks'], onSuccess: onClose });
  let body = d?.payload ?? '';
  try { body = JSON.stringify(JSON.parse(d.payload), null, 2); } catch { /* shown as it is */ }
  return (<Drawer open onClose={onClose} title={d ? d.event_type : 'Delivery'} subtitle={d ? `${d.endpoint_name} · ${host(d.endpoint_url)}` : undefined} width={560}>
    {!d ? <p className="text-sm text-graphite-600">Loading…</p> : (<div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2"><Badge tone={STATUS_TONE[d.status] ?? 'neutral'}>{STATUS_LABEL[d.status] ?? d.status}</Badge>{d.test && <Badge tone="brand">Test</Badge>}
        <span className="text-sm text-graphite-700">{d.response_code ? `Response ${d.response_code}` : 'No response'} · {d.attempts} {d.attempts === 1 ? 'attempt' : 'attempts'}{d.duration_ms ? ` · ${d.duration_ms} ms` : ''}</span></div>
      {d.last_error && <div className="rounded bg-crit-50 p-3 text-sm text-graphite-900" role="note">{d.last_error}</div>}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
        <div><dt className="text-xs text-graphite-500">Event id</dt><dd className="font-mono text-xs">{d.event_id}</dd></div>
        <div><dt className="text-xs text-graphite-500">Created</dt><dd>{fmt.dateTime(d.created_at)}</dd></div>
        <div><dt className="text-xs text-graphite-500">Last attempt</dt><dd>{d.last_attempt_at ? fmt.dateTime(d.last_attempt_at) : '–'}</dd></div>
        <div><dt className="text-xs text-graphite-500">{d.status === 'PENDING' ? 'Next attempt' : 'Delivered'}</dt><dd>{d.status === 'PENDING' ? (d.next_attempt_at ? fmt.dateTime(d.next_attempt_at) : '–') : d.delivered_at ? fmt.dateTime(d.delivered_at) : '–'}</dd></div>
      </dl>
      <div><div className="mb-1 text-xs font-semibold text-graphite-700">What was sent</div><pre className="max-h-80 overflow-auto rounded bg-graphite-950 p-3 text-xs text-limestone-100" aria-label="Delivery payload">{body}</pre></div>
      {can('WEBHOOK:EDIT') && d.status !== 'SUCCEEDED' && !d.test && <Button variant="primary" loading={retry.isPending} onClick={() => retry.mutate(undefined)}>Try again now</Button>}
    </div>)}
  </Drawer>);
}

export function Webhooks() {
  const { can } = useSession();
  const [tab, setTab] = useState<'endpoints' | 'log'>('endpoints');
  const { data: endpoints } = useApi<any[]>('/api/workflow/webhooks', { refetchInterval: 8000 });
  const { data: events } = useApi<any[]>('/api/workflow/webhooks/events');
  const [edit, setEdit] = useState<any | 'new' | null>(null);
  const [secret, setSecret] = useState<any>(null);
  const [testing, setTesting] = useState<any>(null);
  const [deleting, setDeleting] = useState<any>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [endpointFilter, setEndpointFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const logPath = `/api/workflow/webhooks/deliveries?${new URLSearchParams({ ...(endpointFilter ? { endpointId: endpointFilter } : {}), ...(statusFilter ? { status: statusFilter } : {}) }).toString()}`;
  const log = usePagedApi<any>(logPath, { refetchInterval: 5000 });
  const toggle = useAction((e: any) => api.put(`/api/workflow/webhooks/${e.id}`, { active: !e.active }), { success: 'Saved', invalidate: ['/api/workflow/webhooks'] });
  const rotate = useAction((e: any) => api.post<any>(`/api/workflow/webhooks/${e.id}/rotate-secret`, {}), { success: 'New secret made. Deliveries are signed with it from now on.', invalidate: ['/api/workflow/webhooks'], onSuccess: setSecret });
  const remove = useAction((e: any) => api.del(`/api/workflow/webhooks/${e.id}`), { success: 'Endpoint deleted', invalidate: ['/api/workflow/webhooks'], onSuccess: () => setDeleting(null) });
  const label = (k: string) => (events ?? []).find((e) => e.key === k)?.label ?? k;

  const cols: Column<any>[] = [
    { key: 'name', header: 'Endpoint', render: (e) => (<div><div className="font-semibold">{e.name}</div><div className="text-xs text-graphite-500" title={e.url}>{host(e.url)}</div></div>) },
    { key: 'events', header: 'Events', sortable: false, hideOnMobile: true, render: (e) => <div className="flex flex-wrap gap-1">{e.events.map((k: string) => <Badge key={k}>{label(k)}</Badge>)}</div> },
    { key: 'active', header: 'State', render: (e) => (e.active ? <Badge tone="good">Active</Badge> : <div><Badge tone="warn">Switched off</Badge>{e.disabled_reason && <div className="mt-0.5 max-w-[16rem] text-2xs text-graphite-500">{e.disabled_reason}</div>}</div>) },
    { key: 'delivered_24h', header: 'Last 24 hours', hideOnMobile: true, value: (e) => Number(e.delivered_24h), render: (e) => <span>{e.delivered_24h} delivered{Number(e.failed_24h) > 0 && <span className="text-crit-700">, {e.failed_24h} failed</span>}{Number(e.pending) > 0 && <span className="text-graphite-500">, {e.pending} waiting</span>}</span> },
    { key: 'actions', header: '', sortable: false, render: (e) => (can('WEBHOOK:EDIT') ? <div className="flex flex-wrap justify-end gap-1">
      <Button size="sm" variant="quiet" aria-label={`Send a test event to ${e.name}`} onClick={() => setTesting(e)}>Test</Button>
      <Button size="sm" variant="quiet" aria-label={`Edit ${e.name}`} onClick={() => setEdit(e)}>Edit</Button>
      <Button size="sm" variant="quiet" aria-label={`${e.active ? 'Switch off' : 'Switch on'} ${e.name}`} loading={toggle.isPending} onClick={() => toggle.mutate(e)}>{e.active ? 'Switch off' : 'Switch on'}</Button>
      <Button size="sm" variant="quiet" aria-label={`Rotate the secret for ${e.name}`} onClick={() => rotate.mutate(e)}>Rotate secret</Button>
      {can('WEBHOOK:DELETE') && <Button size="sm" variant="quiet" aria-label={`Delete ${e.name}`} onClick={() => setDeleting(e)}>Delete</Button>}</div> : null) },
  ];
  const logCols: Column<any>[] = [
    { key: 'created_at', header: 'When', value: (d) => d.created_at, render: (d) => fmt.dateTime(d.created_at) },
    { key: 'event_type', header: 'Event', render: (d) => (<div className="flex items-center gap-1.5"><span>{label(d.event_type)}</span>{d.test && <Badge tone="brand">Test</Badge>}</div>) },
    { key: 'endpoint_name', header: 'Endpoint', hideOnMobile: true, sortable: false },
    { key: 'status', header: 'Status', render: (d) => <Badge tone={STATUS_TONE[d.status] ?? 'neutral'}>{STATUS_LABEL[d.status] ?? d.status}</Badge> },
    { key: 'response_code', header: 'Response', align: 'right', value: (d) => Number(d.response_code ?? 0), render: (d) => (d.response_code ?? '–') },
    { key: 'attempts', header: 'Attempts', align: 'right', hideOnMobile: true, value: (d) => Number(d.attempts) },
    { key: 'next_attempt_at', header: 'Next try', hideOnMobile: true, render: (d) => (d.status === 'PENDING' && d.next_attempt_at ? fmt.dateTime(d.next_attempt_at) : '–') },
  ];
  const sample = `// Node, with Express. Use the raw body: re-serialising it changes the bytes.
import crypto from 'node:crypto';

function isFromPropertyOS(req, rawBody, secret) {
  const timestamp = req.headers['x-propertyos-timestamp'];
  if (Math.abs(Date.now() / 1000 - Number(timestamp)) > 300) return false;           // older than 5 minutes: reject a replay
  const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(\`\${timestamp}.\${rawBody}\`).digest('hex');
  const given = req.headers['x-propertyos-signature'] ?? '';
  return given.length === expected.length && crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
}`;

  return (<>
    <PageHeader title="Webhooks" description="Send events from PropertyOS to your CRM, Slack or Zapier as signed JSON." actions={can('WEBHOOK:CREATE') && <Button variant="primary" icon={<Plus size={14} />} onClick={() => setEdit('new')}>Add endpoint</Button>} />
    <Tabs tabs={[{ key: 'endpoints', label: 'Endpoints', count: endpoints?.length }, { key: 'log', label: 'Delivery log', count: log.total }]} value={tab} onChange={setTab} className="mb-4" />
    {tab === 'endpoints' ? (<div className="space-y-6">
      <Panel padded={false}>
        <DataTable rows={endpoints ?? []} columns={cols} rowKey={(e) => e.id} loading={!endpoints}
          empty={{ title: 'No endpoints yet', body: can('WEBHOOK:CREATE') ? 'Add an endpoint to send events such as “Lease signed” to another system.' : undefined }} />
      </Panel>
      <Panel title="How to verify an event" description="Every delivery is a POST with a JSON body and these headers, so you can be sure it came from PropertyOS and was not changed.">
        <ul className="mb-4 space-y-1 text-sm text-graphite-800">
          <li><code>X-PropertyOS-Event</code> and <code>X-PropertyOS-Event-Id</code>: the event and its id. The id is the same on every retry, so you can ignore one you have already handled.</li>
          <li><code>X-PropertyOS-Timestamp</code>: when it was sent, in seconds.</li>
          <li><code>X-PropertyOS-Signature</code>: <code>sha256=</code> and the HMAC-SHA256, using your signing secret, of the timestamp, a full stop, then the exact body.</li>
          <li>Answer with any 2xx within a few seconds. If you don't, PropertyOS tries again later (after 1 minute, 5 minutes, 30 minutes, 2 hours, then 6 hours) and records each try in the delivery log.</li>
        </ul>
        <pre className="overflow-x-auto rounded bg-graphite-950 p-3 text-xs leading-relaxed text-limestone-100" aria-label="Verification example">{sample}</pre>
      </Panel>
    </div>) : (<>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <Field label="Endpoint" className="w-56"><Select value={endpointFilter} onChange={(e) => setEndpointFilter(e.target.value)}><option value="">All endpoints</option>{(endpoints ?? []).map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}</Select></Field>
        <Field label="Status" className="w-48"><Select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}><option value="">All</option><option value="SUCCEEDED">Delivered</option><option value="PENDING">Waiting to retry</option><option value="FAILED">Failed</option></Select></Field>
        <span className="pb-2 text-xs text-graphite-500">This list refreshes by itself.</span>
      </div>
      <Panel padded={false}>
        <DataTable rows={log.rows} server={log.server} columns={logCols} rowKey={(d) => d.id} loading={log.isLoading} onRowClick={(d) => setOpen(d.id)}
          empty={{ title: 'No deliveries yet', body: 'Deliveries appear here as events happen, or when you send a test event.' }} />
      </Panel>
    </>)}
    {edit && <Editor ep={edit === 'new' ? undefined : edit} events={events ?? []} onClose={() => setEdit(null)} onSecret={setSecret} />}
    {secret && <SecretDialog ep={secret} onClose={() => setSecret(null)} />}
    {testing && <TestDialog ep={testing} onClose={() => setTesting(null)} onLog={() => { setEndpointFilter(testing.id); setStatusFilter(''); setTesting(null); setTab('log'); }} />}
    {deleting && <ConfirmDialog open title={`Delete ${deleting.name}?`} body="The endpoint stops receiving events and its delivery log is deleted." confirmLabel="Delete endpoint" tone="danger" loading={remove.isPending} onClose={() => setDeleting(null)} onConfirm={() => remove.mutate(deleting)} />}
    {open && <DeliveryDrawer id={open} onClose={() => setOpen(null)} />}
  </>);
}
