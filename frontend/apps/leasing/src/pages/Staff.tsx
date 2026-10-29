import { useEffect, useState } from 'react';
import { Link, NavLink, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import {
  PageHeader, Panel, Button, StatusPill, Stat, StatStrip, DataTable, type Column, Tabs, KeyValues, ActionBar, WorkflowTrack, Timeline, Field, Input,
  Modal, Select, ConfirmDialog, AiInsight, ConfidenceMeter, EmptyState, Skeleton, Section, useApi, useAction, api, fmt, useSession, Textarea, Badge, QuickLink, PersonCell, DocumentsPanel, toast,
} from '@propertyos/ui';
import { useBase, APPLICATION_STEPS, LEASE_STEPS, LISTING_STEPS, labelOf } from '../common';

// The section tabs (Leasing: Overview, Listings, Leads...) are drawn once by the shell from the same menu definition as the left menu.
const SubNav = () => null;

function Overview() {
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const { data: apps } = useApi<any[]>('/api/leasing/applications');
  const { data: leads } = useApi<any[]>('/api/leasing/leads');
  const units = (props ?? []).reduce((s, p) => s + p.units, 0), occ = (props ?? []).reduce((s, p) => s + p.occupied, 0);
  const roll = (props ?? []).reduce((s, p) => s + Number(p.rent_roll), 0);
  return (
    <>
      <PageHeader title="Leasing" description="From first enquiry to signed lease, with screening and follow-up automated." />
      <SubNav />
      <StatStrip className="mb-6">
        <Stat label="Occupancy" value={units ? fmt.pct((occ / units) * 100) : '—'} emphasis sub={`${occ} of ${units} units`} />
        <Stat label="Monthly rent roll" value={fmt.usd(roll)} />
        <Stat label="Open applications" value={(apps ?? []).filter((a) => !['APPROVED', 'REJECTED', 'WITHDRAWN'].includes(a.status)).length} />
        <Stat label="New leads" value={(leads ?? []).filter((l) => l.status === 'NEW').length} />
      </StatStrip>
      <Properties embedded />
    </>
  );
}


const STATES = 'AL AK AZ AR CA CO CT DE DC FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY'.split(' ');
const num = (v: string) => (v === '' ? undefined : Number(v));

/** Small "add" dialog: fields in, one POST, refresh the lists. */
function AddDialog({ open, title, onClose, onSave, saving, disabled, children, onArchive, archiveLabel = 'Archive' }: { open: boolean; title: string; onClose: () => void; onSave: () => void; saving: boolean; disabled: boolean; children: React.ReactNode; onArchive?: () => void; archiveLabel?: string }) {
  return (<Modal open={open} onClose={onClose} title={title} footer={<>{onArchive && <Button variant="danger" className="mr-auto" onClick={onArchive}>{archiveLabel}</Button>}<Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={saving} disabled={disabled} onClick={onSave}>Save</Button></>}>
    <div className="grid gap-3 sm:grid-cols-2">{children}</div></Modal>);
}

function AddOwner({ open, onClose, existing }: { open: boolean; onClose: () => void; existing?: any }) {
  const blank = { name: '', contact: '', email: '', phone: '', managementFeePct: '8', approvalLimit: '500' };
  const [f, setF] = useState<any>(blank);
  useEffect(() => { if (open) setF(existing ? { name: existing.name ?? '', contact: existing.contact ?? '', email: existing.email ?? '', phone: existing.phone ?? '', managementFeePct: String(existing.management_fee_pct ?? ''), approvalLimit: String(existing.approval_limit ?? '') } : blank); }, [open, existing?.id]);
  const body = () => ({ ...f, managementFeePct: num(f.managementFeePct), approvalLimit: num(f.approvalLimit) });
  const save = useAction(() => (existing ? api.put(`/api/leasing/owners/${existing.id}`, body()) : api.post('/api/leasing/owners', body())),
    { success: existing ? 'Owner updated' : 'Owner added', invalidate: ['/api/leasing/owners', '/api/leasing/properties'], onSuccess: () => { setF(blank); onClose(); } });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const archive = useAction(() => api.del(`/api/leasing/owners/${existing?.id}`), { success: 'Owner archived', invalidate: ['/api/leasing/owners', '/api/leasing/properties'], onSuccess: () => { setConfirm(false); onClose(); } });
  const [confirm, setConfirm] = useState(false);
  const { can } = useSession();
  return (<><AddDialog open={open} title={existing ? 'Edit owner' : 'Add an owner'} onClose={onClose} onSave={() => save.mutate(undefined as never)} saving={save.isPending} disabled={!f.name.trim()}
    onArchive={existing && can('OWNER:DELETE') ? () => setConfirm(true) : undefined}>
    <div className="sm:col-span-2"><Field label="Owner or company name" required><Input value={f.name} onChange={set('name')} /></Field></div>
    <Field label="Contact person"><Input value={f.contact} onChange={set('contact')} /></Field>
    <Field label="Phone"><Input value={f.phone} inputMode="tel" onChange={set('phone')} /></Field>
    <div className="sm:col-span-2"><Field label="Email"><Input type="email" value={f.email} onChange={set('email')} /></Field></div>
    <Field label="Management fee (%)" hint="Share of collected rent you keep"><Input type="number" min={0} max={100} step="0.1" value={f.managementFeePct} onChange={set('managementFeePct')} /></Field>
    <Field label="Repair approval limit ($)" hint="Repairs above this need the owner"><Input type="number" min={0} value={f.approvalLimit} onChange={set('approvalLimit')} /></Field>
  </AddDialog>
  <ConfirmDialog open={confirm} title={`Archive ${existing?.name ?? 'this owner'}?`} body="The owner is hidden from lists and pickers. Past statements and records are kept. An owner with properties can’t be archived." confirmLabel="Archive" tone="danger" loading={archive.isPending} onClose={() => setConfirm(false)} onConfirm={() => archive.mutate(undefined as never)} /></>);
}

function AddProperty({ open, onClose, onNeedOwner, existing }: { open: boolean; onClose: () => void; onNeedOwner: () => void; existing?: any }) {
  const { can: canDo } = useSession();
  const { data: owners } = useApi<any[]>(canDo('OWNER:VIEW') ? '/api/leasing/owners' : null);
  const blank = { ownerId: '', name: '', street: '', city: '', state: 'TX', zip: '', type: 'MULTIFAMILY', yearBuilt: '' };
  const [f, setF] = useState<any>(blank);
  useEffect(() => { if (open) setF(existing ? { ownerId: existing.owner_id, name: existing.name ?? '', street: existing.street ?? '', city: existing.city ?? '', state: existing.state ?? 'TX', zip: existing.zip ?? '', type: existing.type ?? 'MULTIFAMILY', yearBuilt: existing.year_built ? String(existing.year_built) : '' } : blank); }, [open, existing?.id]);
  const save = useAction(() => (existing ? api.put(`/api/leasing/properties/${existing.id}`, { name: f.name, street: f.street, city: f.city, state: f.state, zip: f.zip, type: f.type, yearBuilt: num(f.yearBuilt) }) : api.post('/api/leasing/properties', { ...f, yearBuilt: num(f.yearBuilt) })),
    { success: existing ? 'Property updated' : 'Property added', invalidate: ['/api/leasing/properties', '/api/leasing/owners'], onSuccess: () => { setF(blank); onClose(); } });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const ready = f.ownerId && f.name.trim() && f.street.trim() && f.city.trim() && f.zip.trim();
  const archive = useAction(() => api.del(`/api/leasing/properties/${existing?.id}`), { success: 'Property archived', invalidate: ['/api/leasing'], onSuccess: () => { setConfirm(false); onClose(); } });
  const [confirm, setConfirm] = useState(false);
  const { can } = useSession();
  return (<><AddDialog open={open} title={existing ? 'Edit property' : 'Add a property'} onClose={onClose} onSave={() => save.mutate(undefined as never)} saving={save.isPending} disabled={!ready}
    onArchive={existing && can('PROPERTY:DELETE') ? () => setConfirm(true) : undefined}>
    <div className="sm:col-span-2"><Field label="Owner" required hint={existing ? 'The owner can’t be changed here.' : owners && owners.length === 0 ? undefined : 'Not in the list? Add the owner first.'}>
      {existing ? <Input value={owners?.find((o) => o.id === f.ownerId)?.name ?? ''} disabled readOnly /> : owners && owners.length === 0
        ? (canDo('OWNER:CREATE') ? <Button variant="secondary" onClick={() => { onClose(); onNeedOwner(); }}>Add the first owner</Button> : <span className="text-sm text-graphite-600">There are no owners yet. Ask someone who can add owners.</span>)
        : <Select value={f.ownerId} onChange={set('ownerId')}><option value="">Choose an owner</option>{(owners ?? []).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</Select>}</Field></div>
    <div className="sm:col-span-2"><Field label="Property name" required><Input value={f.name} onChange={set('name')} /></Field></div>
    <div className="sm:col-span-2"><Field label="Street address" required><Input value={f.street} onChange={set('street')} /></Field></div>
    <Field label="City" required><Input value={f.city} onChange={set('city')} /></Field>
    <Field label="State" required><Select value={f.state} onChange={set('state')}>{STATES.map((x) => <option key={x}>{x}</option>)}</Select></Field>
    <Field label="ZIP code" required><Input value={f.zip} maxLength={10} onChange={set('zip')} /></Field>
    <Field label="Type"><Select value={f.type} onChange={set('type')}><option value="MULTIFAMILY">Multifamily</option><option value="SINGLE_FAMILY">Single family</option><option value="CONDO">Condo</option><option value="TOWNHOME">Townhome</option><option value="COMMERCIAL">Commercial</option></Select></Field>
    <Field label="Year built"><Input type="number" value={f.yearBuilt} onChange={set('yearBuilt')} /></Field>
  </AddDialog>
  <ConfirmDialog open={confirm} title={`Archive ${existing?.name ?? 'this property'}?`} body="The property and its vacant units are hidden. Leases, invoices and history are kept. It can’t be archived while a unit is occupied or has an open listing." confirmLabel="Archive" tone="danger" loading={archive.isPending} onClose={() => setConfirm(false)} onConfirm={() => archive.mutate(undefined as never)} /></>);
}

function AddUnit({ open, onClose, propertyId, existing }: { open: boolean; onClose: () => void; propertyId?: string; existing?: any }) {
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const blank = { propertyId: propertyId ?? '', number: '', beds: '1', baths: '1', sqft: '700', marketRent: '' };
  const [f, setF] = useState<any>(blank);
  useEffect(() => { if (open) setF(existing ? { propertyId: existing.property_id, number: existing.number ?? '', beds: String(existing.beds ?? ''), baths: String(existing.baths ?? ''), sqft: String(existing.sqft ?? ''), marketRent: String(existing.market_rent ?? '') } : blank); }, [open, existing?.id]);
  const pid = f.propertyId || propertyId || '';
  const save = useAction(() => (existing ? api.put(`/api/leasing/units/${existing.id}`, { number: f.number, beds: num(f.beds), baths: f.baths, sqft: num(f.sqft), marketRent: f.marketRent })
    : api.post(`/api/leasing/properties/${pid}/units`, { number: f.number, beds: num(f.beds), baths: f.baths, sqft: num(f.sqft), marketRent: f.marketRent })),
    { success: existing ? 'Unit updated' : 'Unit added', invalidate: ['/api/leasing/units', '/api/leasing/properties'], onSuccess: () => { setF(blank); onClose(); } });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const archive = useAction(() => api.del(`/api/leasing/units/${existing?.id}`), { success: 'Unit archived', invalidate: ['/api/leasing'], onSuccess: () => { setConfirm(false); onClose(); } });
  const [confirm, setConfirm] = useState(false);
  const { can } = useSession();
  return (<><AddDialog open={open} title={existing ? 'Edit unit' : 'Add a unit'} onClose={onClose} onSave={() => save.mutate(undefined as never)} saving={save.isPending} disabled={!pid || !f.number.trim() || !(Number(f.marketRent) > 0)}
    onArchive={existing && can('PROPERTY:DELETE') ? () => setConfirm(true) : undefined}>
    <div className="sm:col-span-2"><Field label="Property" required>
      <Select value={pid} disabled={!!existing} onChange={set('propertyId')}><option value="">Choose a property</option>{(props ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></Field></div>
    <Field label="Unit number" required><Input value={f.number} onChange={set('number')} /></Field>
    <Field label="Market rent ($ per month)" required><Input type="number" min={0} value={f.marketRent} onChange={set('marketRent')} /></Field>
    <Field label="Bedrooms"><Input type="number" min={0} value={f.beds} onChange={set('beds')} /></Field>
    <Field label="Bathrooms"><Input type="number" min={0} step="0.5" value={f.baths} onChange={set('baths')} /></Field>
    <Field label="Square feet"><Input type="number" min={0} value={f.sqft} onChange={set('sqft')} /></Field>
  </AddDialog>
  <ConfirmDialog open={confirm} title={`Archive unit ${existing?.number ?? ''}?`} body="The unit is hidden from lists. History is kept. It can’t be archived while occupied or listed." confirmLabel="Archive" tone="danger" loading={archive.isPending} onClose={() => setConfirm(false)} onConfirm={() => archive.mutate(undefined as never)} /></>);
}

function Properties({ embedded }: { embedded?: boolean }) {
  const { data, isLoading } = useApi<any[]>('/api/leasing/properties');
  const { can } = useSession();
  const [adding, setAdding] = useState<'property' | 'owner' | 'unit' | null>(null);
  const [editing, setEditing] = useState<any>(null);
  const cols: Column<any>[] = [
    { key: 'name', header: 'Property', render: (p) => (<div><QuickLink kind="property" id={p.id}>{p.name}</QuickLink><div className="text-xs text-graphite-500">{p.street}, {p.city}, {p.state}</div></div>) },
    { key: 'owner_name', header: 'Owner', hideOnMobile: true, render: (p) => <QuickLink kind="owner" id={p.owner_id} className="font-medium">{p.owner_name}</QuickLink> },
    { key: 'units', header: 'Units', align: 'right' },
    { key: 'vacant', header: 'Vacant', align: 'right', render: (p) => (p.vacant ? <Badge tone="warn">{p.vacant}</Badge> : '0') },
    { key: 'rent_roll', header: 'Rent roll', align: 'right', value: (p) => Number(p.rent_roll), render: (p) => fmt.usd(p.rent_roll) },
    { key: 'live_listings', header: 'Live listings', align: 'right', hideOnMobile: true },
  ];
  const add = can('PROPERTY:CREATE');
  const t = (<Panel title="Properties" padded={false} actions={add && <><Button size="sm" variant="secondary" onClick={() => setAdding('unit')} disabled={!data?.length}>Add unit</Button><Button size="sm" variant="primary" onClick={() => setAdding('property')}>Add property</Button></>}>
    <DataTable rows={data} columns={cols} rowKey={(p) => p.id} loading={isLoading} exportName="properties" onRowClick={(p) => add && setEditing(p)}
      empty={{ title: 'No properties yet', body: add ? 'Add the owner, then the property, then its units.' : undefined, action: add ? <Button variant="primary" onClick={() => setAdding('property')}>Add your first property</Button> : undefined } as any} />
    {add && <><AddProperty open={adding === 'property'} onClose={() => setAdding(null)} onNeedOwner={() => setAdding('owner')} />{can('OWNER:CREATE') && <AddOwner open={adding === 'owner'} onClose={() => setAdding(null)} />}<AddUnit open={adding === 'unit'} onClose={() => setAdding(null)} />
      <AddProperty open={!!editing} existing={editing} onClose={() => setEditing(null)} onNeedOwner={() => undefined} /></>}</Panel>);
  return embedded ? t : <><PageHeader title="Properties" /><SubNav />{t}</>;
}

function Units() {
  const { data, isLoading } = useApi<any[]>('/api/leasing/units');
  const { can } = useSession();
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const cols: Column<any>[] = [
    { key: 'property_name', header: 'Property', render: (u) => <QuickLink kind="property" id={u.property_id}>{u.property_name}</QuickLink> },
    { key: 'number', header: 'Unit', render: (u) => <QuickLink kind="unit" id={u.id}>{u.number}</QuickLink> },
    { key: 'status', header: 'Status', render: (u) => <StatusPill status={u.status} /> },
    { key: 'beds', header: 'Beds', align: 'right' },
    { key: 'baths', header: 'Baths', align: 'right', hideOnMobile: true },
    { key: 'sqft', header: 'Sq ft', align: 'right', hideOnMobile: true },
    { key: 'market_rent', header: 'Market rent', align: 'right', value: (u) => Number(u.market_rent), render: (u) => fmt.usd(u.market_rent) },
    { key: 'current_rent', header: 'Current rent', align: 'right', value: (u) => (u.current_rent == null ? null : Number(u.current_rent)), render: (u) => (u.current_rent == null ? '—' : fmt.usd(u.current_rent)) },
    { key: 'vacant_since', header: 'Vacant since', hideOnMobile: true, render: (u) => (u.vacant_since ? fmt.date(u.vacant_since) : '—') },
  ];
  return (<><PageHeader title="Units" description="Every door in your portfolio, with rent and vacancy." /><SubNav />
    <Panel padded={false} actions={can('PROPERTY:CREATE') && <Button size="sm" variant="primary" onClick={() => setAdding(true)}>Add unit</Button>}>
      <DataTable rows={data} columns={cols} rowKey={(u) => u.id} loading={isLoading} exportName="units" onRowClick={(u) => can('PROPERTY:EDIT') && setEditing(u)} empty={{ title: 'No units yet', body: can('PROPERTY:CREATE') ? 'Add a property first, then its units.' : undefined } as any} />
      {can('PROPERTY:CREATE') && <AddUnit open={adding} onClose={() => setAdding(false)} />}
      {can('PROPERTY:EDIT') && <AddUnit open={!!editing} existing={editing} onClose={() => setEditing(null)} />}</Panel></>);
}

function Owners() {
  const { data, isLoading } = useApi<any[]>('/api/leasing/owners');
  const { can } = useSession();
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const cols: Column<any>[] = [
    { key: 'name', header: 'Owner', render: (o) => <QuickLink kind="owner" id={o.id}>{o.name}</QuickLink> },
    { key: 'contact', header: 'Contact', render: (o) => (o.contact ? <PersonCell name={o.contact} sub={o.email} /> : '—') },
    { key: 'phone', header: 'Phone', hideOnMobile: true, filter: 'text' },
    { key: 'properties', header: 'Properties', align: 'right', value: (o) => Number(o.properties) },
    { key: 'units', header: 'Units', align: 'right', value: (o) => Number(o.units) },
    { key: 'management_fee_pct', header: 'Mgmt fee %', align: 'right', hideOnMobile: true, value: (o) => Number(o.management_fee_pct) },
    { key: 'approval_limit', header: 'Approval limit', align: 'right', hideOnMobile: true, value: (o) => Number(o.approval_limit), render: (o) => fmt.usd(o.approval_limit) },
  ];
  return (<><PageHeader title="Owners" description="Property owners, their contacts and management terms." /><SubNav />
    <Panel padded={false} actions={can('OWNER:CREATE') && <Button size="sm" variant="primary" onClick={() => setAdding(true)}>Add owner</Button>}>
      <DataTable rows={data} columns={cols} rowKey={(o) => o.id} loading={isLoading} exportName="owners" onRowClick={(o) => can('OWNER:EDIT') && setEditing(o)} empty={{ title: 'No owners yet', body: can('OWNER:CREATE') ? 'Add the people or companies whose properties you manage.' : undefined } as any} />
      {can('OWNER:CREATE') && <AddOwner open={adding} onClose={() => setAdding(false)} />}
      {can('OWNER:EDIT') && <AddOwner open={!!editing} existing={editing} onClose={() => setEditing(null)} />}</Panel></>);
}

function NewListing({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const { data: units } = useApi<any[]>('/api/leasing/units?status=VACANT');
  const { data: listed } = useApi<any[]>('/api/leasing/listings');
  const [unitId, setUnitId] = useState(''); const [rent, setRent] = useState('');
  const taken = new Set((listed ?? []).filter((l) => l.status !== 'LEASED').map((l) => l.unit_id));
  const free = (units ?? []).filter((u) => u.status === 'VACANT' && !taken.has(u.id));
  const save = useAction(() => api.post<{ id: string }>('/api/leasing/listings', { unitId, rent: rent ? Number(rent) : undefined }),
    { success: 'Draft listing created', invalidate: ['/api/leasing/listings'], onSuccess: (r: any) => { setUnitId(''); setRent(''); onClose(); onCreated(r.id); } });
  return (<AddDialog open={open} title="New listing" onClose={onClose} onSave={() => save.mutate(undefined as never)} saving={save.isPending} disabled={!unitId}>
    <div className="sm:col-span-2"><Field label="Vacant unit" required hint={free.length === 0 ? 'No vacant unit without a listing. Add a unit first.' : 'Rent, details and photos are filled in from the unit.'}>
      <Select value={unitId} onChange={(e) => { setUnitId(e.target.value); setRent(''); }}><option value="">Choose a unit</option>
        {free.map((u) => <option key={u.id} value={u.id}>{u.property_name} · Unit {u.number} · {fmt.usd(u.market_rent)}</option>)}</Select></Field></div>
    <Field label="Asking rent ($)" hint="Leave empty to use the unit’s market rent"><Input type="number" min={0} value={rent} onChange={(e) => setRent(e.target.value)} /></Field>
  </AddDialog>);
}

function Listings() {
  const nav = useNavigate(); const base = useBase();
  const { can } = useSession();
  const [adding, setAdding] = useState(false);
  const { data, isLoading } = useApi<any[]>('/api/leasing/listings');
  const cols: Column<any>[] = [
    { key: 'title', header: 'Listing', render: (l) => (<div><div className="font-semibold">{l.title}</div><div className="text-xs text-graphite-500">Available {fmt.shortDate(l.available_date)}</div></div>) },
    { key: 'status', header: 'Status', render: (l) => <StatusPill status={l.status} /> },
    { key: 'rent', header: 'Rent', align: 'right', value: (l) => Number(l.rent), render: (l) => fmt.usd(l.rent) },
    { key: 'views', header: 'Views', align: 'right', hideOnMobile: true },
    { key: 'enquiries', header: 'Enquiries', align: 'right', hideOnMobile: true },
  ];
  return (<><PageHeader title="Listings" description="Draft with AI help, review, then publish to the public site." /><SubNav />
    <Panel padded={false} actions={can('LISTING:CREATE') && <Button size="sm" variant="primary" onClick={() => setAdding(true)}>New listing</Button>}>
      <DataTable rows={data} columns={cols} rowKey={(l) => l.id} loading={isLoading} onRowClick={(l) => nav(`${base}/listings/${l.id}`)} exportName="listings"
        empty={{ title: 'No listings yet', body: can('LISTING:CREATE') ? 'Create a listing from a vacant unit, review it, then publish it.' : undefined }} />
      {can('LISTING:CREATE') && <NewListing open={adding} onClose={() => setAdding(false)} onCreated={(id) => nav(`${base}/listings/${id}`)} />}</Panel></>);
}

function ListingDetail() {
  const { id } = useParams(); const base = useBase(); const nav = useNavigate();
  const { data: l, isLoading } = useApi<any>(`/api/leasing/listings/${id}`);
  const [rent, setRent] = useState('');
  const refresh = ['/api/leasing/listings'];
  const assist = useAction(() => api.post<any>(`/api/leasing/listings/${id}/ai-assist`, {}), { success: 'AI draft applied', invalidate: refresh });
  const act = useAction((v: { a: string; reason?: string }) => api.post(`/api/leasing/listings/${id}/actions/${v.a}`, { reason: v.reason }), { success: 'Listing updated', invalidate: refresh });
  const save = useAction(() => api.put(`/api/leasing/listings/${id}`, { rent: Number(rent) }), { success: 'Rent updated', invalidate: refresh, onSuccess: () => setRent('') });
  const [delAsk, setDelAsk] = useState(false);
  const remove = useAction(() => api.del(`/api/leasing/listings/${id}`), { success: 'Draft deleted', invalidate: refresh, onSuccess: () => { setDelAsk(false); nav(`${base}/listings`); } });
  const { can } = useSession();
  if (isLoading || !l) return <Skeleton className="h-96" />;
  const rs = l.rent_suggestion;
  return (<>
    <PageHeader title={l.title} back={<Link to={`${base}/listings`} className="text-sm font-semibold text-federal-900">All listings</Link>}
      meta={<><StatusPill status={l.status} /><span><QuickLink kind="property" id={l.property_id}>{l.property_name}</QuickLink> · Unit {l.unit_number}</span></>}
      actions={<>{l.status === 'DRAFT' && can('LISTING:DELETE') && <Button variant="quiet" onClick={() => setDelAsk(true)}>Delete draft</Button>}<Button icon={<Sparkles size={14} />} loading={assist.isPending} onClick={() => assist.mutate(undefined)}>Draft with AI</Button><ActionBar actions={l.actions} loading={act.isPending} primary={['PUBLISH', 'APPROVE']} onAction={(a, reason) => act.mutate({ a, reason })} /></>} />
    <div className="mb-6"><WorkflowTrack steps={LISTING_STEPS} current={l.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
      <div className="space-y-6">
        <Panel title="Description"><p className="max-w-[70ch] leading-relaxed text-graphite-800">{l.description}</p>{l.ai_written && <p className="mt-3 text-xs text-graphite-500">Written with AI and reviewed by your team before publishing.</p>}</Panel>
        <Panel title="Unit"><KeyValues cols={3} items={[{ label: 'Bedrooms', value: l.beds }, { label: 'Bathrooms', value: l.baths }, { label: 'Size', value: `${l.sqft} sqft` }, { label: 'Rent', value: fmt.usd(l.rent) }, { label: 'Market rent', value: fmt.usd(l.market_rent) }, { label: 'Views / enquiries', value: `${l.views} / ${l.enquiries}` }]} /></Panel>
      </div>
      <div className="space-y-6">
        {rs && <AiInsight title="Rent suggestion" confidence={0.7} reasons={[rs.reason]}
          footer={<Button size="sm" variant="primary" onClick={() => { setRent(String(rs.suggested)); }}>Use {fmt.usd(rs.suggested)}</Button>}>
          Suggested {fmt.usd(rs.suggested)} against {fmt.usd(rs.current)} today ({rs.monthlyDelta >= 0 ? '+' : ''}{fmt.usd(rs.monthlyDelta)} a month).
        </AiInsight>}
        <Panel title="Change rent"><Field label="New monthly rent (USD)"><Input type="number" value={rent} onChange={(e) => setRent(e.target.value)} /></Field><Button className="mt-3" variant="primary" disabled={!rent} loading={save.isPending} onClick={() => save.mutate(undefined)}>Save rent</Button></Panel>
      </div>
    </div>
    <ConfirmDialog open={delAsk} title="Delete this draft?" body="The draft is removed for good. A listing that was published is never deleted: pause it instead." confirmLabel="Delete draft" tone="danger" loading={remove.isPending} onClose={() => setDelAsk(false)} onConfirm={() => remove.mutate(undefined as never)} />
  </>);
}

function Leads() {
  const { data, isLoading } = useApi<any[]>('/api/leasing/leads');
  const [open, setOpen] = useState<any>(null); const [reply, setReply] = useState('');
  const send = useAction(() => api.post(`/api/leasing/leads/${open.id}/respond`, { reply }), { success: 'Reply sent', invalidate: ['/api/leasing/leads'], onSuccess: () => setOpen(null) });
  const cols: Column<any>[] = [
    { key: 'name', header: 'Prospect', render: (l) => (<div><div className="font-semibold">{l.name}</div><div className="text-xs text-graphite-500">{l.email}</div></div>) },
    { key: 'listing_title', header: 'Interested in', hideOnMobile: true },
    { key: 'ai_score', header: 'AI score', align: 'right', render: (l) => <ConfidenceMeter value={(l.ai_score ?? 0) / 100} /> },
    { key: 'status', header: 'Status', render: (l) => <StatusPill status={l.status} /> },
    { key: 'created_at', header: 'Received', value: (l) => l.created_at, render: (l) => fmt.date(l.created_at) },
  ];
  return (<><PageHeader title="Leads" description="Enquiries are scored and answered instantly; review the reply before it goes out." /><SubNav />
    <Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(l) => l.id} loading={isLoading} initialSort={{ key: 'ai_score', dir: 'desc' }} onRowClick={(l) => { setOpen(l); setReply(l.ai_reply ?? ''); }} /></Panel>
    <Modal open={!!open} onClose={() => setOpen(null)} title={open?.name ?? ''} footer={<><Button variant="quiet" onClick={() => setOpen(null)}>Close</Button><Button variant="primary" loading={send.isPending} onClick={() => send.mutate(undefined)}>Send reply</Button></>}>
      <p className="mb-3 text-sm text-graphite-700">“{open?.message}”</p>
      <Field label="Reply drafted by AI" hint="Edit before sending."><Textarea rows={6} value={reply} onChange={(e) => setReply(e.target.value)} /></Field>
    </Modal></>);
}

function Showings() {
  const { data, isLoading } = useApi<any[]>('/api/leasing/showings');
  const act = useAction((v: { id: string; a: string }) => api.post(`/api/leasing/showings/${v.id}/actions/${v.a}`, {}), { success: 'Showing updated', invalidate: ['/api/leasing/showings'] });
  const cols: Column<any>[] = [
    { key: 'scheduled_at', header: 'When', value: (s) => s.scheduled_at, render: (s) => new Date(s.scheduled_at).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' }) },
    { key: 'prospect_name', header: 'Prospect' }, { key: 'listing_title', header: 'Home', hideOnMobile: true },
    { key: 'status', header: 'Status', render: (s) => <StatusPill status={s.status} /> },
    { key: 'a', header: '', align: 'right', render: (s) => <div className="flex justify-end gap-2"><ActionBar actions={s.actions} primary={['CONFIRM']} onAction={(a) => act.mutate({ id: s.id, a })} /></div> },
  ];
  return (<><PageHeader title="Showings" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(s) => s.id} loading={isLoading} empty={{ title: 'No showings scheduled' }} /></Panel></>);
}

function Applications() {
  const nav = useNavigate(); const base = useBase();
  const { data, isLoading } = useApi<any[]>('/api/leasing/applications');
  const cols: Column<any>[] = [
    { key: 'applicant_name', header: 'Applicant', render: (a) => (<div><div className="font-semibold"><QuickLink kind="applicant" id={a.id}>{a.applicant_name}</QuickLink></div><div className="text-xs text-graphite-500">{a.unit_label}</div></div>) },
    { key: 'status', header: 'Status', render: (a) => <StatusPill status={a.status} /> },
    { key: 'recommendation', header: 'AI recommends', render: (a) => (a.recommendation ? <Badge tone={a.recommendation === 'APPROVE' ? 'good' : a.recommendation === 'DECLINE' ? 'crit' : 'warn'}>{labelOf(a.recommendation)}</Badge> : '—') },
    { key: 'monthly_income', header: 'Income', align: 'right', hideOnMobile: true, value: (a) => Number(a.monthly_income), render: (a) => fmt.usd(a.monthly_income) },
    { key: 'credit_score', header: 'Credit', align: 'right', hideOnMobile: true, render: (a) => a.credit_score ?? '—' },
    { key: 'submitted_at', header: 'Submitted', value: (a) => a.submitted_at, render: (a) => fmt.date(a.submitted_at) },
  ];
  return (<><PageHeader title="Applications" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(a) => a.id} loading={isLoading} onRowClick={(a) => nav(`${base}/applications/${a.id}`)} initialSort={{ key: 'submitted_at', dir: 'desc' }} /></Panel></>);
}

function ApplicationDetail() {
  const { id } = useParams(); const base = useBase(); const nav = useNavigate();
  const { data: a, isLoading } = useApi<any>(`/api/leasing/applications/${id}`);
  const inv = ['/api/leasing/applications'];
  const screen = useAction(() => api.post(`/api/leasing/applications/${id}/screen`, {}), { success: 'Screening complete', invalidate: inv });
  const act = useAction((v: { a: string; reason?: string }) => api.post(`/api/leasing/applications/${id}/actions/${v.a}`, { reason: v.reason }), { success: 'Application updated', invalidate: inv });
  const mkLease = useAction(() => api.post<any>(`/api/leasing/applications/${id}/lease`, {}), { success: 'Lease drafted', invalidate: inv, onSuccess: (r) => nav(`${base}/leases/${r.id}`) });
  const link = useAction(() => api.post<{ path: string }>(`/api/leasing/applications/${id}/upload-link`, {}), {
    silent: true, onSuccess: async (r) => {
      const url = `${window.location.origin}${r.path}`;
      try { await navigator.clipboard.writeText(url); toast('Upload link copied. Send it to the applicant.'); } catch { window.prompt('Copy this link and send it to the applicant', url); }
    } });
  if (isLoading || !a) return <Skeleton className="h-96" />;
  const s = a.screening;
  const canScreen = ['SUBMITTED', 'DOCS_PENDING'].includes(a.status);
  return (<>
    <PageHeader title={a.applicant_name} back={<Link to={`${base}/applications`} className="text-sm font-semibold text-federal-900">All applications</Link>}
      meta={<><StatusPill status={a.status} /><span>{a.unit_label}</span></>}
      actions={<>{!['REJECTED', 'WITHDRAWN', 'LEASED'].includes(a.status) && <Button variant="secondary" loading={link.isPending} onClick={() => link.mutate(undefined)}>Copy upload link</Button>}{canScreen && <Button variant="primary" loading={screen.isPending} onClick={() => screen.mutate(undefined)}>Run screening</Button>}
        <ActionBar actions={a.actions} loading={act.isPending} primary={['APPROVE']} onAction={(x, reason) => act.mutate({ a: x, reason })} />
        {a.status === 'APPROVED' && !a.lease && <Button variant="primary" loading={mkLease.isPending} onClick={() => mkLease.mutate(undefined)}>Create lease</Button>}
        {a.lease && <Link to={`${base}/leases/${a.lease.id}`}><Button>Open lease</Button></Link>}</>} />
    <div className="mb-6"><WorkflowTrack steps={APPLICATION_STEPS} current={a.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
      <div className="space-y-6">
        <Panel title="Applicant"><KeyValues cols={3} items={[
          { label: 'Email', value: a.applicant_email }, { label: 'Monthly income', value: fmt.usd(a.monthly_income) }, { label: 'Employer', value: a.employer },
          { label: 'Months employed', value: a.months_employed }, { label: 'Occupants', value: a.occupants }, { label: 'Move-in', value: fmt.date(a.move_in_date) },
          { label: 'Rent applied for', value: fmt.usd(a.listing_rent) }, { label: 'Credit score', value: a.credit_score ?? 'Not run' }, { label: 'Background', value: a.background_clear == null ? 'Not run' : a.background_clear ? 'Clear' : 'Needs review' },
        ]} /></Panel>
        {a.decision_reason && <Panel title="Decision"><p className="text-sm text-graphite-800">{a.decision_reason}</p></Panel>}
        <DocumentsPanel entityType="APPLICATION" entityId={a.id} title="Applicant files" description="ID, income proof and anything else the applicant sent. Files from the applicant wait for your review." />
      </div>
      <div>
        {s ? <AiInsight title={`Screening: ${labelOf(s.recommendation)}`} confidence={(s.score ?? 0) / 100} tone={s.recommendation === 'APPROVE' ? 'good' : s.recommendation === 'DECLINE' ? 'crit' : 'warn'}
          reasons={s.reasons?.length ? s.reasons : ['Meets income, credit and background criteria']}>
          <p>{s.summary}</p><p className="mt-2 text-xs text-graphite-500">{s.guardrail}</p>
        </AiInsight> : <EmptyState title="Not screened yet" body="Run screening to check income, credit and background." />}
      </div>
    </div>
  </>);
}

function Leases() {
  const nav = useNavigate(); const base = useBase();
  const { data, isLoading } = useApi<any[]>('/api/leasing/leases');
  const cols: Column<any>[] = [
    { key: 'tenant_name', header: 'Resident', render: (l) => (<div><div className="font-semibold"><QuickLink kind="tenant" id={l.tenant_id} name={l.tenant_name}>{l.tenant_name}</QuickLink></div><div className="text-xs text-graphite-500"><QuickLink kind="property" id={l.property_id} className="font-medium">{l.property_name}</QuickLink> · <QuickLink kind="unit" id={l.unit_id} className="font-medium">{l.unit_label}</QuickLink></div></div>) },
    { key: 'status', header: 'Status', render: (l) => <StatusPill status={l.status} /> },
    { key: 'rent', header: 'Rent', align: 'right', value: (l) => Number(l.rent), render: (l) => fmt.usd(l.rent) },
    { key: 'end_date', header: 'Ends', value: (l) => l.end_date, render: (l) => fmt.date(l.end_date) },
    { key: 'days_left', header: 'Days left', align: 'right', hideOnMobile: true, render: (l) => (l.status === 'ACTIVE' ? <span className={l.days_left <= 60 ? 'font-semibold text-warn-700' : ''}>{l.days_left}</span> : '—') },
  ];
  return (<><PageHeader title="Leases" /><SubNav /><Panel padded={false}><DataTable rows={data} columns={cols} rowKey={(l) => l.id} loading={isLoading} onRowClick={(l) => nav(`${base}/leases/${l.id}`)} initialSort={{ key: 'end_date', dir: 'asc' }} /></Panel></>);
}

function LeaseView({ mine }: { mine?: boolean }) {
  const params = useParams(); const base = useBase();
  const path = mine ? '/api/leasing/leases/mine' : `/api/leasing/leases/${params.id}`;
  const { data: l, isLoading, error } = useApi<any>(path, { retry: false });
  const [sig, setSig] = useState('');
  const inv = ['/api/leasing/leases'];
  const act = useAction((v: { a: string; reason?: string; body?: any }) => api.post(`/api/leasing/leases/${l.id}/actions/${v.a}`, { reason: v.reason, ...(v.body ?? {}) }), { success: 'Lease updated', invalidate: inv });
  const renew = useAction(() => api.post<any>(`/api/leasing/leases/${l.id}/renewal`, {}), { success: 'Renewal offer prepared', invalidate: inv });
  if (isLoading) return <Skeleton className="h-96" />;
  if (error || !l) return <EmptyState title="No lease found" body="When you have an active lease it appears here." />;
  const canSign = mine && l.status === 'SENT';
  const rn = l.renewal_notices;
  return (<>
    <PageHeader title={mine ? 'My lease' : `${l.tenant_name} · ${l.unit_label}`} back={!mine && <Link to={`${base}/leases`} className="text-sm font-semibold text-federal-900">All leases</Link>}
      meta={<><StatusPill status={l.status} /><span><QuickLink kind="property" id={l.property_id}>{l.property_name}</QuickLink></span></>}
      actions={!mine ? <>{l.status === 'ACTIVE' && <Button loading={renew.isPending} onClick={() => renew.mutate(undefined)}>Prepare renewal</Button>}
        <ActionBar actions={l.actions} loading={act.isPending} primary={['SEND', 'COUNTERSIGN']} onAction={(a, reason) => act.mutate({ a, reason })} /></> : undefined} />
    <div className="mb-6"><WorkflowTrack steps={LEASE_STEPS} current={l.status} /></div>
    <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
      <div className="space-y-6">
        <Panel title="Lease agreement"><pre className="max-h-[560px] overflow-y-auto whitespace-pre-wrap font-sans text-sm leading-relaxed text-graphite-800">{l.document_text}</pre></Panel>
        <DocumentsPanel entityType="LEASE" entityId={l.id} title="Lease documents" description="The signed lease, addenda, insurance and inspections." />
      </div>
      <div className="space-y-6">
        <Panel title="Terms"><KeyValues cols={1} items={[{ label: 'Rent', value: fmt.usd(l.rent) }, { label: 'Deposit', value: fmt.usd(l.deposit) }, { label: 'Term', value: `${fmt.date(l.start_date)} to ${fmt.date(l.end_date)}` }, { label: 'State', value: l.state },
          { label: 'Tenant signed', value: l.tenant_signed_at ? fmt.date(l.tenant_signed_at) : 'Not yet' }, { label: 'Countersigned', value: l.manager_signed_at ? fmt.date(l.manager_signed_at) : 'Not yet' }]} /></Panel>
        {canSign && <Panel title="Sign your lease" tone="tinted"><Field label={`Type your full legal name: ${l.tenant_name}`}><Input value={sig} onChange={(e) => setSig(e.target.value)} /></Field>
          <Button className="mt-3 w-full" variant="primary" disabled={!sig} loading={act.isPending} onClick={() => act.mutate({ a: 'TENANT_SIGN', body: { signature: sig } })}>Sign lease</Button></Panel>}
        {rn && <AiInsight title="Renewal offer" confidence={0.8}>{typeof rn === 'string' ? rn : JSON.stringify(rn)}</AiInsight>}
      </div>
    </div>
  </>);
}

export function Staff() {
  return (
    <Routes>
      <Route index element={<Overview />} />
      <Route path="properties" element={<Properties />} />
      <Route path="units" element={<Units />} />
      <Route path="owners" element={<Owners />} />
      <Route path="listings" element={<Listings />} />
      <Route path="listings/:id" element={<ListingDetail />} />
      <Route path="leads" element={<Leads />} />
      <Route path="showings" element={<Showings />} />
      <Route path="applications" element={<Applications />} />
      <Route path="applications/:id" element={<ApplicationDetail />} />
      <Route path="leases" element={<Leases />} />
      <Route path="leases/:id" element={<LeaseView />} />
      <Route path="my-lease" element={<LeaseView mine />} />
    </Routes>
  );
}
