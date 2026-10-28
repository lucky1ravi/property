import { useEffect, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Mail, Phone } from 'lucide-react';
import { Drawer } from '../components/overlay';
import { KeyValues, Panel } from '../components/layout';
import { Badge, Button, Skeleton, StatusPill } from '../components/primitives';
import { useApi } from '../lib/hooks';
import { useSession } from '../lib/session';
import { fmt } from '../lib/format';
import { QUICKVIEW_EVENT, QuickLink, type QuickTarget } from './QuickLink';

const OPEN_STATUSES = ['COMPLETED', 'CLOSED', 'CANCELLED', 'VERIFIED'];
const take = <T,>(rows: T[] | undefined, n = 5) => (rows ?? []).slice(0, n);

/** Mounted once in the shell. Listens for quick-view requests from any module and shows the matching panel. */
export function QuickViewHost() {
  const [stack, setStack] = useState<QuickTarget[]>([]);
  const loc = useLocation();
  useEffect(() => {
    const on = (e: Event) => setStack((s) => [...s, (e as CustomEvent<QuickTarget>).detail]);
    window.addEventListener(QUICKVIEW_EVENT, on);
    return () => window.removeEventListener(QUICKVIEW_EVENT, on);
  }, []);
  useEffect(() => setStack([]), [loc.pathname]);
  const top = stack[stack.length - 1];
  if (!top) return null;
  const close = () => setStack([]);
  const back = stack.length > 1 ? () => setStack((s) => s.slice(0, -1)) : undefined;
  const props = { key: `${top.kind}:${top.id}`, id: top.id, name: top.name, onClose: close, onBack: back };
  switch (top.kind) {
    case 'property': return <PropertyPanel {...props} />;
    case 'owner': return <OwnerPanel {...props} />;
    case 'unit': return <UnitPanel {...props} />;
    case 'tenant': return <TenantPanel {...props} />;
    case 'vendor': return <VendorPanel {...props} />;
    case 'workorder': return <WorkOrderPanel {...props} />;
    case 'invoice': return <InvoicePanel {...props} />;
    case 'payment': return <PaymentPanel {...props} />;
    case 'applicant': return <ApplicantPanel {...props} />;
  }
}

interface PanelProps { id: string; name?: string; onClose: () => void; onBack?: () => void }

function Shell({ title, subtitle, onClose, onBack, openPath, openLabel = 'Open full record', width = 640, children }: {
  title: ReactNode; subtitle?: ReactNode; onClose: () => void; onBack?: () => void; openPath?: string; openLabel?: string; width?: number; children: ReactNode;
}) {
  const nav = useNavigate();
  return (
    <Drawer open onClose={onClose} width={width} title={title} subtitle={subtitle}
      footer={openPath ? <Button variant="primary" onClick={() => { onClose(); nav(openPath); }}>{openLabel}</Button> : undefined}>
      {onBack && <button onClick={onBack} className="mb-3 text-sm font-semibold text-federal-800 hover:underline">← Back</button>}
      <div className="space-y-5">{children}</div>
    </Drawer>
  );
}

const Loading = () => <Skeleton className="h-64" />;
const Missing = ({ what }: { what: string }) => <p className="text-sm text-graphite-600">This {what} isn’t available in your view, or it was removed.</p>;
const Contact = ({ email, phone }: { email?: string | null; phone?: string | null }) => (
  <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-graphite-700">
    {email && <a className="inline-flex items-center gap-1.5 hover:underline" href={`mailto:${email}`}><Mail className="h-4 w-4 text-graphite-500" />{email}</a>}
    {phone && <a className="inline-flex items-center gap-1.5 hover:underline" href={`tel:${phone}`}><Phone className="h-4 w-4 text-graphite-500" />{phone}</a>}
  </div>
);
const Row = ({ children }: { children: ReactNode }) => <li className="flex items-center justify-between gap-3 px-5 py-3 text-sm">{children}</li>;
const Empty = ({ children }: { children: ReactNode }) => <li className="px-5 py-4 text-sm text-graphite-500">{children}</li>;

/** Fetch a list only when the person has the permission for it. */
function useList<T = any>(path: string, perm: string) {
  const { can } = useSession();
  return useApi<T[]>(can(perm) ? path : null);
}

// ---------------- Property and owner ----------------
function PropertyPanel({ id, onClose, onBack }: PanelProps) {
  const { data: p } = useApi<any>(`/api/leasing/properties/${id}`);
  const { data: list } = useApi<any[]>('/api/leasing/properties');
  const stats = list?.find((x) => x.id === id);
  return (
    <Shell title={p?.name ?? 'Property'} subtitle={p ? `${p.street}, ${p.city}, ${p.state} ${p.zip}` : undefined} onClose={onClose} onBack={onBack} width={680} openPath="/app/leasing/listings" openLabel="View listings">
      {!p ? <Loading /> : (<>
        {p.photo_url && <img src={p.photo_url} alt="" className="h-44 w-full rounded-md object-cover" loading="lazy" />}
        <Panel title="At a glance">
          <KeyValues cols={3} items={[
            { label: 'Type', value: fmt.title(p.type) }, { label: 'Year built', value: p.year_built }, { label: 'Status', value: <StatusPill status={p.status} /> },
            { label: 'Units', value: stats?.units ?? p.units.length }, { label: 'Occupied', value: stats?.occupied }, { label: 'Vacant', value: stats?.vacant ? <Badge tone="warn">{stats.vacant}</Badge> : '0' },
            { label: 'Rent roll', value: stats ? fmt.usd(stats.rent_roll) : '—' }, { label: 'Market rent', value: stats ? fmt.usd(stats.market_rent_total) : '—' }, { label: 'Live listings', value: stats?.live_listings },
          ]} />
        </Panel>
        <Panel title="Owner">
          <QuickLink kind="owner" id={p.owner_id}>{p.owner_name ?? 'No owner'}</QuickLink>
          <div className="mt-1"><Contact email={p.owner_email} phone={p.owner_phone} /></div>
        </Panel>
        {Array.isArray(p.amenities) && p.amenities.length > 0 && <div className="flex flex-wrap gap-1.5">{p.amenities.map((a: string) => <Badge key={a}>{a}</Badge>)}</div>}
        <Panel title={`Units (${p.units.length})`} padded={false}>
          <ul className="divide-y divide-limestone-200">
            {p.units.map((u: any) => (
              <Row key={u.id}>
                <div className="min-w-0">
                  <QuickLink kind="unit" id={u.id}>Unit {u.number}</QuickLink> <span className="text-graphite-500">· {u.beds} bd, {u.baths} ba, {u.sqft} sqft</span>
                  <div className="truncate text-xs text-graphite-500">{u.tenant_name ? `${u.tenant_name}${u.lease_end ? `, lease ends ${fmt.date(u.lease_end)}` : ''}` : 'No resident'}</div>
                </div>
                <div className="flex shrink-0 items-center gap-3"><span className="tabular-nums">{fmt.usd(u.current_rent ?? u.market_rent)}</span><StatusPill status={u.status} /></div>
              </Row>
            ))}
          </ul>
        </Panel>
      </>)}
    </Shell>
  );
}

function OwnerPanel({ id, onClose, onBack }: PanelProps) {
  const { data: owners } = useApi<any[]>('/api/leasing/owners');
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const o = owners?.find((x) => x.id === id);
  const mine = (props ?? []).filter((p) => p.owner_id === id);
  return (
    <Shell title={o?.name ?? 'Owner'} subtitle={o?.contact && o.contact !== o.name ? `Contact: ${o.contact}` : undefined} onClose={onClose} onBack={onBack} width={560}>
      {!owners ? <Loading /> : !o ? <Missing what="owner" /> : (<>
        <Panel title="Contact"><Contact email={o.email} phone={o.phone} /></Panel>
        <Panel title="Terms">
          <KeyValues cols={2} items={[
            { label: 'Repairs they can approve up to', value: fmt.usd(o.approval_limit) }, { label: 'Management fee', value: `${o.management_fee_pct}%` },
            { label: 'Properties', value: o.properties }, { label: 'Units', value: o.units },
            ...(o.bank_last4 !== undefined ? [{ label: 'Payout account', value: o.bank_last4 ?? 'Not set' }] : []),
          ]} />
        </Panel>
        <Panel title={`Properties (${mine.length})`} padded={false}>
          <ul className="divide-y divide-limestone-200">
            {mine.map((p) => (<Row key={p.id}><div className="min-w-0"><QuickLink kind="property" id={p.id}>{p.name}</QuickLink><div className="truncate text-xs text-graphite-500">{p.city}, {p.state} · {p.occupied}/{p.units} occupied</div></div><span className="tabular-nums">{fmt.usd(p.rent_roll)}</span></Row>))}
            {mine.length === 0 && <Empty>No properties in your view.</Empty>}
          </ul>
        </Panel>
      </>)}
    </Shell>
  );
}

// ---------------- Unit ----------------
function UnitPanel({ id, onClose, onBack }: PanelProps) {
  const { data: units } = useApi<any[]>('/api/leasing/units');
  const u = units?.find((x) => x.id === id);
  const { data: prop } = useApi<any>(u ? `/api/leasing/properties/${u.property_id}` : null);
  const row = prop?.units?.find((x: any) => x.id === id);
  const nav = useNavigate();
  return (
    <Shell title={u ? `Unit ${u.number}` : 'Unit'} subtitle={u ? <QuickLink kind="property" id={u.property_id}>{u.property_name}</QuickLink> : undefined} onClose={onClose} onBack={onBack} width={560}>
      {!units ? <Loading /> : !u ? <Missing what="unit" /> : (<>
        <Panel title="Details">
          <KeyValues cols={3} items={[
            { label: 'Status', value: <StatusPill status={u.status} /> }, { label: 'Bedrooms', value: u.beds }, { label: 'Bathrooms', value: u.baths },
            { label: 'Size', value: `${u.sqft} sqft` }, { label: 'Market rent', value: fmt.usd(u.market_rent) }, { label: 'Current rent', value: u.current_rent ? fmt.usd(u.current_rent) : '—' },
          ]} />
          {u.current_rent && u.market_rent > u.current_rent && <p className="mt-3 text-sm text-graphite-600">Rent is {fmt.usd(u.market_rent - u.current_rent)} a month below market.</p>}
        </Panel>
        <Panel title="Resident and listing">
          {!row ? <p className="text-sm text-graphite-500">Loading…</p> : (
            <div className="space-y-2 text-sm">
              <div>{row.tenant_name ? <>Resident: <b>{row.tenant_name}</b>{row.lease_end && <span className="text-graphite-500">, lease ends {fmt.date(row.lease_end)}</span>}</> : 'No resident right now.'}</div>
              <div className="flex flex-wrap gap-2">
                {row.lease_id && <Button size="sm" variant="secondary" onClick={() => { onClose(); nav(`/app/leasing/leases/${row.lease_id}`); }}>Open lease</Button>}
                {row.listing_id && <Button size="sm" variant="secondary" onClick={() => { onClose(); nav(`/app/leasing/listings/${row.listing_id}`); }}>Open listing{row.listing_status ? ` (${fmt.title(row.listing_status)})` : ''}</Button>}
              </div>
            </div>
          )}
        </Panel>
      </>)}
    </Shell>
  );
}

// ---------------- Tenant ----------------
function TenantPanel({ id, name, onClose, onBack }: PanelProps) {
  const { data: leases } = useList('/api/leasing/leases', 'LEASE:VIEW');
  const { data: invoices } = useList('/api/billing/invoices', 'INVOICE:VIEW');
  const { data: payments } = useList('/api/billing/payments', 'PAYMENT:VIEW');
  const { data: orders } = useList('/api/maintenance/work-orders', 'WORK_ORDER:VIEW');
  const mine = (rows?: any[]) => (rows ?? []).filter((r) => r.tenant_id === id);
  const myLeases = mine(leases).length ? mine(leases) : (leases ?? []).filter((l) => name && l.tenant_name === name);
  const myInvoices = mine(invoices), myPayments = mine(payments), myOrders = mine(orders);
  const who = name ?? myLeases[0]?.tenant_name ?? myInvoices[0]?.tenant_name ?? myOrders[0]?.tenant_name ?? 'Resident';
  const unit = myLeases[0] ? `${myLeases[0].property_name} · ${myLeases[0].unit_label}` : myInvoices[0]?.unit_label;
  const balance = myInvoices.filter((i) => i.status !== 'VOID').reduce((s, i) => s + Number(i.balance ?? 0), 0);
  const overdue = myInvoices.filter((i) => Number(i.days_overdue ?? 0) > 0 && Number(i.balance ?? 0) > 0).length;
  const paid = myPayments.filter((p) => p.status === 'SUCCEEDED').reduce((s, p) => s + Number(p.amount), 0);
  const loaded = leases && invoices;
  return (
    <Shell title={who} subtitle={unit} onClose={onClose} onBack={onBack} width={620}>
      {!loaded ? <Loading /> : (<>
        <Panel title="Account">
          <KeyValues cols={3} items={[
            { label: 'Balance due', value: <span className={balance > 0 ? 'font-bold text-crit-700' : ''}>{fmt.usd(balance)}</span> },
            { label: 'Overdue invoices', value: overdue ? <Badge tone="crit">{overdue}</Badge> : '0' }, { label: 'Paid to date', value: fmt.usd(paid) },
          ]} />
          <div className="mt-3"><Contact email={myLeases[0]?.tenant_email} /></div>
        </Panel>
        <Panel title="Lease" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {myLeases.map((l: any) => (<Row key={l.id}><div className="min-w-0"><div className="font-semibold">{l.property_name} · {l.unit_label}</div><div className="text-xs text-graphite-500">{fmt.usd(l.rent)} a month, {fmt.date(l.start_date)} to {fmt.date(l.end_date)}</div></div><div className="flex items-center gap-2"><StatusPill status={l.status} /><LeaseGo id={l.id} onClose={onClose} /></div></Row>))}
            {myLeases.length === 0 && <Empty>No lease in your view.</Empty>}
          </ul>
        </Panel>
        <Panel title="Recent invoices" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {take(myInvoices).map((i) => (<Row key={i.id}><div className="min-w-0"><QuickLink kind="invoice" id={i.id}>{i.description}</QuickLink><div className="text-xs text-graphite-500">Due {fmt.date(i.due_date)}</div></div><div className="flex items-center gap-2"><span className="tabular-nums">{fmt.usd(i.amount, true)}</span><StatusPill status={i.status} /></div></Row>))}
            {myInvoices.length === 0 && <Empty>No invoices.</Empty>}
          </ul>
        </Panel>
        {payments && <Panel title="Recent payments" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {take(myPayments).map((p) => (<Row key={p.id}><div><QuickLink kind="payment" id={p.id}>{p.reference ?? 'Payment'}</QuickLink><div className="text-xs text-graphite-500">{fmt.date(p.created_at)} · {p.method} {p.method_last4 ? `•••• ${p.method_last4}` : ''}</div></div><span className="tabular-nums">{fmt.usd(p.amount, true)}</span></Row>))}
            {myPayments.length === 0 && <Empty>No payments yet.</Empty>}
          </ul>
        </Panel>}
        {orders && <Panel title="Repair requests" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {take(myOrders).map((w) => (<Row key={w.id}><div className="min-w-0"><QuickLink kind="workorder" id={w.id}>{w.title}</QuickLink><div className="text-xs text-graphite-500">{w.unit_label}</div></div><StatusPill status={w.status} /></Row>))}
            {myOrders.length === 0 && <Empty>No repair requests.</Empty>}
          </ul>
        </Panel>}
      </>)}
    </Shell>
  );
}
function LeaseGo({ id, onClose }: { id: string; onClose: () => void }) {
  const nav = useNavigate();
  return <Button size="sm" variant="quiet" onClick={() => { onClose(); nav(`/app/leasing/leases/${id}`); }}>Open</Button>;
}

// ---------------- Vendor ----------------
function VendorPanel({ id, onClose, onBack }: PanelProps) {
  const { data: vendors } = useApi<any[]>('/api/maintenance/vendors');
  const { data: orders } = useList('/api/maintenance/work-orders', 'WORK_ORDER:VIEW');
  const v = vendors?.find((x) => x.id === id);
  const jobs = (orders ?? []).filter((w) => w.vendor_id === id);
  const soon = v?.insurance_expires && new Date(v.insurance_expires).getTime() - Date.now() < 60 * 864e5;
  return (
    <Shell title={v?.name ?? 'Vendor'} subtitle={v?.contact ? `Contact: ${v.contact}` : undefined} onClose={onClose} onBack={onBack} openPath="/app/maintenance/vendors" openLabel="All vendors" width={600}>
      {!vendors ? <Loading /> : !v ? <Missing what="vendor" /> : (<>
        <Panel title="Contact"><Contact email={v.email} phone={v.phone} /></Panel>
        <Panel title="Performance">
          <KeyValues cols={3} items={[
            { label: 'Rating', value: `${v.rating} / 5` }, { label: 'Jobs completed', value: v.jobs_completed }, { label: 'Open jobs', value: v.open_jobs },
            { label: 'Average response', value: `${v.avg_response_hours} h` }, { label: 'Average job cost', value: fmt.usd(v.avg_job_cost) },
            { label: 'Insurance expires', value: <span className={soon ? 'font-bold text-crit-700' : ''}>{fmt.date(v.insurance_expires)}</span> },
          ]} />
          <div className="mt-3 flex flex-wrap gap-1.5">{(v.trades ?? []).map((t: string) => <Badge key={t} tone="brand">{fmt.title(t)}</Badge>)}{(v.cities ?? []).map((c: string) => <Badge key={c}>{c}</Badge>)}</div>
        </Panel>
        {orders && <Panel title="Their jobs" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {take(jobs, 6).map((w) => (<Row key={w.id}><div className="min-w-0"><QuickLink kind="workorder" id={w.id}>{w.title}</QuickLink><div className="truncate text-xs text-graphite-500">{w.property_name} · {w.unit_label}</div></div><StatusPill status={w.status} /></Row>))}
            {jobs.length === 0 && <Empty>No jobs in your view.</Empty>}
          </ul>
        </Panel>}
      </>)}
    </Shell>
  );
}

// ---------------- Work order ----------------
function WorkOrderPanel({ id, onClose, onBack }: PanelProps) {
  const { data: w } = useApi<any>(`/api/maintenance/work-orders/${id}`);
  const t = w?.ai_triage;
  return (
    <Shell title={w?.title ?? 'Work order'} subtitle={w ? <span className="flex items-center gap-2"><StatusPill status={w.status} /><Badge tone={w.priority === 'EMERGENCY' ? 'crit' : 'neutral'}>{fmt.title(w.priority)}</Badge></span> : undefined}
      onClose={onClose} onBack={onBack} openPath={`/app/maintenance/${id}`} width={620}>
      {!w ? <Loading /> : (<>
        {w.description && <p className="text-sm text-graphite-800">{w.description}</p>}
        <Panel title="Details">
          <KeyValues cols={2} items={[
            { label: 'Property', value: <QuickLink kind="property" id={w.property_id}>{w.property_name}</QuickLink> },
            { label: 'Unit', value: <QuickLink kind="unit" id={w.unit_id}>{w.unit_label}</QuickLink> },
            { label: 'Resident', value: <QuickLink kind="tenant" id={w.tenant_id} name={w.tenant_name}>{w.tenant_name}</QuickLink> },
            { label: 'Vendor', value: w.vendor_id ? <QuickLink kind="vendor" id={w.vendor_id}>{w.vendor_name}</QuickLink> : 'Not assigned' },
            { label: 'Category', value: fmt.title(w.category) }, { label: 'Estimate', value: w.estimate ? fmt.usd(w.estimate) : '—' },
            { label: 'Respond by', value: <span className={w.sla_breached ? 'font-bold text-crit-700' : ''}>{w.sla_due_at ? fmt.dateTime(w.sla_due_at) : '—'}{w.sla_breached ? ' (late)' : ''}</span> },
            { label: 'Permission to enter', value: w.permission_to_enter ? 'Yes' : 'No' },
          ]} />
        </Panel>
        {t?.summary && <Panel title="AI triage" tone="tinted"><p className="text-sm text-graphite-800">{t.summary}</p>{t.guardrail && <p className="mt-2 text-xs text-graphite-500">{t.guardrail}</p>}</Panel>}
        <Panel title="History" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {take(w.timeline, 6).map((e: any) => (<Row key={e.id}><div><div className="font-medium">{fmt.title(e.action)}{e.to_state ? `: ${fmt.title(e.to_state)}` : ''}</div><div className="text-xs text-graphite-500">{e.actor_name}</div></div><span className="text-xs text-graphite-500">{fmt.relative(e.created_at)}</span></Row>))}
            {(w.timeline ?? []).length === 0 && <Empty>No history yet.</Empty>}
          </ul>
        </Panel>
      </>)}
    </Shell>
  );
}

// ---------------- Invoice and payment ----------------
function InvoicePanel({ id, onClose, onBack }: PanelProps) {
  const { data: i } = useApi<any>(`/api/billing/invoices/${id}`);
  return (
    <Shell title={i?.description ?? 'Invoice'} subtitle={i ? <span className="flex items-center gap-2"><StatusPill status={i.status} />{fmt.title(i.kind)}</span> : undefined} onClose={onClose} onBack={onBack} width={560}>
      {!i ? <Loading /> : (<>
        <Panel title="Amounts">
          <KeyValues cols={3} items={[
            { label: 'Amount', value: fmt.usd(i.amount, true) }, { label: 'Paid', value: fmt.usd(i.paid, true) },
            { label: 'Balance', value: <span className={Number(i.balance) > 0 ? 'font-bold' : ''}>{fmt.usd(i.balance, true)}</span> },
            { label: 'Due date', value: fmt.date(i.due_date) }, { label: 'Period', value: i.period ?? '—' }, { label: 'Created by', value: i.created_by },
          ]} />
        </Panel>
        <Panel title="Billed to">
          <div className="space-y-1 text-sm"><QuickLink kind="tenant" id={i.tenant_id} name={i.tenant_name}>{i.tenant_name}</QuickLink><div className="text-graphite-600">{i.unit_label}</div>
            {i.property_id && <QuickLink kind="property" id={i.property_id} className="text-sm">View property</QuickLink>}</div>
        </Panel>
        <Panel title="Payments" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {(i.payments ?? []).map((p: any) => (<Row key={p.id}><div><QuickLink kind="payment" id={p.id}>{p.reference ?? 'Payment'}</QuickLink><div className="text-xs text-graphite-500">{fmt.date(p.created_at)} · {p.method}</div></div><span className="tabular-nums">{fmt.usd(p.amount, true)}</span></Row>))}
            {(i.payments ?? []).length === 0 && <Empty>No payments recorded.</Empty>}
          </ul>
        </Panel>
      </>)}
    </Shell>
  );
}

function PaymentPanel({ id, onClose, onBack }: PanelProps) {
  const { data: rows } = useApi<any[]>('/api/billing/payments');
  const p = rows?.find((x) => x.id === id);
  return (
    <Shell title={p ? fmt.usd(p.amount, true) : 'Payment'} subtitle={p ? <span className="flex items-center gap-2"><StatusPill status={p.status} />{p.reference}</span> : undefined} onClose={onClose} onBack={onBack} width={520}>
      {!rows ? <Loading /> : !p ? <Missing what="payment" /> : (<>
        <Panel title="Details">
          <KeyValues cols={2} items={[
            { label: 'Paid by', value: <QuickLink kind="tenant" id={p.tenant_id} name={p.tenant_name}>{p.tenant_name}</QuickLink> },
            { label: 'Method', value: `${p.method}${p.method_last4 ? ` •••• ${p.method_last4}` : ''}` },
            { label: 'Received', value: fmt.dateTime(p.created_at) }, { label: 'Source', value: fmt.title(p.source) },
          ]} />
        </Panel>
        <Panel title="Applied to" padded={false}>
          <ul className="divide-y divide-limestone-200">
            {(p.allocations ?? []).map((a: any, n: number) => (<Row key={n}><div>{a.invoiceId ? <QuickLink kind="invoice" id={a.invoiceId}>{fmt.title(a.kind)} invoice</QuickLink> : fmt.title(a.kind)}</div><span className="tabular-nums">{fmt.usd(a.amount, true)}</span></Row>))}
            {(p.allocations ?? []).length === 0 && <Empty>Not applied to an invoice.</Empty>}
          </ul>
        </Panel>
      </>)}
    </Shell>
  );
}

// ---------------- Applicant ----------------
function ApplicantPanel({ id, onClose, onBack }: PanelProps) {
  const { data: a } = useApi<any>(`/api/leasing/applications/${id}`);
  const s = a?.screening;
  return (
    <Shell title={a?.applicant_name ?? 'Applicant'} subtitle={a ? <span className="flex items-center gap-2"><StatusPill status={a.status} />{a.unit_label}</span> : undefined} onClose={onClose} onBack={onBack} openPath={`/app/leasing/applications/${id}`} openLabel="Open application" width={600}>
      {!a ? <Loading /> : (<>
        <Panel title="Contact"><Contact email={a.applicant_email} /></Panel>
        <Panel title="Application">
          <KeyValues cols={3} items={[
            { label: 'Monthly income', value: fmt.usd(a.monthly_income) }, { label: 'Rent applied for', value: fmt.usd(a.listing_rent) }, { label: 'Occupants', value: a.occupants },
            { label: 'Move-in', value: fmt.date(a.move_in_date) }, { label: 'Submitted', value: fmt.date(a.submitted_at) }, { label: 'Application fee', value: a.fee_paid ? 'Paid' : 'Unpaid' },
          ]} />
          <div className="mt-3 flex flex-wrap gap-3 text-sm">
            <QuickLink kind="unit" id={a.unit_id}>View unit</QuickLink>
            <QuickLink kind="property" id={a.property_id}>View property</QuickLink>
          </div>
        </Panel>
        {s && (
          <Panel title="Screening" tone="tinted">
            <KeyValues cols={3} items={[
              { label: 'Score', value: s.score }, { label: 'Credit score', value: s.creditScore ?? a.credit_score },
              { label: 'Income to rent', value: s.incomeToRent ? `${s.incomeToRent}×` : '—' },
              { label: 'Background', value: a.background_clear ? 'Clear' : 'Needs review' }, { label: 'Evictions', value: a.evictions ?? 0 }, { label: 'Recommendation', value: fmt.title(s.recommendation) },
            ]} />
            {Array.isArray(s.reasons) && s.reasons.length > 0 && <ul className="mt-3 list-disc pl-5 text-sm text-graphite-700">{s.reasons.map((r: string) => <li key={r}>{r}</li>)}</ul>}
            {s.guardrail && <p className="mt-2 text-xs text-graphite-500">{s.guardrail}</p>}
          </Panel>
        )}
      </>)}
    </Shell>
  );
}
