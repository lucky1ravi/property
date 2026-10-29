import { useState } from 'react';
import { NavLink, Route, Routes, useNavigate } from 'react-router-dom';
import {
  DocumentsPanel,
  MicroApp, Tabs, PageHeader, Panel, Stat, StatStrip, Button, StatusPill, Badge, DataTable, type Column, Drawer, Modal, Field, Input, Select, KeyValues, Timeline,
  TrendChart, CompareChart, AiInsight, EmptyState, Skeleton, Toggle, ConfirmDialog, Money,
  useApi, useAction, api, fmt, useSession, personaOf, QuickLink, InvoiceDownload } from '@propertyos/ui';

const BASE = '/app/payments';
const inv = ['/api/billing'];

// The section tabs (Payments: Overview, Invoices, Collections...) are drawn once by the shell from the same menu definition as the left menu.
const SubNav = () => null;

function Overview() {
  const { data: s } = useApi<any>('/api/billing/summary');
  const { data: a } = useApi<any>('/api/billing/aging');
  const { data: c } = useApi<any[]>('/api/billing/collections');
  const cycle = useAction(() => api.post<any>('/api/billing/run-cycle'), { success: (r) => `Billing cycle complete${r?.created != null ? `: ${r.created} invoices created` : ''}`, invalidate: inv });
  const trend = (s?.trend ?? []).map((t: any) => ({ ...t, billed: Number(t.billed), collected: Number(t.collected) }));
  const aging = a ? [{ k: 'Current', v: a.current_due }, { k: '1–30 days', v: a.d1_30 }, { k: '31–60 days', v: a.d31_60 }, { k: '61+ days', v: a.d61_plus }] : [];
  return (<>
    <PageHeader title="Payments" description="Billing, collections and owner payouts. Rent is invoiced and reminders go out automatically."
      actions={<Button variant="secondary" loading={cycle.isPending} onClick={() => cycle.mutate(undefined)}>Run billing cycle</Button>} />
    <SubNav />
    {!s ? <Skeleton className="h-40" /> : <>
      <StatStrip className="mb-6">
        <Stat label="Collected this month" value={fmt.usd(s.collected_this_month)} emphasis sub={`${fmt.pct(s.collection_rate)} of ${fmt.usd(s.billed_this_month)} billed`} />
        <Stat label="Overdue" value={fmt.usd(s.overdue_total)} sub={`${s.overdue_invoices} invoices`} />
        <Stat label="Delinquent residents" value={s.delinquent_tenants} />
        <Stat label="Total receivable" value={fmt.usd(s.receivable_total)} />
        <Stat label="Failed payments (30d)" value={s.failed_payments_30d} />
      </StatStrip>
      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="Billed and collected" description="By month"><CompareChart data={trend} x="month" bars={[{ key: 'billed', label: 'Billed' }, { key: 'collected', label: 'Collected' }]} /></Panel>
        <Panel title="Receivables by age"><CompareChart data={aging} x="k" bars={[{ key: 'v', label: 'Amount' }]} /></Panel>
      </div>
      {(c?.length ?? 0) > 0 && (() => {
        const high = c!.filter((x) => x.risk_level === 'HIGH');
        const top = c!.slice(0, 3);
        const conf = Math.max(...top.map((x) => x.risk_score ?? 0)) / 100;
        const owed = c!.reduce((n, x) => n + Number(x.balance), 0);
        return <div className="mt-6"><AiInsight title={`${high.length} high-risk ${high.length === 1 ? 'account' : 'accounts'} of ${c!.length} overdue`} confidence={conf || 0.5}
          reasons={top.map((x) => `${x.tenant_name}: ${fmt.usd(x.balance)} overdue ${x.oldest_days} days, risk score ${x.risk_score}. ${(x.reasons ?? []).join('; ')}. ${x.recommendation}`)}>
          {fmt.usd(owed)} is overdue across {c!.length} residents. Work the list from the highest risk score down; the score comes from days past due, late fees and failed payments.</AiInsight></div>;
      })()}
    </>}
  </>);
}

function InvoiceDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { can, session } = useSession();
  const navigate = useNavigate();
  const { data: i } = useApi<any>(id ? `/api/billing/invoices/${id}` : null);
  const [amount, setAmount] = useState(''); const [voiding, setVoiding] = useState(false);
  const pay = useAction(() => api.post('/api/billing/payments', { tenantId: i.tenant_id, invoiceId: id, amount: Number(amount), method: 'ACH' }), { success: 'Payment recorded', invalidate: inv, onSuccess: () => setAmount('') });
  const voidIt = useAction((reason: string) => api.post(`/api/billing/invoices/${id}/void`, { reason }), { success: 'Invoice voided', invalidate: inv, onSuccess: () => { setVoiding(false); onClose(); } });
  return (
    <Drawer open={!!id} onClose={onClose} title={i?.description ?? 'Invoice'} subtitle={i && `${i.tenant_name} · ${i.unit_label}`}>
      {!i ? <Skeleton className="h-40" /> : <div className="space-y-6">
        <div className="flex justify-end gap-2">{can('CONVERSATION:CREATE') && personaOf(session) !== 'tenant' && <Button size="sm" variant="secondary" onClick={() => navigate(`/app/messages?new=1&ctx=INVOICE&id=${i.id}&label=${encodeURIComponent(`${i.invoice_number ?? ''} ${i.description ?? ''}`.trim())}&subject=${encodeURIComponent(`${i.description ?? 'Invoice'} for ${i.tenant_name}`)}`)}>Message about this</Button>}<InvoiceDownload id={i.id} invoice={i} label="Download PDF" /></div>
        <KeyValues cols={3} items={[{ label: 'Invoice #', value: i.invoice_number ?? '—' }, { label: 'Status', value: <StatusPill status={i.status} /> },
          ...(Number(i.tax_amount) > 0 ? [{ label: 'Subtotal', value: fmt.usd(i.subtotal, true) }, { label: `Tax (${Number(i.tax_rate)}%${i.state ? `, ${i.state}` : ''})`, value: fmt.usd(i.tax_amount, true) }] : []),
          { label: 'Total', value: fmt.usd(i.amount, true) }, { label: 'Paid', value: fmt.usd(i.paid, true) }, { label: 'Balance', value: fmt.usd(i.balance, true) }, { label: 'Due', value: fmt.date(i.due_date) }, { label: 'Type', value: i.kind }]} />
        {can('PAYMENT:CREATE') && ['OPEN', 'PARTIAL'].includes(i.status) && <Panel title="Record a payment" tone="tinted">
          <div className="flex gap-2"><Input type="number" min="0" step="0.01" placeholder={`Up to ${fmt.usd(i.balance, true)}`} value={amount} onChange={(e) => setAmount(e.target.value)} />
            <Button variant="primary" disabled={!amount} loading={pay.isPending} onClick={() => pay.mutate(undefined)}>Record</Button></div></Panel>}
        <Panel title="Payments">{(i.payments ?? []).length ? <ul className="space-y-1 text-sm">{i.payments.map((p: any) => <li key={p.id} className="flex justify-between"><span>{p.reference} · {p.method} {p.method_last4}</span><Money value={p.amount} cents /></li>)}</ul> : <p className="text-sm text-graphite-500">No payments yet.</p>}</Panel>
        <DocumentsPanel entityType="INVOICE" entityId={i.id} title="Attachments" compact />
        <Panel title="History"><Timeline items={i.timeline} /></Panel>
        {can('INVOICE:EDIT') && ['OPEN', 'PARTIAL'].includes(i.status) && <Button variant="danger" onClick={() => setVoiding(true)}>Void invoice</Button>}
      </div>}
      <ConfirmDialog open={voiding} title="Void this invoice?" confirmLabel="Void invoice" tone="danger" requireReason loading={voidIt.isPending} onClose={() => setVoiding(false)} onConfirm={(r) => voidIt.mutate(r)} />
    </Drawer>
  );
}

function Invoices() {
  const { can } = useSession();
  const { data, isLoading } = useApi<any[]>('/api/billing/invoices');
  const [open, setOpen] = useState<string | null>(null); const [charge, setCharge] = useState(false);
  const cols: Column<any>[] = [
    { key: 'invoice_number', header: 'Invoice #', hideOnMobile: true },
    { key: 'tenant_name', header: 'Resident', render: (r) => (<div><div className="font-semibold"><QuickLink kind="tenant" id={r.tenant_id} name={r.tenant_name}>{r.tenant_name}</QuickLink></div><div className="text-xs text-graphite-500">{r.unit_label}</div></div>) },
    { key: 'description', header: 'Description', hideOnMobile: true },
    { key: 'status', header: 'Status', render: (r) => <StatusPill status={r.status} /> },
    { key: 'due_date', header: 'Due', value: (r) => r.due_date, render: (r) => fmt.date(r.due_date) },
    { key: 'balance', header: 'Balance', align: 'right', value: (r) => Number(r.balance), render: (r) => fmt.usd(r.balance, true) },
  ];
  return (<><PageHeader title="Invoices" actions={can('INVOICE:CREATE') && <Button variant="primary" onClick={() => setCharge(true)}>New charge</Button>} /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} onRowClick={(r) => setOpen(r.id)} exportName="invoices" initialSort={{ key: 'due_date', dir: 'desc' }} /></Panel>
    <InvoiceDrawer id={open} onClose={() => setOpen(null)} /><ChargeModal open={charge} onClose={() => setCharge(false)} /></>);
}

function ChargeModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data: leases } = useApi<any[]>(open ? '/api/leasing/leases?status=ACTIVE' : null);
  const [f, setF] = useState<any>({ tenantId: '', kind: 'FEE', description: '', amount: '', dueDate: '' });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const go = useAction(() => api.post('/api/billing/invoices', { ...f, amount: Number(f.amount) }), { success: 'Charge added', invalidate: inv, onSuccess: onClose });
  return (<Modal open={open} onClose={onClose} title="New charge" footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={go.isPending} disabled={!f.tenantId || !f.amount || !f.description} onClick={() => go.mutate(undefined)}>Add charge</Button></>}>
    <div className="space-y-3">
      <Field label="Resident" required><Select value={f.tenantId} onChange={set('tenantId')}><option value="">Choose a resident</option>{(leases ?? []).map((l) => <option key={l.id} value={l.tenant_id}>{l.tenant_name} · {l.unit_label}</option>)}</Select></Field>
      <Field label="Type"><Select value={f.kind} onChange={set('kind')}><option value="FEE">Fee</option><option value="LATE_FEE">Late fee (state cap applies)</option><option value="UTILITY">Utility</option><option value="OTHER">Other</option></Select></Field>
      <Field label="Description" required><Input value={f.description} onChange={set('description')} /></Field>
      <div className="grid grid-cols-2 gap-3"><Field label="Amount (USD)" required><Input type="number" min="0" step="0.01" value={f.amount} onChange={set('amount')} /></Field><Field label="Due date"><Input type="date" value={f.dueDate} onChange={set('dueDate')} /></Field></div>
    </div></Modal>);
}

function Collections() {
  const { data, isLoading } = useApi<any[]>('/api/billing/collections');
  const remind = useAction((tenantId?: string) => api.post('/api/billing/collections/remind', tenantId ? { tenantId } : {}), { success: 'Reminders sent', invalidate: inv });
  const cols: Column<any>[] = [
    { key: 'tenant_name', header: 'Resident', render: (r) => (<div><div className="font-semibold"><QuickLink kind="tenant" id={r.tenant_id} name={r.tenant_name}>{r.tenant_name}</QuickLink></div><div className="text-xs text-graphite-500">{r.unit_label}</div></div>) },
    { key: 'balance', header: 'Overdue', align: 'right', value: (r) => Number(r.balance), render: (r) => fmt.usd(r.balance) },
    { key: 'oldest_days', header: 'Oldest', align: 'right', render: (r) => `${r.oldest_days} days` },
    { key: 'risk_level', header: 'Risk', render: (r) => <Badge tone={r.risk_level === 'HIGH' ? 'crit' : r.risk_level === 'MEDIUM' ? 'warn' : 'neutral'}>{r.risk_level}</Badge> },
    { key: 'recommendation', header: 'Suggested next step', hideOnMobile: true },
    { key: 'a', header: '', align: 'right', render: (r) => <Button size="sm" onClick={() => remind.mutate(r.tenant_id)}>Send reminder</Button> },
  ];
  return (<><PageHeader title="Collections" description="Residents behind on rent, ranked by risk. AI suggests the next step; you decide." actions={<Button variant="primary" loading={remind.isPending} onClick={() => remind.mutate(undefined)}>Remind everyone overdue</Button>} /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.tenant_id} loading={isLoading} initialSort={{ key: 'balance', dir: 'desc' }} empty={{ title: 'Nobody is overdue' }} /></Panel></>);
}

function Payments() {
  const { data, isLoading } = useApi<any[]>('/api/billing/payments');
  const cols: Column<any>[] = [
    { key: 'created_at', header: 'Date', value: (r) => r.created_at, render: (r) => fmt.date(r.created_at) },
    { key: 'tenant_name', header: 'Resident', render: (r) => <QuickLink kind="tenant" id={r.tenant_id} name={r.tenant_name}>{r.tenant_name}</QuickLink> }, { key: 'reference', header: 'Reference', hideOnMobile: true, render: (r) => <QuickLink kind="payment" id={r.id}>{r.reference}</QuickLink> },
    { key: 'method', header: 'Method', render: (r) => `${r.method} ${r.method_last4 ?? ''}` },
    { key: 'status', header: 'Status', render: (r) => <StatusPill status={r.status} /> },
    { key: 'amount', header: 'Amount', align: 'right', value: (r) => Number(r.amount), render: (r) => fmt.usd(r.amount, true) },
  ];
  return (<><PageHeader title="Payments" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} exportName="payments" initialSort={{ key: 'created_at', dir: 'desc' }} /></Panel></>);
}

function Statements() {
  const { can } = useSession();
  const { data, isLoading } = useApi<any[]>('/api/billing/statements');
  const { data: owners } = useApi<any[]>(can('OWNER:VIEW') ? '/api/leasing/owners' : null);
  const { data: props } = useApi<any[]>(can('PROPERTY:VIEW') ? '/api/leasing/properties' : null);
  const [exp, setExp] = useState(false);
  const blankExp = { propertyId: '', description: '', category: 'REPAIRS', amount: '', date: new Date().toISOString().slice(0, 10) };
  const [e, setE] = useState(blankExp);
  const addExpense = useAction(() => {
    const p = props?.find((x) => x.id === e.propertyId);
    return api.post('/api/billing/expenses', { propertyId: e.propertyId, ownerId: p?.owner_id, description: e.description, category: e.category, amount: Number(e.amount), date: e.date });
  }, { success: 'Expense recorded. It appears on the next owner statement.', invalidate: inv, onSuccess: () => { setE(blankExp); setExp(false); } });
  const [open, setOpen] = useState<any>(null); const [gen, setGen] = useState(false);
  const [g, setG] = useState({ ownerId: '', period: new Date().toISOString().slice(0, 7) });
  const create = useAction(() => api.post('/api/billing/statements', { ownerId: g.ownerId, period: g.period, ownerName: owners?.find((o) => o.id === g.ownerId)?.name ?? 'Owner' }), { success: 'Statement drafted', invalidate: inv, onSuccess: () => setGen(false) });
  const publish = useAction((id: string) => api.post(`/api/billing/statements/${id}/publish`), { success: 'Published to the owner', invalidate: inv, onSuccess: () => setOpen(null) });
  const cols: Column<any>[] = [
    { key: 'owner_name', header: 'Owner' }, { key: 'period', header: 'Period' },
    { key: 'rent_collected', header: 'Rent collected', align: 'right', value: (r) => Number(r.rent_collected), render: (r) => fmt.usd(r.rent_collected) },
    { key: 'mgmt_fee', header: 'Management fee', align: 'right', hideOnMobile: true, render: (r) => fmt.usd(r.mgmt_fee) },
    { key: 'expenses', header: 'Expenses', align: 'right', hideOnMobile: true, render: (r) => fmt.usd(r.expenses) },
    { key: 'net_payout', header: 'Net payout', align: 'right', value: (r) => Number(r.net_payout), render: (r) => <b>{fmt.usd(r.net_payout, true)}</b> },
    { key: 'status', header: 'Status', render: (r) => <StatusPill status={r.status ?? 'DRAFT'} /> },
  ];
  return (<><PageHeader title="Owner statements" description="Rent collected, fees and repairs, with the net payout for each owner." actions={can('OWNER_STATEMENT:CREATE') && <><Button variant="secondary" onClick={() => setExp(true)}>Record expense</Button><Button variant="primary" onClick={() => setGen(true)}>Generate statement</Button></>} /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} onRowClick={setOpen} exportName="statements" /></Panel>
    <Drawer open={!!open} onClose={() => setOpen(null)} title={open ? `${open.owner_name} · ${open.period}` : ''}>
      {open && <div className="space-y-5"><KeyValues cols={2} items={[{ label: 'Rent collected', value: fmt.usd(open.rent_collected, true) }, { label: 'Fees collected', value: fmt.usd(open.fees_collected, true) }, { label: 'Management fee', value: fmt.usd(open.mgmt_fee, true) }, { label: 'Expenses', value: fmt.usd(open.expenses, true) }, { label: 'Net payout', value: <b>{fmt.usd(open.net_payout, true)}</b> }]} />
        <Panel title="Detail" padded={false}><ul className="divide-y divide-limestone-200 text-sm">{(open.lines ?? []).map((l: any, i: number) => <li key={i} className="flex justify-between gap-3 px-5 py-2"><span>{fmt.shortDate(l.date)} · {l.type} · {l.description}</span><Money value={l.amount} cents /></li>)}</ul></Panel>
        {can('OWNER_STATEMENT:CREATE') && open.status !== 'PUBLISHED' && <Button variant="primary" loading={publish.isPending} onClick={() => publish.mutate(open.id)}>Publish to owner</Button>}</div>}
    </Drawer>
    <Modal open={exp} onClose={() => setExp(false)} title="Record an expense" footer={<><Button variant="quiet" onClick={() => setExp(false)}>Cancel</Button><Button variant="primary" disabled={!e.propertyId || !e.description.trim() || !(Number(e.amount) > 0)} loading={addExpense.isPending} onClick={() => addExpense.mutate(undefined as never)}>Save</Button></>}>
      <div className="space-y-3"><Field label="Property" required><Select value={e.propertyId} onChange={(ev) => setE({ ...e, propertyId: ev.target.value })}><option value="">Choose a property</option>{(props ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></Field>
        <Field label="What was it for?" required><Input value={e.description} onChange={(ev) => setE({ ...e, description: ev.target.value })} /></Field>
        <div className="grid grid-cols-3 gap-3"><Field label="Category"><Select value={e.category} onChange={(ev) => setE({ ...e, category: ev.target.value })}><option value="REPAIRS">Repairs</option><option value="UTILITIES">Utilities</option><option value="INSURANCE">Insurance</option><option value="TAXES">Taxes</option><option value="LANDSCAPING">Landscaping</option><option value="OTHER">Other</option></Select></Field>
          <Field label="Amount (USD)" required><Input type="number" min="0" step="0.01" value={e.amount} onChange={(ev) => setE({ ...e, amount: ev.target.value })} /></Field>
          <Field label="Date"><Input type="date" value={e.date} onChange={(ev) => setE({ ...e, date: ev.target.value })} /></Field></div></div></Modal>
    <Modal open={gen} onClose={() => setGen(false)} title="Generate statement" footer={<><Button variant="quiet" onClick={() => setGen(false)}>Cancel</Button><Button variant="primary" disabled={!g.ownerId} loading={create.isPending} onClick={() => create.mutate(undefined)}>Generate</Button></>}>
      <div className="space-y-3"><Field label="Owner"><Select value={g.ownerId} onChange={(e) => setG({ ...g, ownerId: e.target.value })}><option value="">Choose an owner</option>{(owners ?? []).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</Select></Field>
        <Field label="Month"><Input type="month" value={g.period} onChange={(e) => setG({ ...g, period: e.target.value })} /></Field></div></Modal></>);
}

function Deposits() {
  const { can } = useSession();
  const { data, isLoading } = useApi<any[]>('/api/billing/deposits');
  const [d, setD] = useState<any>(null); const [ded, setDed] = useState({ description: '', amount: '' });
  const settle = useAction(() => api.post(`/api/billing/deposits/${d.id}/settle`, { deductions: ded.amount ? [{ description: ded.description || 'Deduction', amount: Number(ded.amount) }] : [] }), { success: 'Deposit settled', invalidate: inv, onSuccess: () => setD(null) });
  const cols: Column<any>[] = [
    { key: 'tenant_name', header: 'Resident', render: (r) => (<div><div className="font-semibold"><QuickLink kind="tenant" id={r.tenant_id} name={r.tenant_name}>{r.tenant_name}</QuickLink></div><div className="text-xs text-graphite-500">{r.unit_label}</div></div>) },
    { key: 'held', header: 'Held', align: 'right', value: (r) => Number(r.held), render: (r) => fmt.usd(r.held) },
    { key: 'status', header: 'Status', render: (r) => <StatusPill status={r.status} /> },
    { key: 'state', header: 'State' },
    { key: 'a', header: '', align: 'right', render: (r) => (can('PAYMENT:APPROVE') && r.status === 'REFUND_DUE' ? <Button size="sm" variant="primary" onClick={() => setD(r)}>Settle</Button> : null) },
  ];
  return (<><PageHeader title="Security deposits" description="Held in trust. Move-out refunds must follow your state’s deadline." /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(r) => r.id} loading={isLoading} /></Panel>
    <Modal open={!!d} onClose={() => setD(null)} title="Settle deposit" footer={<><Button variant="quiet" onClick={() => setD(null)}>Cancel</Button><Button variant="primary" loading={settle.isPending} onClick={() => settle.mutate(undefined)}>Settle and refund</Button></>}>
      <p className="mb-3 text-sm text-graphite-700">{d?.tenant_name} has {fmt.usd(d?.held)} on deposit. Itemise any lawful deduction; the rest is refunded.</p>
      <div className="grid grid-cols-[1fr_120px] gap-3"><Field label="Deduction"><Input value={ded.description} onChange={(e) => setDed({ ...ded, description: e.target.value })} placeholder="e.g. Carpet cleaning" /></Field><Field label="Amount"><Input type="number" value={ded.amount} onChange={(e) => setDed({ ...ded, amount: e.target.value })} /></Field></div></Modal></>);
}

function MyAccount() {
  const { data: m, isLoading } = useApi<any>('/api/billing/my');
  const [amt, setAmt] = useState(''); const [addM, setAddM] = useState(false); const [nm, setNm] = useState({ type: 'ACH', last4: '', autopay: false, consent: false });
  const methods: any[] = m?.methods ?? [];
  const [sel, setSel] = useState('');
  const chosen = methods.find((x) => x.id === (sel || methods[0]?.id));
  const payAmount = Number(amt || m?.balance || 0);
  const { data: quote } = useApi<any>(payAmount > 0 && chosen ? `/api/billing/payments/quote?amount=${payAmount}&method=${chosen.type}` : null);
  const pay = useAction(() => api.post<any>('/api/billing/payments', { amount: Number(amt || m.balance), method: chosen?.type ?? 'ACH', last4: chosen?.last4 ?? '6789' }), { success: (r) => `Payment received. Reference ${r.reference}`, invalidate: inv, onSuccess: () => setAmt('') });
  const auto = useAction((v: { id: string; on: boolean }) => api.put(`/api/billing/my/methods/${v.id}/autopay`, { on: v.on }), { success: 'Autopay updated', invalidate: inv });
  const add = useAction(() => api.post('/api/billing/my/methods', nm), { success: 'Payment method added', invalidate: inv, onSuccess: () => { setAddM(false); setNm({ type: 'ACH', last4: '', autopay: false, consent: false }); } });
  if (isLoading || !m) return <Skeleton className="h-96" />;
  const cols: Column<any>[] = [
    { key: 'due_date', header: 'Due', value: (r) => r.due_date, render: (r) => fmt.date(r.due_date) }, { key: 'description', header: 'Charge' },
    { key: 'status', header: 'Status', render: (r) => <StatusPill status={r.status} /> }, { key: 'balance', header: 'Balance', align: 'right', render: (r) => fmt.usd(r.balance, true) },
  ];
  return (<>
    <PageHeader title="Rent and payments" description="Pay rent, manage autopay and see your payment history." />
    <StatStrip className="mb-6"><Stat label="Balance due" value={fmt.usd(m.balance, true)} emphasis sub={m.nextDue ? `Next due ${fmt.date(m.nextDue.due_date)}` : 'You’re all paid up'} />
      <Stat label="Past due" value={fmt.usd(m.overdue, true)} /><Stat label="Autopay" value={methods.some((x) => x.autopay) ? 'On' : 'Off'} /></StatStrip>
    <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
      <div className="space-y-6">
        <Panel title="Charges" padded={false}><DataTable rows={m.invoices} columns={cols} rowKey={(r) => r.id} searchable={false} /></Panel>
        <Panel title="Payment history" padded={false}><DataTable rows={m.payments} columns={[
          { key: 'created_at', header: 'Date', render: (r: any) => fmt.date(r.created_at) }, { key: 'reference', header: 'Reference' }, { key: 'status', header: 'Status', render: (r: any) => <StatusPill status={r.status} /> },
          { key: 'amount', header: 'Amount', align: 'right', render: (r: any) => fmt.usd(r.amount, true) }]} rowKey={(r: any) => r.id} searchable={false} /></Panel>
      </div>
      <div className="space-y-6">
        <Panel title="Pay now" tone="tinted">
          {Number(m.balance) > 0 ? <div className="space-y-3">
            <Field label="Amount (USD)"><Input type="number" step="0.01" value={amt} placeholder={String(m.balance)} onChange={(e) => setAmt(e.target.value)} /></Field>
            <Field label="Pay with"><Select value={chosen?.id ?? ''} onChange={(e) => setSel(e.target.value)}>{methods.length === 0 && <option value="">Add a payment method first</option>}{methods.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</Select></Field>
            {quote && Number(quote.fee) > 0 && <div className="rounded border border-limestone-200 bg-white px-3 py-2 text-sm"><div className="flex justify-between"><span>Rent and charges</span><span>{fmt.usd(quote.amount, true)}</span></div><div className="flex justify-between text-graphite-600"><span>Processing fee</span><span>{fmt.usd(quote.fee, true)}</span></div><div className="mt-1 flex justify-between border-t border-limestone-200 pt-1 font-semibold"><span>Total charged</span><span>{fmt.usd(quote.total, true)}</span></div></div>}
            <Button variant="primary" className="w-full" size="lg" loading={pay.isPending} disabled={!methods.length} onClick={() => pay.mutate(undefined)}>Pay {fmt.usd(quote?.total ?? (amt || m.balance), true)}</Button>
          </div> : <p className="text-sm text-graphite-700">Nothing is due right now.</p>}
        </Panel>
        <Panel title="Payment methods" actions={<Button size="sm" onClick={() => setAddM(true)}>Add</Button>}>
          {methods.length === 0 ? <p className="text-sm text-graphite-500">No payment methods yet.</p> : <ul className="space-y-3">{methods.map((x) => <li key={x.id} className="flex items-center justify-between gap-3"><span className="text-sm font-medium">{x.label}</span><Toggle checked={x.autopay} onChange={(on) => auto.mutate({ id: x.id, on })} label="Autopay" /></li>)}</ul>}
        </Panel>
      </div>
    </div>
    <Modal open={addM} onClose={() => setAddM(false)} title="Add a payment method" footer={<><Button variant="quiet" onClick={() => setAddM(false)}>Cancel</Button><Button variant="primary" loading={add.isPending} disabled={nm.last4.length !== 4 || !nm.consent} onClick={() => add.mutate(undefined)}>Save</Button></>}>
      <div className="space-y-3"><Field label="Type"><Select value={nm.type} onChange={(e) => setNm({ ...nm, type: e.target.value })}><option value="ACH">Bank account (ACH)</option><option value="CARD">Debit or credit card</option></Select></Field>
        <Field label="Last 4 digits" hint="Demo gateway: numbers ending 0002 are declined, to show failure handling."><Input maxLength={4} value={nm.last4} onChange={(e) => setNm({ ...nm, last4: e.target.value.replace(/\D/g, '') })} /></Field>
        <Toggle checked={nm.autopay} onChange={(v) => setNm({ ...nm, autopay: v })} label="Use for autopay" />
        <label className="flex items-start gap-2 rounded border border-limestone-200 p-3 text-xs text-graphite-700"><input type="checkbox" className="mt-0.5" checked={nm.consent} onChange={(e) => setNm({ ...nm, consent: e.target.checked })} />
          <span>I authorise my property manager to {nm.type === 'ACH' ? 'debit this bank account electronically (ACH)' : 'charge this card'} for rent and other charges I owe{nm.autopay ? ', automatically on the due date' : ' when I choose to pay'}. I can revoke this at any time in Payment methods.</span></label></div></Modal>
  </>);
}

function TaxCompliance() {
  const { can } = useSession();
  const [tab, setTab] = useState<'tax' | '1099' | 'rules'>('tax');
  const year = new Date().getFullYear();
  const [y, setY] = useState(year);
  const { data: t } = useApi<any>(`/api/billing/tax/summary?year=${y}`);
  const { data: f } = useApi<any>(can('TAX_REPORT:VIEW') ? `/api/billing/tax/1099?year=${y}` : null);
  const { data: cfg } = useApi<any>(can('SETTINGS:VIEW') ? '/api/identity/config' : null);
  const [w9, setW9] = useState<any>(null);
  const save = useAction(() => api.put(`/api/billing/tax/owners/${w9.owner_id}/w9`, { legalName: w9.legal_name, tinType: w9.tin_type, tinLast4: w9.tin_last4, w9Received: true }), { success: 'W-9 recorded', invalidate: inv, onSuccess: () => setW9(null) });
  const taxCols: Column<any>[] = [
    { key: 'month', header: 'Month' }, { key: 'state', header: 'State' },
    { key: 'rate', header: 'Rate', align: 'right', render: (r) => `${Number(r.rate ?? 0)}%` },
    { key: 'taxable_base', header: 'Taxable charges', align: 'right', value: (r) => Number(r.taxable_base ?? 0), render: (r) => fmt.usd(r.taxable_base ?? 0, true) },
    { key: 'non_taxable', header: 'Non-taxable charges', align: 'right', hideOnMobile: true, value: (r) => Number(r.non_taxable ?? 0), render: (r) => fmt.usd(r.non_taxable ?? 0, true) },
    { key: 'tax_invoiced', header: 'Tax billed', align: 'right', value: (r) => Number(r.tax_invoiced ?? 0), render: (r) => fmt.usd(r.tax_invoiced ?? 0, true) },
    { key: 'tax_collected', header: 'Tax collected', align: 'right', value: (r) => Number(r.tax_collected ?? 0), render: (r) => fmt.usd(r.tax_collected ?? 0, true) },
  ];
  const ownerCols: Column<any>[] = [
    { key: 'owner_name', header: 'Owner', render: (r) => <span className="font-semibold">{r.owner_name}</span> },
    { key: 'gross_collected', header: 'Rents collected', align: 'right', value: (r) => Number(r.gross_collected), render: (r) => fmt.usd(r.gross_collected, true) },
    { key: 'net_paid', header: 'Net paid out', align: 'right', hideOnMobile: true, value: (r) => Number(r.net_paid), render: (r) => fmt.usd(r.net_paid, true) },
    { key: 'reportable', header: '1099 needed', value: (r) => (r.reportable ? 'Yes' : 'No'), render: (r) => <Badge tone={r.reportable ? 'warn' : 'neutral'}>{r.reportable ? 'Yes' : 'Below threshold'}</Badge> },
    { key: 'w9_on_file', header: 'W-9', value: (r) => (r.w9_on_file ? 'On file' : 'Missing'), render: (r) => r.w9_on_file ? <Badge tone="good">On file{r.tin_last4 ? ` ····${r.tin_last4}` : ''}</Badge> : <Badge tone="crit">Missing</Badge> },
    { key: 'backup_withholding_due', header: 'Backup withholding', align: 'right', hideOnMobile: true, value: (r) => Number(r.backup_withholding_due), render: (r) => Number(r.backup_withholding_due) > 0 ? fmt.usd(r.backup_withholding_due, true) : '—' },
    { key: 'owner_id', header: '', sortable: false, render: (r) => can('TAX_REPORT:EDIT') && <Button size="sm" variant="quiet" onClick={() => setW9({ ...r, tin_type: r.tin_type ?? 'SSN' })}>{r.w9_on_file ? 'Update' : 'Record W-9'}</Button> },
  ];
  const rules: any[] = cfg?.stateRules ? Object.entries<any>(cfg.stateRules).map(([k, v]) => ({ code: k, ...v })) : [];
  const lim = (v: any, suffix = '') => (v === null || v === undefined ? 'No cap' : `${v}${suffix}`);
  const ruleCols: Column<any>[] = [
    { key: 'name', header: 'State', render: (r) => <span className="font-semibold">{r.name} {r.code !== 'DEFAULT' && <span className="text-graphite-500">({r.code})</span>}</span> },
    { key: 'lateFeeGraceDays', header: 'Late fee grace', align: 'right', render: (r) => `${r.lateFeeGraceDays} days` },
    { key: 'lateFeeCapPercent', header: 'Late fee cap', align: 'right', render: (r) => `${lim(r.lateFeeCapPercent, '%')}${r.lateFeeCapFlat != null ? ` / $${r.lateFeeCapFlat}` : ''}` },
    { key: 'depositCapMonths', header: 'Deposit cap', align: 'right', hideOnMobile: true, render: (r) => r.depositCapMonths == null ? 'No cap' : `${r.depositCapMonths} mo` },
    { key: 'depositRefundDays', header: 'Deposit refund', align: 'right', hideOnMobile: true, render: (r) => `${r.depositRefundDays} days` },
    { key: 'noticeDays', header: 'Notice', align: 'right', hideOnMobile: true, render: (r) => `${r.noticeDays} days` },
    { key: 'nsfFeeCap', header: 'Returned-payment cap', align: 'right', hideOnMobile: true, render: (r) => r.nsfFeeCap == null ? 'No cap' : `$${r.nsfFeeCap}` },
  ];
  return (<>
    <PageHeader title="Tax and compliance" description="Sales tax billed and collected, owner 1099 preparation and the legal limits PropertyOS applies by state."
      actions={<Select value={y} onChange={(e) => setY(Number(e.target.value))} aria-label="Tax year">{[year, year - 1, year - 2].map((v) => <option key={v} value={v}>{v}</option>)}</Select>} />
    <Tabs tabs={[{ key: 'tax', label: 'Sales tax' }, { key: '1099', label: 'Owner 1099s', count: f?.missingW9 || undefined }, { key: 'rules', label: 'State rules' }]} value={tab} onChange={setTab} className="mb-6" />
    {tab === 'tax' && (!t ? <Skeleton className="h-40" /> : <div className="space-y-6">
      <StatStrip><Stat label="Tax billed" value={fmt.usd(t.taxInvoiced, true)} emphasis /><Stat label="Tax collected" value={fmt.usd(t.taxCollected, true)} /><Stat label="Still to collect" value={fmt.usd(Number(t.taxInvoiced) - Number(t.taxCollected), true)} /></StatStrip>
      {!t.taxEnabled || Number(t.taxInvoiced) === 0 ? <div className="rounded-md border border-limestone-200 bg-limestone-50 p-4 text-sm text-graphite-700">No tax has been charged in {t.year}. Residential rent isn’t taxed in most states, so every rate starts at 0%. If your company must collect tax in a state, set the rate and the charge types under Administration, Configuration, Payments and tax.</div> : null}
      <Panel title="Tax by month and state" padded={false}><DataTable rows={t.rows} columns={taxCols} rowKey={(r) => r.month + r.state} exportName={`sales-tax-${y}`} searchable={false} /></Panel></div>)}
    {tab === '1099' && (!f ? <Skeleton className="h-40" /> : <div className="space-y-6">
      <StatStrip><Stat label="Owners paid" value={f.owners.length} emphasis /><Stat label="1099-MISC needed" value={f.owners.filter((o: any) => o.reportable).length} sub={`At ${fmt.usd(f.threshold)} or more`} /><Stat label="W-9 missing" value={f.missingW9} sub={f.missingW9 ? `${f.backupWithholdingPercent}% backup withholding applies` : 'All set'} /></StatStrip>
      <p className="text-sm text-graphite-600">This is a preparation worksheet from published owner statements, not a filed return. Collect each owner’s W-9 before the first payout and confirm figures with your tax professional. Only the last 4 digits of the tax ID are kept here. Forms are due to owners by January 31.</p>
      <Panel title={`Owner payments in ${f.year}`} padded={false}><DataTable rows={f.owners} columns={ownerCols} rowKey={(r) => r.owner_id} exportName={`owner-1099-${y}`} searchable={false} /></Panel></div>)}
    {tab === 'rules' && <div className="space-y-4">
      <p className="text-sm text-graphite-600">Late fees, grace periods, deposits and returned-payment fees are limited to these values when invoices are created. An administrator can change them under Administration, Configuration, State rules. Residential-tenancy law varies by state and city, so have counsel confirm them.</p>
      <Panel padded={false}>{cfg ? <DataTable rows={rules} columns={ruleCols} rowKey={(r) => r.code} searchable={false} /> : <p className="p-4 text-sm text-graphite-500">You don’t have access to view these settings.</p>}</Panel></div>}
    <Modal open={!!w9} onClose={() => setW9(null)} title={`W-9 for ${w9?.owner_name ?? ''}`} footer={<><Button variant="quiet" onClick={() => setW9(null)}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!w9?.legal_name || String(w9?.tin_last4 ?? '').length !== 4} onClick={() => save.mutate(undefined)}>Save</Button></>}>
      {w9 && <div className="space-y-3">
        <Field label="Name as shown on the W-9" required><Input value={w9.legal_name ?? ''} onChange={(e) => setW9({ ...w9, legal_name: e.target.value })} /></Field>
        <Field label="Tax ID type"><Select value={w9.tin_type} onChange={(e) => setW9({ ...w9, tin_type: e.target.value })}><option value="SSN">Social Security number</option><option value="EIN">Employer ID number</option></Select></Field>
        <Field label="Last 4 digits of the tax ID" required hint="Keep the signed W-9 in your document system. The full number is not stored here."><Input maxLength={4} value={w9.tin_last4 ?? ''} onChange={(e) => setW9({ ...w9, tin_last4: e.target.value.replace(/\D/g, '') })} /></Field>
      </div>}
    </Modal>
  </>);
}

function Home() {
  const { session } = useSession();
  return personaOf(session) === 'tenant' ? <MyAccount /> : <Overview />;
}

export default function App() {
  return (
    <MicroApp name="payments">
      <Routes>
        <Route index element={<Home />} />
        <Route path="invoices" element={<Invoices />} />
        <Route path="collections" element={<Collections />} />
        <Route path="payments" element={<Payments />} />
        <Route path="statements" element={<Statements />} />
        <Route path="deposits" element={<Deposits />} />
        <Route path="tax" element={<TaxCompliance />} />
      </Routes>
    </MicroApp>
  );
}
