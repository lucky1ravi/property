import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Mail, Phone } from 'lucide-react';
import { Drawer, KeyValues, Panel, StatusPill, Badge, Skeleton, Button, useApi, useSession, fmt } from '@propertyos/ui';
import { useBase } from '../common';

type Target = { kind: 'property' | 'owner'; id: string };
interface Ctx { openProperty: (id?: string | null) => void; openOwner: (id?: string | null) => void }
const QuickViewCtx = createContext<Ctx>({ openProperty: () => {}, openOwner: () => {} });
export const useQuickView = () => useContext(QuickViewCtx);

/** A name that opens a quick-information panel. Renders plain text when there is nothing to open. */
export function QuickLink({ kind, id, children, className = '' }: { kind: 'property' | 'owner'; id?: string | null; children: ReactNode; className?: string }) {
  const { openProperty, openOwner } = useQuickView();
  const { can } = useSession();
  if (!id || !can('PROPERTY:VIEW')) return <>{children}</>;
  return (
    <button type="button" onClick={(e) => { e.stopPropagation(); (kind === 'property' ? openProperty : openOwner)(id); }}
      className={`text-left font-semibold text-federal-800 underline-offset-2 hover:text-federal-950 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slateblue-600 ${className}`}>
      {children}
    </button>
  );
}

export function QuickViewProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<Target[]>([]);
  const push = useCallback((kind: Target['kind'], id?: string | null) => { if (id) setStack((s) => [...s, { kind, id }]); }, []);
  const ctx = useMemo<Ctx>(() => ({ openProperty: (id) => push('property', id), openOwner: (id) => push('owner', id) }), [push]);
  const top = stack[stack.length - 1];
  const close = () => setStack([]);
  const back = () => setStack((s) => s.slice(0, -1));
  return (
    <QuickViewCtx.Provider value={ctx}>
      {children}
      {top?.kind === 'property' && <PropertyPanel id={top.id} onClose={close} onBack={stack.length > 1 ? back : undefined} />}
      {top?.kind === 'owner' && <OwnerPanel id={top.id} onClose={close} onBack={stack.length > 1 ? back : undefined} />}
    </QuickViewCtx.Provider>
  );
}

const BackLink = ({ onBack }: { onBack?: () => void }) => (onBack ? <button onClick={onBack} className="mb-3 text-sm font-semibold text-federal-800 hover:underline">← Back</button> : null);

function PropertyPanel({ id, onClose, onBack }: { id: string; onClose: () => void; onBack?: () => void }) {
  const { data: p } = useApi<any>(`/api/leasing/properties/${id}`);
  const { data: list } = useApi<any[]>('/api/leasing/properties');
  const stats = list?.find((x) => x.id === id);
  const nav = useNavigate();
  const base = useBase();
  const { openOwner } = useQuickView();
  const go = (path: string) => { onClose(); nav(path); };
  return (
    <Drawer open onClose={onClose} width={680} title={p?.name ?? 'Property'} subtitle={p ? `${p.street}, ${p.city}, ${p.state} ${p.zip}` : undefined}
      footer={<Button variant="quiet" onClick={() => go(`${base}/listings`)}>View listings</Button>}>
      <BackLink onBack={onBack} />
      {!p ? <Skeleton className="h-64" /> : (
        <div className="space-y-5">
          {p.photo_url && <img src={p.photo_url} alt="" className="h-44 w-full rounded-md object-cover" loading="lazy" />}
          <Panel title="At a glance">
            <KeyValues cols={3} items={[
              { label: 'Type', value: fmt.title(p.type) }, { label: 'Year built', value: p.year_built },
              { label: 'Status', value: <StatusPill status={p.status} /> },
              { label: 'Units', value: stats?.units ?? p.units.length }, { label: 'Occupied', value: stats?.occupied },
              { label: 'Vacant', value: stats?.vacant ? <Badge tone="warn">{stats.vacant}</Badge> : '0' },
              { label: 'Rent roll', value: stats ? fmt.usd(stats.rent_roll) : '—' }, { label: 'Market rent', value: stats ? fmt.usd(stats.market_rent_total) : '—' },
              { label: 'Live listings', value: stats?.live_listings },
            ]} />
          </Panel>
          <Panel title="Owner">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <QuickLink kind="owner" id={p.owner_id}>{p.owner_name ?? 'No owner'}</QuickLink>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-sm text-graphite-600">
                  {p.owner_email && <a className="inline-flex items-center gap-1 hover:underline" href={`mailto:${p.owner_email}`}><Mail className="h-3.5 w-3.5" />{p.owner_email}</a>}
                  {p.owner_phone && <a className="inline-flex items-center gap-1 hover:underline" href={`tel:${p.owner_phone}`}><Phone className="h-3.5 w-3.5" />{p.owner_phone}</a>}
                </div>
              </div>
              {p.owner_id && <Button size="sm" variant="quiet" onClick={() => openOwner(p.owner_id)}>Owner details</Button>}
            </div>
          </Panel>
          {Array.isArray(p.amenities) && p.amenities.length > 0 && (
            <div className="flex flex-wrap gap-1.5">{p.amenities.map((a: string) => <Badge key={a}>{a}</Badge>)}</div>
          )}
          <Panel title={`Units (${p.units.length})`} padded={false}>
            <ul className="divide-y divide-limestone-200 text-sm">
              {p.units.map((u: any) => (
                <li key={u.id} className="flex items-center justify-between gap-3 px-5 py-3">
                  <div className="min-w-0">
                    <div className="font-semibold text-graphite-900">Unit {u.number} <span className="font-normal text-graphite-500">· {u.beds} bd, {u.baths} ba, {u.sqft} sqft</span></div>
                    <div className="truncate text-xs text-graphite-500">{u.tenant_name ? `${u.tenant_name}${u.lease_end ? `, lease ends ${fmt.date(u.lease_end)}` : ''}` : 'No resident'}</div>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <span className="tabular-nums">{fmt.usd(u.current_rent ?? u.market_rent)}</span>
                    <StatusPill status={u.status} />
                    {u.lease_id ? <button className="text-xs font-semibold text-federal-800 hover:underline" onClick={() => go(`${base}/leases/${u.lease_id}`)}>Lease</button>
                      : u.listing_id ? <button className="text-xs font-semibold text-federal-800 hover:underline" onClick={() => go(`${base}/listings/${u.listing_id}`)}>Listing</button> : null}
                  </div>
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      )}
    </Drawer>
  );
}

function OwnerPanel({ id, onClose, onBack }: { id: string; onClose: () => void; onBack?: () => void }) {
  const { data: owners } = useApi<any[]>('/api/leasing/owners');
  const { data: props } = useApi<any[]>('/api/leasing/properties');
  const o = owners?.find((x) => x.id === id);
  const mine = (props ?? []).filter((p) => p.owner_id === id);
  const { openProperty } = useQuickView();
  return (
    <Drawer open onClose={onClose} width={560} title={o?.name ?? 'Owner'} subtitle={o?.contact && o.contact !== o.name ? `Contact: ${o.contact}` : undefined}>
      <BackLink onBack={onBack} />
      {!o ? <Skeleton className="h-48" /> : (
        <div className="space-y-5">
          <Panel title="Contact">
            <div className="space-y-2 text-sm">
              {o.email && <a className="flex items-center gap-2 hover:underline" href={`mailto:${o.email}`}><Mail className="h-4 w-4 text-graphite-500" />{o.email}</a>}
              {o.phone && <a className="flex items-center gap-2 hover:underline" href={`tel:${o.phone}`}><Phone className="h-4 w-4 text-graphite-500" />{o.phone}</a>}
            </div>
          </Panel>
          <Panel title="Terms">
            <KeyValues cols={2} items={[
              { label: 'Repairs they can approve up to', value: fmt.usd(o.approval_limit) }, { label: 'Management fee', value: `${o.management_fee_pct}%` },
              { label: 'Properties', value: o.properties }, { label: 'Units', value: o.units },
              ...(o.bank_last4 !== undefined ? [{ label: 'Payout account', value: o.bank_last4 ?? 'Not set' }] : []),
            ]} />
          </Panel>
          <Panel title={`Properties (${mine.length})`} padded={false}>
            <ul className="divide-y divide-limestone-200 text-sm">
              {mine.map((p) => (
                <li key={p.id} className="flex items-center justify-between gap-3 px-5 py-3">
                  <div className="min-w-0">
                    <button className="font-semibold text-federal-800 hover:underline" onClick={() => openProperty(p.id)}>{p.name}</button>
                    <div className="truncate text-xs text-graphite-500">{p.city}, {p.state} · {p.occupied}/{p.units} occupied</div>
                  </div>
                  <span className="tabular-nums text-graphite-700">{fmt.usd(p.rent_roll)}</span>
                </li>
              ))}
              {mine.length === 0 && <li className="px-5 py-4 text-graphite-500">No properties in your view.</li>}
            </ul>
          </Panel>
        </div>
      )}
    </Drawer>
  );
}
