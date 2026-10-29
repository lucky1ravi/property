import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BellRing, CalendarClock } from 'lucide-react';
import { PageHeader, Panel, Button, Badge, Stat, StatStrip, Tabs, EmptyState, Skeleton, useApi, useAction, api, fmt, useSession } from '@propertyos/ui';

const TYPE_LABEL: Record<string, string> = { LEASE_EXPIRY: 'Lease expiry', DEPOSIT_REFUND: 'Deposit refund', VENDOR_INSURANCE: 'Vendor insurance', TAX_FILING: 'Sales tax', FORM_1099: '1099 reporting', W9_MISSING: 'W-9 missing', DOCUMENT_EXPIRY: 'Document expiry', SIGNATURE_DUE: 'Signature due', SIGNATURE_EXPIRING: 'Signature expiring' };
const SOURCES = ['/api/leasing/deadlines', '/api/billing/deadlines', '/api/maintenance/deadlines', '/api/documents/deadlines'];

/** Everything with a date that carries a legal or financial consequence, in one list. */
export function Deadlines() {
  const { can } = useSession();
  const nav = useNavigate();
  const a = useApi<any[]>(SOURCES[0]); const b = useApi<any[]>(SOURCES[1]); const c = useApi<any[]>(SOURCES[2]); const e = useApi<any[]>(SOURCES[3]);
  const [tab, setTab] = useState<'all' | 'soon' | 'overdue'>('all');
  const loading = a.isLoading || b.isLoading || c.isLoading || e.isLoading;
  const all = useMemo(() => [...(a.data ?? []), ...(b.data ?? []), ...(c.data ?? []), ...(e.data ?? [])].sort((x, y) => (x.days_left ?? -99999) - (y.days_left ?? -99999)), [a.data, b.data, c.data, e.data]);
  const overdue = all.filter((d) => d.days_left != null && d.days_left < 0);
  const soon = all.filter((d) => d.days_left != null && d.days_left >= 0 && d.days_left <= 30);
  const rows = tab === 'overdue' ? overdue : tab === 'soon' ? soon : all;
  const run = useAction(async () => { let n = 0; for (const s of SOURCES) { try { const r = await api.post<any>(s.replace('/deadlines', '/alerts/run')); n += Number(r?.sent ?? 0); } catch { /* a service may be unavailable */ } } return n; },
    { success: (n) => (n ? `${n} alert${n === 1 ? '' : 's'} sent` : 'Nothing new to send. Every alert due so far has already gone out.') });
  const tone = (d: any) => (d.severity === 'OVERDUE' ? 'crit' : d.severity === 'HIGH' ? 'warn' : 'neutral');
  return (<>
    <PageHeader title="Deadlines and alerts" description="Lease expiry, deposit refund deadlines, vendor insurance, tax filing and 1099 dates. People are alerted automatically at the days set in Configuration."
      actions={can('ALERT:CONFIGURE') && <Button variant="secondary" loading={run.isPending} onClick={() => run.mutate(undefined)}><BellRing className="mr-1.5 h-4 w-4" />Send due alerts now</Button>} />
    <StatStrip className="mb-6"><Stat label="Overdue" value={overdue.length} emphasis /><Stat label="Next 30 days" value={soon.length} /><Stat label="Tracked" value={all.length} /></StatStrip>
    <Tabs tabs={[{ key: 'all', label: 'All', count: all.length }, { key: 'soon', label: 'Next 30 days', count: soon.length }, { key: 'overdue', label: 'Overdue', count: overdue.length }]} value={tab} onChange={setTab} className="mb-4" />
    {loading ? <Skeleton className="h-48" /> : rows.length === 0
      ? <EmptyState icon={<CalendarClock className="h-8 w-8" />} title="Nothing here" body="No deadlines match this view." />
      : <Panel padded={false}><ul className="divide-y divide-limestone-200">{rows.map((d, i) => (
        <li key={`${d.type}-${d.entity_id}-${i}`}><button onClick={() => d.link && nav('/app' + d.link)} className="flex w-full items-start justify-between gap-4 px-4 py-3 text-left hover:bg-limestone-50">
          <span className="min-w-0"><span className="flex flex-wrap items-center gap-2"><span className="text-sm font-semibold text-graphite-900">{d.title}</span><Badge>{TYPE_LABEL[d.type] ?? d.type}</Badge></span>
            <span className="mt-0.5 block text-xs text-graphite-600">{d.detail}</span></span>
          <span className="shrink-0 text-right"><Badge tone={tone(d) as any}>{d.days_left == null ? 'Action needed' : d.days_left < 0 ? `${-d.days_left} days overdue` : d.days_left === 0 ? 'Today' : `In ${d.days_left} days`}</Badge>
            {d.due_date && <span className="mt-1 block text-2xs text-graphite-500">{fmt.date(d.due_date)}</span>}</span>
        </button></li>))}</ul></Panel>}
  </>);
}
