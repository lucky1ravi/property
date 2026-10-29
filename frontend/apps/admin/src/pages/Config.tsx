import { useEffect, useState } from 'react';
import { PageHeader, Panel, Button, Field, Input, Toggle, Skeleton, Tabs, Timeline, useApi, useAction, api, fmt, useSession, ConfirmDialog, Textarea } from '@propertyos/ui';

const inv = ['/api/identity/config'];
/** Friendly names for known settings; anything else is humanised from its key. */
const LABELS: Record<string, [string, string?]> = {
  LEASE_ABSTRACT: ['Lease summary', 'Pulls key dates, amounts and obligations out of a lease'], ANOMALY_SCAN: ['Billing check', 'Flags duplicate invoices, rent that doesn’t match the lease and doubled expenses'],
  STATEMENT_NARRATIVE: ['Owner statement summary', 'Writes a plain-English summary for each owner statement'], RESIDENT_OUTLOOK: ['Resident outlook', 'Shows who is most likely to pay late or move out, and why'],
  PHOTO_TRIAGE: ['Repair photo check', 'Reads a photo of a problem and suggests type, urgency and cost'], FAIR_HOUSING_CHECK: ['Fair housing check', 'Flags wording in ads and messages that could break fair housing rules'],
  IMAGE_MODERATION: ['Photo safety check', 'AI looks at every uploaded property and profile photo before it is published'],
  autoApproveConfidence: ['Auto-approve at or above (%)', 'Photos the AI judges safe with at least this confidence are published automatically. Everything below goes to a person for review. Raise it to send more photos to people.'],
  autoRejectEnabled: ['Reject clearly unsafe photos automatically', 'Off by default so a person always makes the call on flagged photos.'],
  autoRejectConfidence: ['Auto-reject at or above (%)', 'Used only when automatic rejection is on.'],
  maxImageMb: ['Largest photo (MB)'], maxPhotosPerProperty: ['Most photos per property'], notifyUploaderOnDecision: ['Tell the uploader when a photo is approved or rejected'],
  DOCUMENTS: ['Files and documents', 'Document library, uploads from residents, owners, vendors and applicants'],
  ESIGN: ['E-signatures', 'Send documents for typed-name signing with a certificate of completion'],
  SMART_FILING: ['Smart filing', 'Suggests the category and type of an uploaded file from its name'],
  storage: ['File storage'], maxFileMb: ['Largest file (MB)'], allowedExtensions: ['Allowed file types', 'One per line, without the dot. Executable and script types are always blocked.'],
  blockDuplicates: ['Warn about duplicate files', 'Stops the same file being added twice to the same record.'], trashDays: ['Keep deleted files for (days)', 'Then they are removed for good, unless a retention rule or legal hold applies.'],
  categories: ['Document categories', 'Retention is in years. Sensitive categories are visible only to roles with access to sensitive documents.'], retentionYears: ['Keep for (years)'], sensitive: ['Sensitive'], types: ['Document types', 'One per line.'],
  requirements: ['Required documents', 'Documents each record must have. Missing ones are flagged and shown as a checklist.'], APPLICATION: ['Applications'], LEASE: ['Leases'], VENDOR: ['Vendors'], OWNER: ['Owners'],
  uploads: ['Who can upload what', 'Categories each kind of person may add. Staff can add any category.'], applicantCategories: ['Applicants'], tenantCategories: ['Residents'], ownerCategories: ['Owners'], vendorCategories: ['Vendors'],
  reviewUploadsFromOthers: ['Staff review files sent by applicants, residents, owners and vendors'],
  esign: ['E-signature'], expiryDays: ['Signature requests expire after (days)'], requireConsent: ['Require consent to sign electronically'], consentText: ['Consent wording'],
  documentExpiry: ['Document expiry alerts', 'People are told before an insurance certificate, ID or contract expires'], signatureReminder: ['Signature reminders'], everyDays: ['Remind every (days)'],
  MESSAGING: ['Messaging', 'Conversations between residents, owners, vendors and staff'],
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
  methods: ['Accepted payment methods'], ach: ['Bank transfer (ACH)'], card: ['Debit and credit cards'],
  autopayEnabled: ['Allow residents to set up autopay'], allowPartialPayments: ['Accept partial payments'],
  minimumPayment: ['Smallest single payment ($)'], maximumPayment: ['Largest single payment ($)'],
  chargeNsfFee: ['Charge a returned-payment fee when a bank payment fails'], enforceStateFeeCaps: ['Apply state caps and grace periods to late and returned-payment fees', 'Recommended. Turning this off makes you responsible for staying within each state’s limits.'],
  convenienceFees: ['Processing fees paid by the resident', 'Added on top of the amount applied to the account. Check your state’s rules before charging.'],
  achFlat: ['Bank transfer fee ($ per payment)'], cardPercent: ['Card surcharge (% of payment)', 'Card networks cap surcharges at 3%, and PropertyOS enforces that.'],
  surchargeBlockedStates: ['States where card surcharges are not allowed', 'Two-letter codes, one per line. Verify the current list with your counsel.'],
  invoice: ['Invoice details', 'Printed on every invoice PDF'], numberPrefix: ['Invoice number prefix'], legalName: ['Legal name on invoices'], remitToAddress: ['Remit-to address'], taxId: ['Tax ID (EIN)'], paymentTerms: ['Payment terms'], footerNote: ['Footer note'],
  tax: ['Sales and rental tax', 'Most states don’t tax residential rent, so rates start at 0%. Enter the combined state and local rate where tax applies. Confirm with your tax advisor.'],
  taxRent: ['Charge tax on rent'], taxLateFees: ['Charge tax on late fees'], taxUtilities: ['Charge tax on utility charges'], taxApplicationFees: ['Charge tax on application fees'], taxOther: ['Charge tax on other charges and fees'],
  defaultRatePercent: ['Rate for states not listed (%)'], rates: ['Tax rate by state (%)', 'Security deposits and returned-payment fees are never taxed.'],
  reporting: ['Tax reporting'], form1099Threshold: ['1099 reporting threshold per owner ($)', 'IRS threshold for payments made in 2026 and later. Earlier years used $600.'],
  requireW9BeforePublish: ['Require a W-9 before publishing an owner statement'], backupWithholdingPercent: ['Backup withholding rate (%)'],
  depositCapMonths: ['Security deposit cap (months of rent)', 'Leave empty for no cap'], lateFeeGraceDays: ['Late fee grace period (days)'], lateFeeCapPercent: ['Late fee cap (% of rent)', 'Leave empty for no cap'],
  lateFeeCapFlat: ['Late fee cap ($)', 'Leave empty for no cap'], depositRefundDays: ['Deposit refund deadline (days)'], noticeDays: ['Notice period (days)'], nsfFeeCap: ['Returned-payment fee cap ($)', 'Leave empty for no cap'], name: ['State'],
  CONVERSATION_ASSIST: ['Conversation catch-up', 'Summarises a thread and suggests a reply for staff'],
  leaseExpiry: ['Lease expiry alerts', 'Residents, owners and the property’s brokers are told before a lease ends'],
  depositRefund: ['Deposit refund deadline alerts', 'Accountants, managers and brokers are told before the legal return date'],
  vendorInsurance: ['Vendor insurance expiry alerts', 'The vendor and managers are told before a certificate expires'],
  taxFiling: ['Sales tax filing alerts'], form1099: ['1099 reporting alerts'], w9Missing: ['Missing W-9 alerts'],
  daysBefore: ['Alert this many days before', 'One per line. Each is sent once. An overdue alert follows if the date passes.'], filingDay: ['Sales tax return due day of the following month'],
  who: ['Who can message whom', 'People can only reach others connected through the same properties.'],
  tenantWithStaff: ['Residents can message staff'], ownerWithStaff: ['Owners can message staff'], vendorWithStaff: ['Vendors can message staff'],
  tenantWithOwner: ['Residents can message owners directly', 'Off by default. Direct contact bypasses your team.'], ownerWithVendor: ['Owners can message vendors directly'], tenantWithVendor: ['Residents can message vendors directly'],
  allowStaffToStaff: ['Staff can message each other'], maxMessageLength: ['Longest message (characters)'],
  aiAssist: ['AI help in conversations'], summaries: ['Offer a catch-up summary'], suggestedReplies: ['Offer a suggested reply to staff'],
  guardrails: ['Message guardrails'], fairHousingCheck: ['Check staff messages for fair-housing wording', 'Blocks a message that contains a flagged phrase until it is reworded.'], flaggedPhrases: ['Flagged phrases', 'One per line'],
  companyName: ['Company name'], primaryColor: ['Primary colour'], accentColor: ['Accent colour'], portalDomain: ['Resident portal domain'], emailSender: ['Email sender address'],
};
const STATES: Record<string, string> = { AL: 'Alabama', AK: 'Alaska', AZ: 'Arizona', AR: 'Arkansas', CA: 'California', CO: 'Colorado', CT: 'Connecticut', DE: 'Delaware', DC: 'District of Columbia', FL: 'Florida', GA: 'Georgia', HI: 'Hawaii', ID: 'Idaho', IL: 'Illinois', IN: 'Indiana', IA: 'Iowa', KS: 'Kansas', KY: 'Kentucky', LA: 'Louisiana', ME: 'Maine', MD: 'Maryland', MA: 'Massachusetts', MI: 'Michigan', MN: 'Minnesota', MS: 'Mississippi', MO: 'Missouri', MT: 'Montana', NE: 'Nebraska', NV: 'Nevada', NH: 'New Hampshire', NJ: 'New Jersey', NM: 'New Mexico', NY: 'New York', NC: 'North Carolina', ND: 'North Dakota', OH: 'Ohio', OK: 'Oklahoma', OR: 'Oregon', PA: 'Pennsylvania', RI: 'Rhode Island', SC: 'South Carolina', SD: 'South Dakota', TN: 'Tennessee', TX: 'Texas', UT: 'Utah', VT: 'Vermont', VA: 'Virginia', WA: 'Washington', WV: 'West Virginia', WI: 'Wisconsin', WY: 'Wyoming' };
const human = (k: string) => {
  if (STATES[k]) return `${STATES[k]} (${k})`;
  if (k === 'DEFAULT') return 'All other states';
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
  ['payments', 'Payments and tax', 'Accepted methods, processing fees, invoice details, sales tax by state and 1099 reporting.'],
  ['moderation', 'Photo review', 'The confidence needed for the AI to publish a photo on its own, and what happens to photos it flags.'],
  ['documents', 'Files and documents', 'Categories, file types and size, required documents per record, e-signature wording and how long files are kept.'],
  ['alerts', 'Deadline alerts', 'When to alert people about lease expiry, deposit refunds, insurance, tax and 1099 dates.'],
  ['chat', 'Messaging', 'Who can message whom, message limits, AI help and fair-housing checks.'],
  ['stateRules', 'State rules', 'Legal limits by state: deposits, late fees, grace periods and notice. These cap what PropertyOS will charge.'],
];

/** Renders any JSON config value as an editable form, so every setting is changeable without a deploy. */
const HEX_COLOUR = /^#[0-9a-fA-F]{6}$/;

/**
 * Colour setting: a native colour picker next to a hex box, kept in step. The value is saved only when it is a full
 * six-digit hex code, so typing a code by hand never saves a half-finished colour.
 */
function ColourField({ label, hint, value, onChange }: { label: string; hint?: string; value: string; onChange: (v: string) => void }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  const type = (v: string) => {
    const t = v.startsWith('#') ? v : '#' + v;
    setText(t);
    if (HEX_COLOUR.test(t)) onChange(t.toUpperCase());
  };
  return (
    <Field label={label} hint={hint ?? 'Pick a colour, or type a code such as #0B2545'} error={HEX_COLOUR.test(text) ? undefined : 'Enter a six-digit code, like #0B2545'}>
      <div className="flex items-center gap-2">
        <input type="color" aria-label={`${label} picker`} value={HEX_COLOUR.test(text) ? text.toLowerCase() : '#000000'} onChange={(e) => type(e.target.value)}
          className="h-9 w-12 cursor-pointer rounded border border-limestone-200 bg-white p-1" />
        <Input value={text} maxLength={7} spellCheck={false} aria-label={`${label} code`} onChange={(e) => type(e.target.value)} className="max-w-[9rem] font-mono" />
      </div>
    </Field>
  );
}

function Node({ label, hint, value, onChange, depth = 0 }: { label: string; hint?: string; value: any; onChange: (v: any) => void; depth?: number }) {
  if (typeof value === 'boolean') return (
    <div className="flex items-start justify-between gap-4 rounded-md border border-limestone-200 bg-white px-4 py-3">
      <span className="min-w-0"><span className="block text-sm font-semibold text-graphite-900">{label}</span>{hint && <span className="mt-0.5 block text-xs text-graphite-500">{hint}</span>}</span>
      <Toggle checked={value} onChange={onChange} label={label} />
    </div>
  );
  if (typeof value === 'number') return <Field label={label} hint={hint}><Input type="number" step="any" value={value} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} /></Field>;
  if (typeof value === 'string' && HEX_COLOUR.test(value)) return <ColourField label={label} hint={hint} value={value} onChange={onChange} />;
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
  if (value === null) return <Field label={label} hint={hint ?? 'Leave empty for no limit'}><Input type="number" step="any" placeholder="No limit" value="" onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} /></Field>;
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
