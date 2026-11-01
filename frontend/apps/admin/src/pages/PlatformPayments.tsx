import { useState } from 'react';
import { Download, Plus, Play } from 'lucide-react';
import { PageHeader, Panel, DataTable, type Column, useApi, usePagedApi, useAction, api, fmt, Button, Badge, Stat, StatStrip, Tabs, Select, Modal, Drawer, Field, Input, Textarea, CompareChart, EntityPicker, KeyValues } from '@propertyos/ui';

/** What the companies pay PropertyOS: the operator's view (all companies) and each company's own view of its subscription. */

const TONE: Record<string, 'good' | 'warn' | 'crit' | 'neutral' | 'brand'> = { PAID: 'good', PARTIAL: 'warn', OPEN: 'brand', VOID: 'neutral' };
const LABEL: Record<string, string> = { PAID: 'Paid', PARTIAL: 'Part paid', OPEN: 'Open', VOID: 'Void' };
const HOW: Record<string, string> = { CARD: 'Card', ACH: 'Bank transfer', WIRE: 'Wire', CHEQUE: 'Cheque', CREDIT: 'Credit' };

function InvoiceStatus({ r }: { r: any }) {
  if (r.overdue) return <Badge tone="crit">Overdue</Badge>;
  return <Badge tone={TONE[r.status] ?? 'neutral'}>{LABEL[r.status] ?? r.status}</Badge>;
}

/** A simple printable statement of one invoice, kept plain so it works for both sides. */
async function downloadInvoice(inv: any, orgName: string) {
  const { jsPDF } = await import('jspdf');
  const d = new jsPDF({ unit: 'pt', format: 'letter' });
  const W = d.internal.pageSize.getWidth(), M = 56;
  d.setFillColor(15, 61, 62); d.rect(0, 0, W, 10, 'F');
  d.setFont('helvetica', 'bold'); d.setFontSize(20); d.setTextColor(15, 61, 62); d.text('PropertyOS', M, 60);
  d.setFontSize(26); d.setTextColor(30, 30, 30); d.text('INVOICE', W - M, 60, { align: 'right' });
  d.setFont('helvetica', 'normal'); d.setFontSize(10); d.setTextColor(110, 110, 110); d.text(`No. ${inv.number}`, W - M, 78, { align: 'right' });
  d.setFont('helvetica', 'bold'); d.setFontSize(9); d.text('BILLED TO', M, 120); d.setFont('helvetica', 'normal'); d.setFontSize(12); d.setTextColor(30, 30, 30); d.text(orgName, M, 138);
  d.setFontSize(10); d.setTextColor(110, 110, 110);
  d.text([`Issued ${fmt.date(inv.created_at)}`, `Due ${fmt.date(inv.due_date)}`, `Status ${LABEL[inv.status] ?? inv.status}`], W / 2 + 40, 120);
  let y = 200;
  d.setFillColor(15, 61, 62); d.rect(M, y, W - 2 * M, 24, 'F');
  d.setTextColor(255, 255, 255); d.setFont('helvetica', 'bold'); d.text('DESCRIPTION', M + 10, y + 16); d.text('AMOUNT', W - M - 10, y + 16, { align: 'right' });
  y += 46; d.setFont('helvetica', 'normal'); d.setTextColor(30, 30, 30); d.setFontSize(11);
  const lines = d.splitTextToSize(String(inv.description), W - 2 * M - 120) as string[];
  d.text(lines, M + 10, y); d.text(fmt.usd(inv.amount, true), W - M - 10, y, { align: 'right' });
  y += lines.length * 14 + 16; d.setDrawColor(210, 210, 205); d.line(M, y, W - M, y); y += 28;
  const row = (l: string, v: string, bold = false) => { d.setFont('helvetica', bold ? 'bold' : 'normal'); d.text(l, W - M - 200, y); d.text(v, W - M - 10, y, { align: 'right' }); y += 20; };
  row('Subtotal', fmt.usd(Number(inv.amount) - Number(inv.tax_amount ?? 0), true));
  if (Number(inv.tax_amount) > 0) row('Tax', fmt.usd(inv.tax_amount, true));
  row('Total', fmt.usd(inv.amount, true), true); row('Paid', fmt.usd(inv.paid, true)); row('Balance due', fmt.usd(inv.balance, true), true);
  d.setFontSize(9); d.setTextColor(140, 140, 140); d.text(`PropertyOS subscription invoice. All amounts in USD. Generated ${new Date().toLocaleDateString('en-US')}.`, M, d.internal.pageSize.getHeight() - 40);
  d.save(`propertyos-${inv.number}.pdf`);
}

function Detail({ id, onClose, operator, orgName }: { id: string; onClose: () => void; operator: boolean; orgName?: string }) {
  const base = operator ? '/api/identity/platform/billing' : '/api/identity/billing';
  const { data: inv } = useApi<any>(`${base}/invoices/${id}`);
  const [method, setMethod] = useState(operator ? 'WIRE' : 'CARD');
  const [last4, setLast4] = useState(''); const [amount, setAmount] = useState(''); const [reference, setReference] = useState(''); const [reason, setReason] = useState('');
  const [voiding, setVoiding] = useState(false);
  const refresh = ['/api/identity/platform/billing', '/api/identity/billing'];
  const pay = useAction(() => api.post(operator ? `${base}/invoices/${id}/payments` : `${base}/invoices/${id}/pay`, { method, last4: last4 || undefined, amount: amount ? Number(amount) : undefined, reference: reference || undefined }),
    { success: operator ? 'Payment recorded' : 'Payment received. Thank you', invalidate: refresh });
  const voidIt = useAction(() => api.post(`${base}/invoices/${id}/void`, { reason }), { success: 'Invoice voided', invalidate: refresh, onSuccess: () => setVoiding(false) });
  const owing = inv && inv.status !== 'VOID' && Number(inv.balance) > 0;
  const needsDigits = method === 'CARD' || method === 'ACH';
  return (<Drawer open onClose={onClose} title={inv ? `Invoice ${inv.number}` : 'Invoice'} subtitle={inv?.org_name} width={560}
    footer={inv && <><Button variant="secondary" icon={<Download size={14} />} onClick={() => downloadInvoice(inv, inv.org_name ?? orgName ?? '')}>Download PDF</Button>
      {operator && inv.status !== 'VOID' && Number(inv.paid) === 0 && <Button variant="danger" onClick={() => setVoiding(true)}>Void</Button>}</>}>
    {!inv ? <p className="text-sm text-graphite-600">Loading…</p> : <div className="space-y-5">
      <div className="flex items-center gap-2"><InvoiceStatus r={inv} /><span className="text-sm text-graphite-600">{inv.description}</span></div>
      <KeyValues cols={2} items={[{ label: 'Amount', value: fmt.usd(inv.amount, true) }, { label: 'Paid', value: fmt.usd(inv.paid, true) }, { label: 'Balance', value: fmt.usd(inv.balance, true) }, { label: 'Due', value: fmt.date(inv.due_date) },
        { label: 'Units billed', value: inv.units ?? '–' }, { label: 'Plan', value: inv.plan ?? 'One-off charge' }]} />
      <div><h3 className="mb-2 text-sm font-bold text-graphite-900">Payments</h3>
        {(inv.payments ?? []).length === 0 ? <p className="text-sm text-graphite-600">No payments yet.</p> : <ul className="divide-y divide-limestone-200 rounded border border-limestone-200">
          {inv.payments.map((p: any) => <li key={p.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm"><span>{fmt.date(p.created_at)} · {HOW[p.method] ?? p.method}{p.last4 ? ` ending ${p.last4}` : ''}<span className="block text-xs text-graphite-500">{p.reference}{p.failure_reason ? ` · ${p.failure_reason}` : ''}</span></span>
            <span className={p.status === 'FAILED' ? 'font-semibold text-crit-700' : 'font-semibold'}>{p.status === 'FAILED' ? 'Failed ' : ''}{fmt.usd(p.amount, true)}</span></li>)}</ul>}</div>
      {owing && <div className="space-y-3 rounded-md border border-limestone-200 bg-limestone-50 p-4">
        <h3 className="text-sm font-bold text-graphite-900">{operator ? 'Record a payment' : 'Pay this invoice'}</h3>
        <Field label="How"><Select aria-label="How" value={method} onChange={(e) => setMethod(e.target.value)}>
          {(operator ? ['WIRE', 'CHEQUE', 'ACH', 'CARD', 'CREDIT'] : ['CARD', 'ACH']).map((m) => <option key={m} value={m}>{HOW[m]}</option>)}</Select></Field>
        {needsDigits && <Field label="Last 4 digits" hint="Demo gateway: numbers ending 0002 are declined."><Input maxLength={4} value={last4} onChange={(e) => setLast4(e.target.value.replace(/\D/g, ''))} /></Field>}
        <Field label="Amount (USD)" hint={`Leave empty to pay the whole balance of ${fmt.usd(inv.balance, true)}.`}><Input type="number" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        {operator && <Field label="Reference" hint="The wire or cheque number, if there is one."><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>}
        <Button variant="primary" loading={pay.isPending} disabled={needsDigits && last4.length !== 4} onClick={() => pay.mutate(undefined)}>{operator ? 'Record payment' : `Pay ${fmt.usd(amount || inv.balance, true)}`}</Button>
      </div>}
    </div>}
    <Modal open={voiding} onClose={() => setVoiding(false)} title="Void this invoice?" footer={<><Button variant="quiet" onClick={() => setVoiding(false)}>Cancel</Button><Button variant="danger" loading={voidIt.isPending} disabled={!reason.trim()} onClick={() => voidIt.mutate(undefined)}>Void invoice</Button></>}>
      <Field label="Reason" required hint="Saved in the audit trail."><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
    </Modal>
  </Drawer>);
}

export function PlatformPayments() {
  const [tab, setTab] = useState<'invoices' | 'payments'>('invoices');
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [charge, setCharge] = useState(false);
  const [c, setC] = useState<any>({ orgId: '', description: '', amount: '' });
  const { data: s } = useApi<any>('/api/identity/platform/billing/summary');
  const invoices = usePagedApi<any>(`/api/identity/platform/billing/invoices${status ? `?f.${status === 'OVERDUE' ? 'overdue=true' : `status=${status}`}` : ''}`);
  const payments = usePagedApi<any>('/api/identity/platform/billing/payments');
  const refresh = ['/api/identity/platform/billing'];
  const run = useAction(() => api.post<any>('/api/identity/platform/billing/run', {}), { success: (r) => `${r.created} new invoice${r.created === 1 ? '' : 's'} for ${r.period}${r.alreadyInvoiced ? `, ${r.alreadyInvoiced} already invoiced` : ''}`, invalidate: refresh });
  const make = useAction(() => api.post('/api/identity/platform/billing/invoices', { orgId: c.orgId, description: c.description, amount: Number(c.amount) }), { success: 'Charge created', invalidate: refresh, onSuccess: () => { setCharge(false); setC({ orgId: '', description: '', amount: '' }); } });
  const [ledger, setLedger] = useState<string | null>(null);
  const trend: any[] = (s?.trend ?? []).map((t: any) => ({ ...t, billed: Number(t.billed), collected: Number(t.collected) }));
  const invCols: Column<any>[] = [
    { key: 'number', header: 'Invoice', render: (r) => <button className="font-semibold text-federal-800 underline" onClick={() => setOpen(r.id)}>{r.number}</button> },
    { key: 'org_name', header: 'Company', render: (r) => <button className="text-left hover:underline" onClick={() => setLedger(r.org_id)}>{r.org_name}</button> },
    { key: 'period', header: 'For', hideOnMobile: true, render: (r) => r.period ?? 'One-off' },
    { key: 'amount', header: 'Amount', align: 'right', value: (r) => Number(r.amount), render: (r) => fmt.usd(r.amount, true) },
    { key: 'balance', header: 'Balance', align: 'right', value: (r) => Number(r.balance), render: (r) => fmt.usd(r.balance, true) },
    { key: 'due_date', header: 'Due', value: (r) => r.due_date, render: (r) => fmt.date(r.due_date) },
    { key: 'status', header: 'Status', render: (r) => <InvoiceStatus r={r} /> },
  ];
  const payCols: Column<any>[] = [
    { key: 'created_at', header: 'When', value: (r) => r.created_at, render: (r) => fmt.dateTime(r.created_at) },
    { key: 'org_name', header: 'Company' }, { key: 'invoice_number', header: 'Invoice' },
    { key: 'method', header: 'How', render: (r) => `${HOW[r.method] ?? r.method}${r.last4 ? ` ····${r.last4}` : ''}` },
    { key: 'reference', header: 'Reference', hideOnMobile: true },
    { key: 'amount', header: 'Amount', align: 'right', value: (r) => Number(r.amount), render: (r) => fmt.usd(r.amount, true) },
    { key: 'status', header: 'Outcome', render: (r) => <Badge tone={r.status === 'SUCCEEDED' ? 'good' : 'crit'}>{r.status === 'SUCCEEDED' ? 'Received' : 'Failed'}</Badge> },
  ];
  return (<>
    <PageHeader title="Subscription payments" description="What every company pays PropertyOS: invoices, payments, who is behind. Companies are billed monthly on units managed, never less than their plan's minimum."
      actions={<><Button variant="secondary" icon={<Play size={14} />} loading={run.isPending} onClick={() => run.mutate(undefined)}>Run billing now</Button><Button variant="primary" icon={<Plus size={14} />} onClick={() => setCharge(true)}>New charge</Button></>} />
    <StatStrip className="mb-6">
      <Stat label="Billed this month" value={fmt.usd(s?.billedThisMonth ?? 0, true)} emphasis sub={`${s?.payingCompanies ?? 0} companies`} />
      <Stat label="Collected this month" value={fmt.usd(s?.collectedThisMonth ?? 0, true)} sub={`${fmt.usd(s?.collected30d ?? 0)} in 30 days`} />
      <Stat label="Outstanding" value={fmt.usd(s?.outstanding ?? 0, true)} />
      <Stat label="Overdue" value={fmt.usd(s?.overdue ?? 0, true)} sub={`${s?.overdueCompanies ?? 0} companies`} />
      <Stat label="Failed, 30 days" value={s?.failed30d ?? 0} />
    </StatStrip>
    <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
      <Panel title="Billed and collected" description="The last six months." className="lg:col-span-2">
        {trend.length > 0 && <CompareChart data={trend} x="month" bars={[{ key: 'billed', label: 'Billed' }, { key: 'collected', label: 'Collected' }]} />}
      </Panel>
      <Panel title="Who owes the most" padded={false}>
        {(s?.topOwing ?? []).length === 0 ? <p className="p-5 text-sm text-graphite-600">Nobody owes anything.</p> : <ul className="divide-y divide-limestone-200">
          {s.topOwing.map((o: any) => <li key={o.org_id}><button className="flex w-full items-center justify-between gap-3 px-5 py-3 text-left hover:bg-limestone-50" onClick={() => setLedger(o.org_id)}>
            <span><span className="block text-sm font-semibold text-graphite-900">{o.name}</span><span className="block text-xs text-graphite-600">oldest due {fmt.date(o.oldest_due)}</span></span><span className="font-semibold">{fmt.usd(o.owing, true)}</span></button></li>)}</ul>}
      </Panel>
    </div>
    <Tabs tabs={[{ key: 'invoices', label: 'Invoices', count: invoices.total }, { key: 'payments', label: 'Payments', count: payments.total }]} value={tab} onChange={setTab} className="mb-4" />
    {tab === 'invoices' ? <Panel padded={false}><DataTable rows={invoices.rows} server={invoices.server} columns={invCols} rowKey={(r) => r.id} loading={invoices.isLoading}
      toolbar={<Select aria-label="Filter invoices" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All invoices</option><option value="OVERDUE">Overdue</option><option value="OPEN">Open</option><option value="PARTIAL">Part paid</option><option value="PAID">Paid</option><option value="VOID">Void</option></Select>} /></Panel>
      : <Panel padded={false}><DataTable rows={payments.rows} server={payments.server} columns={payCols} rowKey={(r) => r.id} loading={payments.isLoading} /></Panel>}
    {open && <Detail id={open} operator onClose={() => setOpen(null)} />}
    {ledger && <Ledger orgId={ledger} onClose={() => setLedger(null)} onOpen={(id) => { setLedger(null); setOpen(id); }} />}
    <Modal open={charge} onClose={() => setCharge(false)} title="New one-off charge" footer={<><Button variant="quiet" onClick={() => setCharge(false)}>Cancel</Button>
      <Button variant="primary" loading={make.isPending} disabled={!c.orgId || !c.description.trim() || !(Number(c.amount) > 0)} onClick={() => make.mutate(undefined)}>Create charge</Button></>}>
      <div className="space-y-3">
        <Field label="Company" required><EntityPicker<any> endpoint="/api/identity/platform/usage" value={c.orgId || null} onChange={(id) => setC({ ...c, orgId: id ?? '' })} label={(o) => o.name} sub={(o) => `${o.plan} plan`} placeholder="Search companies" /></Field>
        <Field label="What it is for" required><Input value={c.description} onChange={(e) => setC({ ...c, description: e.target.value })} placeholder="e.g. Data import and set-up help" /></Field>
        <Field label="Amount (USD)" required><Input type="number" step="0.01" value={c.amount} onChange={(e) => setC({ ...c, amount: e.target.value })} /></Field>
      </div>
    </Modal>
  </>);
}

function Ledger({ orgId, onClose, onOpen }: { orgId: string; onClose: () => void; onOpen: (id: string) => void }) {
  const { data: l } = useApi<any>(`/api/identity/platform/billing/orgs/${orgId}`);
  return (<Drawer open onClose={onClose} title={l?.name ?? 'Company'} subtitle="Account with PropertyOS" width={600}>
    {!l ? <p className="text-sm text-graphite-600">Loading…</p> : <div className="space-y-5">
      <StatStrip><Stat label="Billed" value={fmt.usd(l.billed, true)} /><Stat label="Paid" value={fmt.usd(l.paid, true)} /><Stat label="Balance" value={fmt.usd(l.balance, true)} emphasis /><Stat label="Overdue" value={fmt.usd(l.overdue, true)} /></StatStrip>
      <div><h3 className="mb-2 text-sm font-bold text-graphite-900">Invoices</h3><ul className="divide-y divide-limestone-200 rounded border border-limestone-200">
        {l.invoices.length === 0 && <li className="px-3 py-3 text-sm text-graphite-600">No invoices yet.</li>}
        {l.invoices.map((i: any) => <li key={i.id}><button className="flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-limestone-50" onClick={() => onOpen(i.id)}>
          <span><span className="font-semibold">{i.number}</span><span className="block text-xs text-graphite-500">{i.period ?? 'One-off'} · due {fmt.date(i.due_date)}</span></span><span>{fmt.usd(i.amount, true)} <Badge tone={TONE[i.status] ?? 'neutral'}>{LABEL[i.status] ?? i.status}</Badge></span></button></li>)}</ul></div>
      <div><h3 className="mb-2 text-sm font-bold text-graphite-900">Payments</h3><ul className="divide-y divide-limestone-200 rounded border border-limestone-200">
        {l.payments.length === 0 && <li className="px-3 py-3 text-sm text-graphite-600">No payments yet.</li>}
        {l.payments.map((p: any) => <li key={p.id} className="flex items-center justify-between px-3 py-2 text-sm"><span>{fmt.date(p.created_at)} · {HOW[p.method] ?? p.method} · {p.invoice_number}</span><span className={p.status === 'FAILED' ? 'font-semibold text-crit-700' : 'font-semibold'}>{p.status === 'FAILED' ? 'Failed ' : ''}{fmt.usd(p.amount, true)}</span></li>)}</ul></div>
    </div>}
  </Drawer>);
}

/** A company's own account with PropertyOS: plan, price, what it owes and every invoice, with a way to pay. */
export function Subscription() {
  const { data: s } = useApi<any>('/api/identity/billing/subscription');
  const invoices = usePagedApi<any>('/api/identity/billing/invoices');
  const [open, setOpen] = useState<string | null>(null);
  const cols: Column<any>[] = [
    { key: 'number', header: 'Invoice', render: (r) => <button className="font-semibold text-federal-800 underline" onClick={() => setOpen(r.id)}>{r.number}</button> },
    { key: 'description', header: 'What it is for', hideOnMobile: true },
    { key: 'amount', header: 'Amount', align: 'right', value: (r) => Number(r.amount), render: (r) => fmt.usd(r.amount, true) },
    { key: 'balance', header: 'Balance', align: 'right', value: (r) => Number(r.balance), render: (r) => fmt.usd(r.balance, true) },
    { key: 'due_date', header: 'Due', value: (r) => r.due_date, render: (r) => fmt.date(r.due_date) },
    { key: 'status', header: 'Status', render: (r) => <InvoiceStatus r={r} /> },
    { key: 'pay', header: '', render: (r) => (Number(r.balance) > 0 && r.status !== 'VOID' ? <Button size="sm" variant="primary" onClick={() => setOpen(r.id)}>Pay</Button> : null) },
  ];
  return (<>
    <PageHeader title="Subscription" description="Your PropertyOS plan, what you owe and your invoices." />
    {s && <StatStrip className="mb-6">
      <Stat label="Plan" value={s.plan_name ?? s.plan} emphasis sub={`${fmt.usd(s.price_per_unit, true)} per unit, minimum ${fmt.usd(s.minimum_monthly)} a month`} />
      <Stat label="Units you manage" value={s.units} sub={`Next invoice about ${fmt.usd(s.estimatedNextInvoice, true)}`} />
      <Stat label="Balance due" value={fmt.usd(s.balance, true)} />
      <Stat label="Overdue" value={fmt.usd(s.overdue, true)} />
    </StatStrip>}
    <Panel padded={false}><DataTable rows={invoices.rows} server={invoices.server} columns={cols} rowKey={(r) => r.id} loading={invoices.isLoading} empty={{ title: 'No invoices yet', body: 'Your first PropertyOS invoice appears here at the start of the month.' }} /></Panel>
    {open && <Detail id={open} operator={false} orgName={s?.name} onClose={() => setOpen(null)} />}
  </>);
}
