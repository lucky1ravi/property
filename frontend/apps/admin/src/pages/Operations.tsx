import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { PageHeader, Panel, DataTable, type Column, useApi, usePagedApi, useAction, api, fmt, Button, Badge, Stat, StatStrip, StatusPill, Select, Modal, Field, Textarea, KeyValues } from '@propertyos/ui';

/** The operator's own pages: is everything running, who is really using the product, and what messages went out. */

const NICE: Record<string, string> = {
  IDENTITY: 'Sign-in and companies', LEASING: 'Leasing', BILLING: 'Payments', MAINTENANCE: 'Maintenance', WORKFLOW: 'Notifications and automation',
  AI: 'AI assistant', DOCUMENTS: 'Documents and e-signatures', DATABASE: 'Database',
};

export function ServiceHealth() {
  const { data, isFetching, refetch, isLoading } = useApi<any>('/api/identity/platform/health', { refetchInterval: 30_000, staleTime: 0 });
  const services: any[] = data?.services ?? [];
  const cols: Column<any>[] = [
    { key: 'service', header: 'Part of PropertyOS', render: (s) => <span className="font-semibold">{NICE[s.service] ?? s.service}</span> },
    { key: 'status', header: 'Status', render: (s) => <StatusPill status={s.status === 'UP' ? 'ACTIVE' : 'SUSPENDED'} label={s.status === 'UP' ? 'Running' : 'Down'} /> },
    { key: 'millis', header: 'Answered in', align: 'right', value: (s) => Number(s.millis), render: (s) => `${s.millis} ms` },
    { key: 'detail', header: 'What was said', hideOnMobile: true, render: (s) => s.detail ?? '' },
  ];
  return (<>
    <PageHeader title="Service health" description="Whether each part of PropertyOS is running and how quickly it answers. Checked again every 30 seconds."
      actions={<Button variant="secondary" icon={<RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} />} onClick={() => refetch()}>Check now</Button>} />
    {data && <StatStrip className="mb-6">
      <Stat label="Overall" value={data.overall === 'UP' ? 'All running' : data.overall === 'DEGRADED' ? 'Partly down' : 'Down'} emphasis sub={`Checked ${fmt.relative(data.checkedAt)}`} />
      <Stat label="Running" value={`${data.up} of ${data.total}`} />
      <Stat label="Slowest answer" value={`${Math.max(0, ...services.map((s) => Number(s.millis)))} ms`} />
    </StatStrip>}
    <Panel padded={false}><DataTable rows={services} columns={cols} rowKey={(s) => s.service} loading={isLoading} searchable={false} /></Panel>
  </>);
}

const ENGAGEMENT: Record<string, { label: string; tone: 'good' | 'warn' | 'crit' | 'neutral'; hint: string }> = {
  ACTIVE: { label: 'Active', tone: 'good', hint: 'Somebody signed in this week' },
  SLOWING: { label: 'Slowing', tone: 'warn', hint: 'Last sign-in was 1 to 4 weeks ago' },
  QUIET: { label: 'Quiet', tone: 'crit', hint: 'Nobody has signed in for over a month' },
  NEVER: { label: 'Not started', tone: 'neutral', hint: 'Nobody has signed in yet' },
};

export function CompanyUsage() {
  const [engagement, setEngagement] = useState('');
  const list = usePagedApi<any>(`/api/identity/platform/usage${engagement ? `?f.engagement=${engagement}` : ''}`);
  const all = useApi<any>('/api/identity/platform/summary');
  const quiet = useApi<any>('/api/identity/platform/usage?f.engagement=QUIET&page=1&size=1');
  const never = useApi<any>('/api/identity/platform/usage?f.engagement=NEVER&page=1&size=1');
  const count = (d: any) => (typeof d?.total === 'number' ? d.total : (d?.rows ?? d ?? []).length);
  const cols: Column<any>[] = [
    { key: 'name', header: 'Company', render: (r) => <><span className="font-semibold">{r.name}</span><span className="block text-xs text-graphite-600">{r.plan} · {r.state}{r.status !== 'ACTIVE' ? ` · ${String(r.status).toLowerCase()}` : ''}</span></> },
    { key: 'engagement', header: 'Use', render: (r) => { const e = ENGAGEMENT[r.engagement] ?? ENGAGEMENT.NEVER; return <span title={e.hint}><Badge tone={e.tone}>{e.label}</Badge></span>; } },
    { key: 'users', header: 'People', align: 'right', value: (r) => Number(r.users), render: (r) => r.users },
    { key: 'active_7d', header: 'Active, 7 days', align: 'right', hideOnMobile: true, value: (r) => Number(r.active_7d), render: (r) => r.active_7d },
    { key: 'active_30d', header: 'Active, 30 days', align: 'right', hideOnMobile: true, value: (r) => Number(r.active_30d), render: (r) => r.active_30d },
    { key: 'actions_30d', header: 'Actions, 30 days', align: 'right', hideOnMobile: true, value: (r) => Number(r.actions_30d), render: (r) => r.actions_30d },
    { key: 'last_sign_in', header: 'Last sign-in', value: (r) => r.last_sign_in ?? '', render: (r) => (r.last_sign_in ? fmt.relative(r.last_sign_in) : 'Never') },
    { key: 'created_at', header: 'Joined', hideOnMobile: true, value: (r) => r.created_at, render: (r) => fmt.date(r.created_at) },
  ];
  return (<>
    <PageHeader title="Company usage" description="Who is really using PropertyOS. Quiet companies are the ones to call before they leave." />
    <StatStrip className="mb-6">
      <Stat label="Companies" value={all.data?.organizations ?? '–'} emphasis />
      <Stat label="Active, 7 days" value={all.data?.activeUsers7d ?? '–'} sub="people" />
      <Stat label="Gone quiet" value={count(quiet.data)} sub="no sign-in for a month" />
      <Stat label="Not started" value={count(never.data)} sub="nobody has signed in" />
    </StatStrip>
    <Panel padded={false}>
      <DataTable rows={list.rows} server={list.server} columns={cols} rowKey={(r) => r.id} loading={list.isLoading}
        toolbar={<Select aria-label="Filter by use" value={engagement} onChange={(e) => setEngagement(e.target.value)}><option value="">All companies</option>
          {Object.entries(ENGAGEMENT).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}</Select>} />
    </Panel>
  </>);
}

const DELIVERY_TONE: Record<string, 'good' | 'warn' | 'neutral'> = { SENT: 'good', QUEUED: 'warn', SKIPPED: 'neutral' };

export function DeliveryLog() {
  const [status, setStatus] = useState('');
  const list = usePagedApi<any>(`/api/workflow/platform/deliveries${status ? `?f.status=${status}` : ''}`, { initialSort: { key: 'created_at', dir: 'desc' } });
  const { data: s } = useApi<any>('/api/workflow/platform/deliveries/summary');
  // Seeing one message in full is allowed for support work, with a reason, and is recorded where the company can see it.
  const [asking, setAsking] = useState<any | null>(null);
  const [reason, setReason] = useState('');
  const [shown, setShown] = useState<any | null>(null);
  const reveal = useAction(() => api.post<any>(`/api/workflow/platform/deliveries/${asking.id}/reveal`, { reason }), { silent: true, onSuccess: (d) => { setShown(d); setAsking(null); setReason(''); } });
  const channel = (c: string) => Number((s?.byChannel ?? []).find((x: any) => x.channel === c)?.count ?? 0);
  const cols: Column<any>[] = [
    { key: 'created_at', header: 'When', value: (r) => r.created_at, render: (r) => fmt.dateTime(r.created_at) },
    { key: 'org_name', header: 'Company', render: (r) => <span className="font-semibold">{r.org_name}</span> },
    { key: 'channel', header: 'Channel', render: (r) => <Badge>{r.channel === 'SMS' ? 'Text message' : 'Email'}</Badge> },
    { key: 'recipient', header: 'To (masked)' },
    { key: 'status', header: 'Outcome', render: (r) => <span title={r.note ?? ''}><Badge tone={DELIVERY_TONE[r.status] ?? 'neutral'}>{r.status === 'SENT' ? 'Sent' : r.status === 'QUEUED' ? 'Held' : 'Skipped'}</Badge></span> },
    { key: '__details', header: '', render: (r) => <Button size="sm" variant="secondary" aria-label={`Show details of the message to ${r.recipient}`} onClick={() => setAsking(r)}>Details</Button> },
  ];
  return (<>
    <PageHeader title="Delivery log" description="Every email and text PropertyOS sent, for every company, and whether it went. Recipients are masked and message text is never shown. For support work, Details shows the whole recipient and subject once you give a reason, which the company can see in its audit log. A demo environment sends nothing." />
    <StatStrip className="mb-6">
      <Stat label="Sent, 7 days" value={s?.last7d ?? '–'} emphasis />
      <Stat label="Emails" value={channel('EMAIL')} />
      <Stat label="Text messages" value={channel('SMS')} />
      <Stat label="Held for quiet hours" value={s?.held ?? '–'} sub="waiting to go" />
    </StatStrip>
    <Panel padded={false}>
      <DataTable rows={list.rows} server={list.server} columns={cols} rowKey={(r) => r.id} loading={list.isLoading} initialSort={{ key: 'created_at', dir: 'desc' }}
        toolbar={<Select aria-label="Filter by outcome" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All outcomes</option><option value="SENT">Sent</option><option value="QUEUED">Held</option><option value="SKIPPED">Skipped</option></Select>} />
    </Panel>
    <Modal open={!!asking} onClose={() => { setAsking(null); setReason(''); }} title="Why do you need to see this?"
      footer={<><Button variant="quiet" onClick={() => { setAsking(null); setReason(''); }}>Cancel</Button><Button variant="primary" loading={reveal.isPending} disabled={reason.trim().length < 10} onClick={() => reveal.mutate(undefined)}>Show details</Button></>}>
      <div className="space-y-3">
        <p className="text-sm text-graphite-700">This shows the whole recipient and the subject of one message to {asking?.org_name}. It does not show the message text. <b>Your name, the time and your reason are recorded in that company’s audit log.</b></p>
        <Field label="Reason" required hint="For example: support request about a missing payment reminder. At least 10 characters."><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} /></Field>
      </div>
    </Modal>
    <Modal open={!!shown} onClose={() => setShown(null)} title="Message details" footer={<Button variant="primary" onClick={() => setShown(null)}>Close</Button>}>
      {shown && <><KeyValues cols={1} items={[{ label: 'Company', value: shown.org_name }, { label: 'Channel', value: shown.channel === 'SMS' ? 'Text message' : 'Email' }, { label: 'To', value: shown.recipient },
        { label: 'Subject', value: shown.subject ?? 'No subject (text message)' }, { label: 'Outcome', value: `${fmt.title(shown.status)}${shown.note ? `: ${shown.note}` : ''}` }, { label: 'Sent', value: fmt.dateTime(shown.created_at) }]} />
        <p className="mt-3 rounded bg-limestone-100 p-3 text-xs text-graphite-700">The message text is not shown. This view was recorded in the company’s audit log.</p></>}
    </Modal>
  </>);
}
