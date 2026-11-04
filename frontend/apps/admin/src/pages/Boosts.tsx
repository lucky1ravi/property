import { useState } from 'react';
import { Gift, Plus } from 'lucide-react';
import { PageHeader, Panel, DataTable, type Column, useApi, usePagedApi, useAction, api, fmt, Button, Badge, Stat, StatStrip, Tabs, Select, Modal, Field, Input, Textarea, EntityPicker } from '@propertyos/ui';

/**
 * Listing boosts, from the operator's side: every company's boosts, free grants, ending a boost, the prices agreed with individual companies and
 * promo codes. Standard prices, plan discounts, the ranking bonus and the page cap are product defaults ("Listing boosts"); the feature itself is a
 * plan feature (BOOST) that is switched per plan on Plans, or per company on its Features tab.
 */

const TONE: Record<string, 'good' | 'warn' | 'neutral' | 'brand'> = { ACTIVE: 'good', PENDING: 'warn', EXPIRED: 'neutral' };
const LABEL: Record<string, string> = { ACTIVE: 'Running', PENDING: 'Waiting for payment', EXPIRED: 'Over' };

function Grant({ onClose }: { onClose: () => void }) {
  const [listing, setListing] = useState<string | null>(null);
  const [days, setDays] = useState('7');
  const [reason, setReason] = useState('');
  const go = useAction(() => api.post('/api/leasing/platform/boosts/grant', { listingId: listing, days: Number(days), reason }), {
    success: 'Boost granted. The company has been told.', invalidate: ['/api/leasing/platform/boosts'], onSuccess: onClose,
  });
  return (<Modal open onClose={onClose} title="Grant a free boost" footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={go.isPending} disabled={!listing || !reason.trim() || !(Number(days) >= 1)} onClick={() => go.mutate(undefined)}>Grant boost</Button></>}>
    <div className="space-y-3">
      <Field label="Live listing" required hint="Only live listings without a boost can be chosen."><EntityPicker<any> endpoint="/api/leasing/platform/boosts/eligible" value={listing} onChange={(id) => setListing(id)} label={(l) => l.title} sub={(l) => l.company_name} placeholder="Search listings" /></Field>
      <Field label="Days" required hint="1 to 90. It starts now."><Input type="number" min={1} max={90} value={days} onChange={(e) => setDays(e.target.value)} /></Field>
      <Field label="Reason" required hint="Kept in the audit trail."><Textarea rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
    </div>
  </Modal>);
}

function Cancel({ boost, onClose }: { boost: any; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const go = useAction(() => api.post<any>(`/api/leasing/platform/boosts/${boost.id}/cancel`, { reason }), {
    success: (r: any) => (r.paid ? 'Boost ended. It was paid for, so any refund is yours to arrange on the billing side.' : 'Boost cancelled'), invalidate: ['/api/leasing/platform/boosts', '/api/identity/platform/billing'], onSuccess: onClose,
  });
  return (<Modal open onClose={onClose} title="End this boost?" footer={<><Button variant="quiet" onClick={onClose}>Keep it</Button><Button variant="danger" loading={go.isPending} disabled={!reason.trim()} onClick={() => go.mutate(undefined)}>End boost</Button></>}>
    <p className="mb-3 text-sm text-graphite-700">{boost.company_name}: {boost.listing_title}. {boost.status === 'PENDING' ? 'It has not been paid for; its invoice will be voided.' : Number(boost.amount) > 0 ? 'It has been paid for. Ending it does not refund the company automatically.' : 'It was a free grant.'}</p>
    <Field label="Reason" required hint="The company sees this."><Textarea rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
  </Modal>);
}

function SetPrice({ onClose }: { onClose: () => void }) {
  const [org, setOrg] = useState<string | null>(null);
  const [days, setDays] = useState('7');
  const [price, setPrice] = useState('');
  const [note, setNote] = useState('');
  const go = useAction(() => api.put('/api/leasing/platform/boosts/prices', { orgId: org, days: Number(days), price: Number(price), note }), {
    success: 'Price saved', invalidate: ['/api/leasing/platform/boosts/prices'], onSuccess: onClose,
  });
  return (<Modal open onClose={onClose} title="Set a company's price" footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={go.isPending} disabled={!org || !(Number(price) > 0) || !(Number(days) >= 1)} onClick={() => go.mutate(undefined)}>Save price</Button></>}>
    <div className="space-y-3">
      <Field label="Company" required><EntityPicker<any> endpoint="/api/identity/platform/usage" value={org} onChange={(id) => setOrg(id)} label={(o) => o.name} sub={(o) => `${o.plan} plan`} placeholder="Search companies" /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Boost length (days)" required hint="Use one of the lengths on offer."><Input type="number" min={1} value={days} onChange={(e) => setDays(e.target.value)} /></Field>
        <Field label="Price ($)" required hint="Replaces the standard price and any plan discount for this company."><Input type="number" min={0} step="0.01" value={price} onChange={(e) => setPrice(e.target.value)} /></Field>
      </div>
      <Field label="Note" hint="Why this price was agreed."><Input maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
    </div>
  </Modal>);
}

function NewCode({ onClose }: { onClose: () => void }) {
  const [f, setF] = useState<any>({ code: '', kind: 'percent', value: '', org: null, validUntil: '', maxUses: '', note: '' });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const go = useAction(() => api.post('/api/leasing/platform/boosts/promos', {
    code: f.code, ...(f.kind === 'percent' ? { percentOff: Number(f.value) } : { amountOff: Number(f.value) }), orgId: f.org || undefined,
    validUntil: f.validUntil || undefined, maxUses: f.maxUses ? Number(f.maxUses) : undefined, note: f.note || undefined,
  }), { success: 'Code created', invalidate: ['/api/leasing/platform/boosts/promos'], onSuccess: onClose });
  return (<Modal open onClose={onClose} title="New promo code" footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={go.isPending} disabled={f.code.trim().length < 3 || !(Number(f.value) > 0)} onClick={() => go.mutate(undefined)}>Create code</Button></>}>
    <div className="space-y-3">
      <Field label="Code" required hint="Letters, digits, - and _. Not case sensitive."><Input value={f.code} maxLength={40} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Takes off" required><Select value={f.kind} onChange={set('kind')}><option value="percent">A percentage</option><option value="amount">A dollar amount</option></Select></Field>
        <Field label={f.kind === 'percent' ? 'Percent off' : 'Dollars off'} required><Input type="number" min={0} value={f.value} onChange={set('value')} /></Field>
      </div>
      <Field label="Only for one company" hint="Leave empty for any company."><EntityPicker<any> endpoint="/api/identity/platform/usage" value={f.org} onChange={(id) => setF({ ...f, org: id })} label={(o) => o.name} sub={(o) => `${o.plan} plan`} placeholder="Any company" /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Last day to use"><Input type="date" value={f.validUntil} onChange={set('validUntil')} /></Field>
        <Field label="Most uses" hint="Empty for no limit."><Input type="number" min={1} value={f.maxUses} onChange={set('maxUses')} /></Field>
      </div>
      <Field label="Note"><Input maxLength={200} value={f.note} onChange={set('note')} /></Field>
    </div>
  </Modal>);
}

export function Boosts() {
  const [tab, setTab] = useState<'boosts' | 'pricing'>('boosts');
  const [granting, setGranting] = useState(false);
  const [ending, setEnding] = useState<any>(null);
  const [pricing, setPricing] = useState(false);
  const [coding, setCoding] = useState(false);
  const { data: s } = useApi<any>('/api/leasing/platform/boosts/overview');
  const { data: billing } = useApi<any>('/api/identity/platform/billing/summary');
  const boosts = usePagedApi<any>('/api/leasing/platform/boosts');
  const { data: prices, refetch: refetchPrices } = useApi<any[]>('/api/leasing/platform/boosts/prices');
  const { data: promos, refetch: refetchPromos } = useApi<any[]>('/api/leasing/platform/boosts/promos');
  const unprice = useAction((p: any) => api.del(`/api/leasing/platform/boosts/prices?orgId=${p.org_id}&days=${p.days}`), { success: 'Back to the standard price', onSuccess: () => refetchPrices() });
  const toggle = useAction((p: { code: string; active: boolean }) => api.put(`/api/leasing/platform/boosts/promos/${encodeURIComponent(p.code)}`, { active: p.active }), { success: 'Saved', onSuccess: () => refetchPromos() });

  const cols: Column<any>[] = [
    { key: 'company_name', header: 'Company', sortable: false },
    { key: 'listing_title', header: 'Listing', render: (b) => <span className="line-clamp-2">{b.listing_title}</span> },
    { key: 'days', header: 'Length', align: 'right', render: (b) => `${b.days} days` },
    { key: 'amount', header: 'Paid', align: 'right', value: (b) => Number(b.amount), render: (b) => (
      <div className="text-right">{b.source === 'GRANT' ? <Badge tone="brand">Free grant</Badge> : <span className="font-semibold">{fmt.usd(b.amount, true)}</span>}
        {b.promo_code && <div className="text-2xs text-graphite-500">Code {b.promo_code}</div>}
        {b.source === 'GRANT' && b.grant_reason && <div className="text-2xs text-graphite-500">{b.grant_reason}</div>}
        {b.discount_note && <div className="max-w-[18rem] text-2xs text-graphite-500">{b.discount_note}</div>}</div>) },
    { key: 'status', header: 'Status', render: (b) => <Badge tone={TONE[b.status] ?? 'neutral'}>{LABEL[b.status] ?? b.status}</Badge> },
    { key: 'ends_at', header: 'Ends', render: (b) => (b.status === 'PENDING' ? <span className="text-graphite-500">When paid</span> : fmt.date(b.ends_at)) },
    { key: 'actions', header: '', sortable: false, render: (b) => (b.status === 'EXPIRED' ? null : <Button size="sm" variant="quiet" aria-label={`End the boost for ${b.listing_title}`} onClick={() => setEnding(b)}>End</Button>) },
  ];
  const priceCols: Column<any>[] = [
    { key: 'company_name', header: 'Company' }, { key: 'days', header: 'Length', align: 'right', render: (p) => `${p.days} days` },
    { key: 'price', header: 'Price', align: 'right', render: (p) => fmt.usd(p.price, true) }, { key: 'note', header: 'Note', hideOnMobile: true },
    { key: 'x', header: '', sortable: false, render: (p) => <Button size="sm" variant="quiet" aria-label={`Remove the agreed price for ${p.company_name}`} onClick={() => unprice.mutate(p)}>Remove</Button> },
  ];
  const codeCols: Column<any>[] = [
    { key: 'code', header: 'Code', render: (c) => <span className="font-mono font-semibold">{c.code}</span> },
    { key: 'off', header: 'Takes off', render: (c) => (c.percent_off ? `${c.percent_off}%` : fmt.usd(c.amount_off, true)) },
    { key: 'company_name', header: 'For' },
    { key: 'uses', header: 'Used', align: 'right', render: (c) => `${c.uses}${c.max_uses ? ` of ${c.max_uses}` : ''}` },
    { key: 'valid_until', header: 'Until', render: (c) => (c.valid_until ? fmt.date(c.valid_until) : 'No end') },
    { key: 'active', header: 'State', render: (c) => <Badge tone={c.active ? 'good' : 'neutral'}>{c.active ? 'On' : 'Off'}</Badge> },
    { key: 'x', header: '', sortable: false, render: (c) => <Button size="sm" variant="quiet" aria-label={`${c.active ? 'Switch off' : 'Switch on'} code ${c.code}`} onClick={() => toggle.mutate({ code: c.code, active: !c.active })}>{c.active ? 'Switch off' : 'Switch on'}</Button> },
  ];

  return (<>
    <PageHeader title="Listing boosts" description="Boosts across every company, free grants, agreed prices and promo codes. Standard prices, plan discounts and the ranking bonus are under Product defaults." actions={<Button icon={<Gift size={14} />} variant="primary" onClick={() => setGranting(true)}>Grant a free boost</Button>} />
    <StatStrip className="mb-6">
      <Stat label="Running now" value={s?.active ?? 0} emphasis sub={`${s?.granted_active ?? 0} free grants`} />
      <Stat label="Waiting for payment" value={s?.pending ?? 0} />
      <Stat label="Ending within 3 days" value={s?.ending_soon ?? 0} />
      <Stat label="Boost revenue this month" value={fmt.usd(billing?.boostRevenueThisMonth ?? 0, true)} />
    </StatStrip>
    <Tabs tabs={[{ key: 'boosts', label: 'Boosts', count: boosts.total }, { key: 'pricing', label: 'Prices and codes' }]} value={tab} onChange={setTab} className="mb-4" />
    {tab === 'boosts' ? <Panel padded={false}><DataTable rows={boosts.rows} server={boosts.server} columns={cols} rowKey={(b) => b.id} loading={boosts.isLoading} empty={{ title: 'No boosts yet', body: 'When a company buys a boost, or you grant one, it appears here.' }} /></Panel> : (<div className="space-y-6">
      <Panel title="Prices agreed with companies" description="Replaces the standard price, and the plan discount, for that company and length." padded={false} actions={<Button size="sm" icon={<Plus size={14} />} onClick={() => setPricing(true)}>Set a price</Button>}>
        {(prices ?? []).length === 0 ? <p className="p-5 text-sm text-graphite-600">Every company pays the standard price for its plan.</p> : <DataTable rows={prices ?? []} columns={priceCols} rowKey={(p) => `${p.org_id}-${p.days}`} />}
      </Panel>
      <Panel title="Promo codes" description="One code per purchase, applied after the plan discount. A boost bought with discounts never costs less than $1." padded={false} actions={<Button size="sm" icon={<Plus size={14} />} onClick={() => setCoding(true)}>New code</Button>}>
        {(promos ?? []).length === 0 ? <p className="p-5 text-sm text-graphite-600">No codes yet.</p> : <DataTable rows={promos ?? []} columns={codeCols} rowKey={(c) => c.code} />}
      </Panel>
    </div>)}
    {granting && <Grant onClose={() => setGranting(false)} />}
    {ending && <Cancel boost={ending} onClose={() => setEnding(null)} />}
    {pricing && <SetPrice onClose={() => { setPricing(false); void refetchPrices(); }} />}
    {coding && <NewCode onClose={() => { setCoding(false); void refetchPromos(); }} />}
  </>);
}
