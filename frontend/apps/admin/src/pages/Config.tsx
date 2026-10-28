import { useState } from 'react';
import { PageHeader, Panel, Button, Field, Input, Toggle, Skeleton, Tabs, Timeline, useApi, useAction, api, fmt, useSession, ConfirmDialog, Textarea } from '@propertyos/ui';

const inv = ['/api/identity/config'];
/** Friendly names for known settings; anything else is humanised from its key. */
const LABELS: Record<string, [string, string?]> = {
  LEASING: ['Leasing', 'Listings, enquiries, showings, applications and leases'],
  PAYMENTS: ['Rent and payments', 'Invoices, online payments and collections'],
  MAINTENANCE: ['Maintenance', 'Repair requests, work orders and vendors'],
  AUTOMATIONS: ['Workflow automations', 'Rules that move routine work forward on their own'],
  AI_ASSISTANT: ['AI assistant', 'Chat assistant for staff and residents'],
  BROKER_COCKPIT: ['Broker cockpit', 'Work queue and portfolio view for brokers'],
  OPPORTUNITIES: ['Profit opportunities', 'AI-found ways to raise income or cut cost'],
  OWNER_PORTAL: ['Owner portal', 'Statements, approvals and performance for owners'],
  VENDOR_PORTAL: ['Vendor portal', 'Work orders and invoices for vendors'],
  RENEWALS: ['Lease renewals', 'Renewal offers and rent-increase suggestions'],
  RECURRING_MAINTENANCE: ['Recurring maintenance', 'Scheduled inspections and seasonal jobs'],
  SELF_GUIDED_SHOWINGS: ['Self-guided showings', 'Let prospects tour with a lockbox code'],
  AI_VOICE_AGENT: ['AI voice agent', 'Answer phone enquiries (not yet available)'],
  LISTING_WRITER: ['Listing writer', 'Drafts listing titles and descriptions'],
  RENT_SUGGESTION: ['Rent suggestion', 'Recommends asking rent from comparable units'],
  LISTING_INTEGRITY: ['Listing check', 'Checks listings for missing details and fair-housing wording'],
  SCREENING: ['Applicant screening', 'Scores applicants and explains the result'],
  LEASE_SUMMARY: ['Lease summary', 'Plain-language summary of lease terms'],
  PAYMENT_RISK: ['Payment risk', 'Flags tenants likely to pay late'],
  MAINTENANCE_TRIAGE: ['Maintenance triage', 'Sets trade, priority and cost estimate for each request'],
  ASSISTANT: ['Chat assistant', 'Answers questions and proposes actions'],
  LEASING_AGENT: ['AI leasing agent', 'Replies to enquiries and books tours'],
  enabled: ['Enabled'],
  autoPublishOnPass: ['Publish automatically when checks pass'],
  advisoryOnly: ['Advisory only (a person makes the decision)'],
  riskThreshold: ['Risk score that triggers a flag (0 to 100)'],
  autoDispatchMaxCost: ['Dispatch automatically up to ($)'],
  minConfidence: ['Minimum confidence (0 to 1)'],
  tone: ['Tone of voice'],
  email: ['Email', 'Receipts, reminders and notices'],
  sms: ['Text message', 'Time-sensitive reminders and alerts'],
  inApp: ['In-app', 'Notification bell inside PropertyOS'],
  quietHours: ['Quiet hours', 'No non-urgent messages are sent in this window (company time zone)'],
  start: ['Start'], end: ['End'],
  channels: ['Channels', 'Where PropertyOS may contact people'],
  lateFee: ['Late fee'], percent: ['Percent of rent (%)'], flat: ['Flat amount ($)'], type: ['Type'],
  applicationFee: ['Application fee ($)'], nsfFee: ['Returned payment fee ($)'], renewalFee: ['Renewal fee ($)'],
  managementFeePercent: ['Management fee (% of collected rent)'], leasingCommissionPercent: ['Leasing commission (% of first month)'],
  dueDay: ['Rent due day of month'], invoiceDaysBeforeDue: ['Invoice this many days before due'],
  reminderDaysBefore: ['Reminder days before due'], reminderDaysAfter: ['Reminder days after due'], allocation: ['Payment allocation order'],
  maintenanceOwnerLimit: ['Owner approval needed above ($)'], ownerApprovesApplications: ['Owner approves applications'], vendorInvoiceApprovalLimit: ['Vendor invoice approval limit ($)'],
  incomeMultiple: ['Required income multiple of rent'], minScore: ['Minimum screening score'], minMonthsEmployed: ['Minimum months employed'], requiredDocuments: ['Required documents'],
  ackHours: ['Acknowledge within (hours)'], resolveHours: ['Resolve within (hours)'], enquiryResponseMinutes: ['Reply to enquiries within (minutes)'],
  EMERGENCY: ['Emergency'], URGENT: ['Urgent'], ROUTINE: ['Routine'],
  offerDaysBeforeEnd: ['Offer renewal this many days before lease end'], maxIncreasePercent: ['Maximum rent increase (%)'],
  companyName: ['Company name'], primaryColor: ['Primary colour'], accentColor: ['Accent colour'], portalDomain: ['Resident portal domain'], emailSender: ['Email sender address'],
};
const human = (k: string) => {
  if (LABELS[k]) return LABELS[k][0];
  const words = k.includes('_') || k === k.toUpperCase() ? k.toLowerCase().split('_').join(' ') : k.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
  return words.replace(/^./, (c) => c.toUpperCase());
};
const hintFor = (k: string) => LABELS[k]?.[1];
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
function Node({ label, hint, value, onChange, depth = 0 }: { label: string; hint?: string; value: any; onChange: (v: any) => void; depth?: number }) {
  if (typeof value === 'boolean') return (
    <div className="flex items-start justify-between gap-4 rounded-md border border-limestone-200 bg-white px-4 py-3">
      <span className="min-w-0"><span className="block text-sm font-semibold text-graphite-900">{label}</span>{hint && <span className="mt-0.5 block text-xs text-graphite-500">{hint}</span>}</span>
      <Toggle checked={value} onChange={onChange} label={label} />
    </div>
  );
  if (typeof value === 'number') return <Field label={label} hint={hint}><Input type="number" step="any" value={value} onChange={(e) => onChange(e.target.value === '' ? 0 : Number(e.target.value))} /></Field>;
  if (typeof value === 'string') return <Field label={label} hint={hint}><Input value={value} onChange={(e) => onChange(e.target.value)} /></Field>;
  if (Array.isArray(value)) return <Field label={label} hint="One per line"><Textarea rows={Math.min(Math.max(value.length, 2), 6)} value={value.join('\n')} onChange={(e) => onChange(e.target.value.split('\n').map((x) => x.trim()).filter(Boolean).map((x) => (typeof value[0] === 'number' ? Number(x) : x)))} /></Field>;
  if (value && typeof value === 'object') {
    const leaves = Object.entries(value);
    return (
      <div className={depth ? 'rounded-md border border-limestone-200 p-4' : ''}>
        {depth > 0 && <div className="mb-3"><h3 className="text-sm font-bold normal-case tracking-normal text-graphite-900">{label}</h3>{hint && <p className="mt-0.5 text-xs normal-case tracking-normal text-graphite-500">{hint}</p>}</div>}
        <div className="grid gap-4 sm:grid-cols-2">
          {leaves.map(([k, v]) => (typeof v === 'object' && !Array.isArray(v) && v !== null
            ? <div key={k} className="sm:col-span-2"><Node label={human(k)} hint={hintFor(k)} value={v} depth={depth + 1} onChange={(nv) => onChange({ ...value, [k]: nv })} /></div>
            : <div key={k} className={Array.isArray(v) ? 'sm:col-span-2' : ''}><Node label={human(k)} hint={hintFor(k)} value={v} depth={depth + 1} onChange={(nv) => onChange({ ...value, [k]: nv })} /></div>))}
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
