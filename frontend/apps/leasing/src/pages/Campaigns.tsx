import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Copy, KeyRound, Pause, Pencil, Play, Plus, Send } from 'lucide-react';
import {
  PageHeader, Panel, DataTable, type Column, useApi, usePagedApi, useAction, api, fmt, Button, Badge, Field, Input, Select, Modal, Stat, StatStrip,
  TrendChart, CompareChart, Skeleton, NotFoundState, useSession, toast, apiBase,
} from '@propertyos/ui';
import { useBase } from '../common';

/**
 * Marketing campaigns. Each one has a tracking link for the public site and a signed inbound address for ad platforms and form tools, and shows what it
 * brought in. The signing secret is shown only when it is made or rotated; the sample lead is signed on the server, so the browser never needs it.
 */

const CHANNELS: Record<string, string> = { FACEBOOK_ADS: 'Facebook ads', GOOGLE_ADS: 'Google ads', ZILLOW: 'Zillow', APARTMENTS: 'Apartments.com', EMAIL: 'Email', OTHER: 'Other' };
const TONE: Record<string, 'good' | 'warn' | 'neutral' | 'brand'> = { ACTIVE: 'good', PAUSED: 'warn', ENDED: 'neutral', SCHEDULED: 'brand' };
const STATE: Record<string, string> = { ACTIVE: 'Running', PAUSED: 'Paused', ENDED: 'Ended', SCHEDULED: 'Starts later' };
const WORD = (s: string) => { const t = String(s ?? '').toLowerCase().replace(/_/g, ' '); return t.charAt(0).toUpperCase() + t.slice(1); };

const trackingLink = (c: any) => `${window.location.origin}/homes?cmp=${c.tracking_code}`;
// The inbound address is on the API, which is not always the same address as the web page (locally the page is on 4173 and the API on 8080).
const inboundUrl = (c: any) => `${apiBase()}/api/leasing/public/inbound/${c.public_key}`;
const pct = (v: any) => (v == null ? '–' : `${Number(v).toFixed(1)}%`);

async function copy(text: string, what: string) {
  try {
    await navigator.clipboard.writeText(text);
    toast(`${what} copied`, { tone: 'success' });
  } catch {
    toast('Could not copy. Select the text and copy it by hand.', { tone: 'warning' });
  }
}

function CopyRow({ label, value }: { label: string; value: string }) {
  return (<div>
    <div className="mb-1 text-xs font-semibold text-graphite-700">{label}</div>
    <div className="flex items-center gap-2"><code className="min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded bg-limestone-100 px-2 py-1.5 text-xs" data-testid={`copy-${label.toLowerCase().replace(/\W+/g, '-')}`}>{value}</code>
      <Button size="sm" variant="secondary" icon={<Copy size={13} />} aria-label={`Copy ${label.toLowerCase()}`} onClick={() => copy(value, label)}>Copy</Button></div>
  </div>);
}

function Code({ children, label }: { children: string; label: string }) {
  return (<div className="relative">
    <pre className="overflow-x-auto rounded bg-graphite-950 p-3 pr-16 text-xs leading-relaxed text-limestone-100" aria-label={label}>{children}</pre>
    <Button size="sm" variant="secondary" className="absolute right-2 top-2" aria-label={`Copy ${label.toLowerCase()}`} onClick={() => copy(children, label)}>Copy</Button>
  </div>);
}

/** The secret, shown once. */
function SecretDialog({ c, onClose }: { c: any; onClose: () => void }) {
  return (<Modal open onClose={onClose} title={`Keys for ${c.name}`} footer={<Button variant="primary" onClick={onClose}>I have saved it</Button>}>
    <div className="space-y-4">
      <div className="rounded border border-warn-300 bg-warn-50 p-3 text-sm text-graphite-800" role="note">Copy the secret now. For your security it is not shown again. If you lose it, rotate it to make a new one.</div>
      <CopyRow label="Signing secret" value={c.secret} />
      <CopyRow label="Inbound address" value={inboundUrl(c)} />
    </div>
  </Modal>);
}

function Editor({ c, onClose, onSecret }: { c?: any; onClose: () => void; onSecret: (c: any) => void }) {
  const [f, setF] = useState<any>({ name: c?.name ?? '', channel: c?.channel ?? 'FACEBOOK_ADS', spend: c ? String(c.spend ?? 0) : '', startsOn: c?.starts_on ?? '', endsOn: c?.ends_on ?? '' });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const body = () => ({ name: f.name, channel: f.channel, spend: f.spend === '' ? 0 : Number(f.spend), startsOn: f.startsOn || null, endsOn: f.endsOn || null });
  const save = useAction(() => (c ? api.put<any>(`/api/leasing/campaigns/${c.id}`, body()) : api.post<any>('/api/leasing/campaigns', body())), {
    success: c ? 'Campaign saved' : 'Campaign created', invalidate: ['/api/leasing/campaigns'], onSuccess: (r: any) => { onClose(); if (!c && r?.secret) onSecret(r); },
  });
  return (<Modal open onClose={onClose} title={c ? 'Edit campaign' : 'New campaign'} footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!f.name.trim()} onClick={() => save.mutate(undefined)}>{c ? 'Save' : 'Create campaign'}</Button></>}>
    <div className="space-y-3">
      <Field label="Name" required><Input value={f.name} maxLength={160} onChange={set('name')} placeholder="Spring move-in special" /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Channel" required><Select value={f.channel} onChange={set('channel')}>{Object.entries(CHANNELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></Field>
        <Field label="Spend so far ($)" hint="Used for cost per lead."><Input type="number" min={0} step="0.01" value={f.spend} onChange={set('spend')} /></Field>
        <Field label="Starts"><Input type="date" value={f.startsOn} onChange={set('startsOn')} /></Field>
        <Field label="Ends"><Input type="date" value={f.endsOn} onChange={set('endsOn')} /></Field>
      </div>
    </div>
  </Modal>);
}

// ---------------------------------------------------------------------------------------------------------------- the list

export function Campaigns() {
  const { can } = useSession();
  const nav = useNavigate(); const base = useBase();
  const { rows, isLoading, server } = usePagedApi<any>('/api/leasing/campaigns');
  const { data: series } = useApi<any>('/api/leasing/campaigns/series?days=56&bucket=week');
  const [edit, setEdit] = useState<any | 'new' | null>(null);
  const [secret, setSecret] = useState<any>(null);
  const flip = useAction((c: any) => api.post(`/api/leasing/campaigns/${c.id}/${c.status === 'PAUSED' ? 'resume' : 'pause'}`, {}), { success: 'Saved', invalidate: ['/api/leasing/campaigns'] });
  const stop = (fn: () => void) => (e: React.MouseEvent) => { e.stopPropagation(); fn(); };
  const cols: Column<any>[] = [
    { key: 'name', header: 'Campaign', render: (c) => (<div><div className="font-semibold">{c.name}</div><div className="text-xs text-graphite-500">{CHANNELS[c.channel] ?? c.channel}</div></div>) },
    { key: 'state', header: 'State', render: (c) => <Badge tone={TONE[c.state] ?? 'neutral'}>{STATE[c.state] ?? WORD(c.state)}</Badge> },
    { key: 'spend', header: 'Spend', align: 'right', value: (c) => Number(c.spend), render: (c) => fmt.usd(c.spend, true) },
    { key: 'leads', header: 'Leads', align: 'right', value: (c) => Number(c.leads) },
    { key: 'applications', header: 'Applications', align: 'right', hideOnMobile: true, value: (c) => Number(c.applications) },
    { key: 'leases', header: 'Leases', align: 'right', hideOnMobile: true, value: (c) => Number(c.leases) },
    { key: 'cost_per_lead', header: 'Cost per lead', align: 'right', hideOnMobile: true, value: (c) => Number(c.cost_per_lead ?? 0), render: (c) => (c.cost_per_lead == null ? '–' : fmt.usd(c.cost_per_lead, true)) },
    { key: 'conversion', header: 'Conversion', align: 'right', hideOnMobile: true, value: (c) => Number(c.conversion ?? 0), render: (c) => pct(c.conversion) },
    { key: 'actions', header: '', sortable: false, render: (c) => (can('CAMPAIGN:EDIT') ? <div className="flex justify-end gap-1">
      <Button size="sm" variant="quiet" aria-label={`Edit ${c.name}`} onClick={stop(() => setEdit(c))}>Edit</Button>
      <Button size="sm" variant="quiet" icon={c.status === 'PAUSED' ? <Play size={13} /> : <Pause size={13} />} aria-label={`${c.status === 'PAUSED' ? 'Resume' : 'Pause'} ${c.name}`} onClick={stop(() => flip.mutate(c))}>{c.status === 'PAUSED' ? 'Resume' : 'Pause'}</Button></div> : null) },
  ];
  const hasChart = (series?.keys ?? []).length > 0;
  return (<>
    <PageHeader title="Campaigns" description="See which marketing brings in leads, applications and leases. Open a campaign for its links and a way to test it." actions={can('CAMPAIGN:CREATE') && <Button variant="primary" icon={<Plus size={14} />} onClick={() => setEdit('new')}>New campaign</Button>} />
    {hasChart && <Panel title="Leads over time" description="New leads each week, for your busiest campaigns." className="mb-6">
      <div aria-label="Chart of new leads each week for each campaign"><CompareChart data={series.rows} x="label" format="num" height={220} bars={series.keys.map((k: any) => ({ key: k.key, label: k.name }))} /></div>
    </Panel>}
    <Panel padded={false}>
      <DataTable rows={rows} server={server} columns={cols} rowKey={(c) => c.id} loading={isLoading} exportName="campaigns" onRowClick={(c) => nav(`${base}/campaigns/${c.id}`)}
        empty={{ title: 'No campaigns yet', body: can('CAMPAIGN:CREATE') ? 'Make a campaign for each ad or email so you can see what it brings in.' : undefined }} />
    </Panel>
    {edit && <Editor c={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} onSecret={setSecret} />}
    {secret && <SecretDialog c={secret} onClose={() => setSecret(null)} />}
  </>);
}

// ---------------------------------------------------------------------------------------------------------------- one campaign

const SAMPLE_BODY = '{"type":"lead","name":"Maya Patel","email":"maya.patel@example.com","phone":"5125550142","listingId":"<a live listing id>","message":"Is this home still available? I would love to tour it."}';

export function CampaignDetail() {
  const { id } = useParams();
  const base = useBase();
  const { can } = useSession();
  const { data: c, isLoading, error } = useApi<any>(`/api/leasing/campaigns/${id}`, { retry: false, refetchInterval: 4000 });
  const { data: series } = useApi<any>(`/api/leasing/campaigns/series?campaignId=${id}&days=30&bucket=day`, { refetchInterval: 8000 });
  const [editing, setEditing] = useState(false);
  const [secret, setSecret] = useState<any>(null);
  const flip = useAction(() => api.post(`/api/leasing/campaigns/${id}/${c.status === 'PAUSED' ? 'resume' : 'pause'}`, {}), { success: 'Saved', invalidate: ['/api/leasing/campaigns'] });
  const rotate = useAction((newAddress: boolean) => api.post<any>(`/api/leasing/campaigns/${id}/rotate-key`, { newAddress }), {
    success: 'New secret made. The old one has stopped working.', invalidate: ['/api/leasing/campaigns'], onSuccess: (r: any) => setSecret(r),
  });
  const test = useAction(() => api.post<any>(`/api/leasing/campaigns/${id}/test-lead`, {}), {
    success: (r: any) => `Test lead sent: ${r.name} asked about ${r.listing_title}. Check Leads and your notifications.`,
    invalidate: ['/api/leasing/campaigns', '/api/leasing/leads'],
  });
  if (error) return <NotFoundState what="campaign" back={<Link to={`${base}/campaigns`}><Button>Back to campaigns</Button></Link>} />;
  if (isLoading || !c) return <Skeleton className="h-96" />;
  const url = inboundUrl(c);
  const curl = `BODY='${SAMPLE_BODY}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "YOUR_SECRET" | sed 's/^.* //')
curl -X POST '${url}' \\
  -H 'Content-Type: application/json' \\
  -H "X-Signature: sha256=$SIG" \\
  -d "$BODY"`;
  const node = `import { createHmac } from 'node:crypto';

const body = JSON.stringify({ type: 'lead', name: 'Maya Patel', email: 'maya.patel@example.com', listingId: '<a live listing id>' });
const signature = createHmac('sha256', process.env.CAMPAIGN_SECRET).update(body).digest('hex');

await fetch('${url}', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Signature': \`sha256=\${signature}\` },
  body,
});`;
  const cols: Column<any>[] = [
    { key: 'created_at', header: 'When', value: (a) => a.created_at, render: (a) => fmt.dateTime(a.created_at) },
    { key: 'kind', header: 'Type', render: (a) => <Badge tone={a.kind === 'APPLICATION' ? 'brand' : 'neutral'}>{a.kind === 'APPLICATION' ? 'Application' : 'Lead'}</Badge> },
    { key: 'name', header: 'Person', render: (a) => (<div><div className="font-semibold">{a.name}</div><div className="text-xs text-graphite-500">{a.email}</div></div>) },
    { key: 'listing_title', header: 'Home', hideOnMobile: true },
    { key: 'status', header: 'Status', render: (a) => WORD(a.status) },
  ];
  return (<>
    <PageHeader title={c.name} back={<Link to={`${base}/campaigns`} className="text-sm font-semibold text-federal-900">All campaigns</Link>}
      meta={<><Badge tone={TONE[c.state] ?? 'neutral'}>{STATE[c.state] ?? WORD(c.state)}</Badge><span>{CHANNELS[c.channel] ?? c.channel}</span>{(c.starts_on || c.ends_on) && <span>{c.starts_on ? fmt.date(c.starts_on) : 'Any time'} to {c.ends_on ? fmt.date(c.ends_on) : 'open'}</span>}</>}
      actions={can('CAMPAIGN:EDIT') && <>
        <Button icon={<Pencil size={14} />} onClick={() => setEditing(true)}>Edit</Button>
        <Button icon={c.status === 'PAUSED' ? <Play size={14} /> : <Pause size={14} />} loading={flip.isPending} onClick={() => flip.mutate(undefined)}>{c.status === 'PAUSED' ? 'Resume' : 'Pause'}</Button>
        <Button variant="primary" icon={<Send size={14} />} loading={test.isPending} disabled={c.state !== 'ACTIVE'} title={c.state === 'ACTIVE' ? undefined : 'Resume the campaign to send a test lead'} onClick={() => test.mutate(undefined)}>Send test lead</Button></>} />
    <StatStrip className="mb-6">
      <Stat label="Spend" value={fmt.usd(c.spend, true)} emphasis />
      <Stat label="Leads" value={c.leads} />
      <Stat label="Applications" value={c.applications} />
      <Stat label="Leases" value={c.leases} sub="Signed by the tenant" />
      <Stat label="Cost per lead" value={c.cost_per_lead == null ? '–' : fmt.usd(c.cost_per_lead, true)} />
      <Stat label="Conversion" value={pct(c.conversion)} sub="Leases out of people" />
    </StatStrip>
    <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-[1fr_380px]">
      <div className="space-y-6">
        <Panel title="Leads over time" description="New leads each day, last 30 days.">
          <div aria-label="Chart of new leads each day"><TrendChart data={series?.rows ?? []} x="label" y="total" format="num" height={200} /></div>
        </Panel>
        <Panel title="Recent activity" description="The newest leads and applications from this campaign. This list refreshes by itself." padded={false}>
          <DataTable rows={c.activity ?? []} columns={cols} rowKey={(a) => `${a.kind}-${a.id}`} empty={{ title: 'Nothing yet', body: 'Leads and applications appear here as they come in. Try Send test lead.' }} />
        </Panel>
      </div>
      <div className="space-y-6">
        <Panel title="Tracking link" description="For ads and emails that send people to your public homes site.">
          <CopyRow label="Tracking link" value={trackingLink(c)} />
          <p className="mt-2 text-xs text-graphite-600">Anyone who arrives through this link and then enquires or applies is counted for this campaign. To point at one home, add <code>?cmp={c.tracking_code}</code> to that home's address.</p>
        </Panel>
        <Panel title="Inbound address" description="For ad platforms and form tools that post leads for you.">
          <CopyRow label="Inbound address" value={url} />
          {can('CAMPAIGN:EDIT') && <div className="mt-3 flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" icon={<KeyRound size={13} />} loading={rotate.isPending} onClick={() => rotate.mutate(false)}>Rotate secret</Button>
            <Button size="sm" variant="secondary" loading={rotate.isPending} onClick={() => rotate.mutate(true)}>Rotate secret and address</Button></div>}
        </Panel>
      </div>
    </div>
    <Panel title="How to sign requests" description="Every post to the inbound address must be signed with this campaign's secret, so only you can send leads to it.">
      <ol className="mb-4 list-decimal space-y-1 pl-5 text-sm text-graphite-800">
        <li>Build the JSON body. Use the exact same bytes for the signature and the request.</li>
        <li>Compute the HMAC-SHA256 of the body with the campaign secret, as lower-case hex.</li>
        <li>Send it in the <code>X-Signature</code> header, optionally written <code>sha256=&lt;hex&gt;</code>.</li>
      </ol>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div><div className="mb-1 text-xs font-semibold text-graphite-700">Example with curl</div><Code label="curl example">{curl}</Code></div>
        <div><div className="mb-1 text-xs font-semibold text-graphite-700">Example with Node</div><Code label="Node example">{node}</Code></div>
      </div>
      <div className="mt-5 grid grid-cols-1 gap-6 md:grid-cols-2">
        <div>
          <h3 className="mb-2 text-sm font-bold text-graphite-900">Fields</h3>
          <ul className="space-y-1 text-sm text-graphite-800">
            <li><code>type</code>: lead or application. The default is lead.</li>
            <li><code>name</code> and <code>email</code>: required.</li>
            <li><code>listingId</code> or <code>unitId</code>: required. It must be a live home of your company.</li>
            <li><code>phone</code> and <code>message</code>: optional.</li>
            <li>An application also needs <code>consent: true</code> (the applicant agreed to a credit and background check). It can include <code>monthlyIncome</code>, <code>employer</code>, <code>moveInDate</code> and more.</li>
          </ul>
        </div>
        <div>
          <h3 className="mb-2 text-sm font-bold text-graphite-900">What you get back</h3>
          <ul className="space-y-1 text-sm text-graphite-800">
            <li><strong>200</strong>: created. <code>duplicate: true</code> means that person had already asked about that home, so nothing new was added.</li>
            <li><strong>401</strong>: the signature is missing or does not match.</li>
            <li><strong>404</strong>: unknown address, or the home is not one of yours.</li>
            <li><strong>409</strong>: the campaign is paused, has not started or has ended.</li>
            <li><strong>429</strong>: too many requests in a minute. Wait and try again.</li>
          </ul>
        </div>
      </div>
    </Panel>
    {editing && <Editor c={c} onClose={() => setEditing(false)} onSecret={setSecret} />}
    {secret && <SecretDialog c={secret} onClose={() => setSecret(null)} />}
  </>);
}
