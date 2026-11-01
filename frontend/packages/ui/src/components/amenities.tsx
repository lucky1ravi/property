import { useMemo, useState } from 'react';
import clsx from 'clsx';
import {
  ArrowUpDown, Baby, BedDouble, Bike, Bath, Briefcase, Building2, Bus, Car, CarFront, CircleCheck, Coffee, Dog, Dumbbell, Fan, Flame, Flower2, Gamepad2, Gem, Heater,
  Hotel, KeyRound, Landmark, Layers, Lamp, Lock, Microwave, Mountain, PackageOpen, Package, PawPrint, Phone, PlugZap, Plus, Projector, Refrigerator, Search, ShieldCheck, ShowerHead,
  Snowflake, Sofa, Sun, Tent, Trash2, Trees, Tv, Users, Utensils, Video, Warehouse, WashingMachine, Waves, Wifi, Wind, Wine, Wrench, type LucideIcon,
} from 'lucide-react';
import { useApi, useAction } from '../lib/hooks';
import { api } from '../lib/api';
import { Button, Field, Input, Badge, Toggle } from './primitives';
import { Modal, ConfirmDialog } from './overlay';
import { DataTable, type Column } from './DataTable';
import { RowActions } from './RowActions';
import { MultiEntityPicker } from './pickers';
import { PageHeader } from './layout';

/**
 * The icons an amenity can use. A fixed set keeps the product small (it never loads the whole icon library) and means a stored icon name
 * always draws something. The name is what is saved with the amenity.
 */
export const AMENITY_ICONS: Record<string, LucideIcon> = {
  ArrowUpDown, Baby, BedDouble, Bike, Bath, Briefcase, Building2, Bus, Car, CarFront, CircleCheck, Coffee, Dog, Dumbbell, Fan, Flame, Flower2, Gamepad2, Gem, Heater, Hotel, KeyRound,
  Landmark, Layers, Lamp, Lock, Microwave, Mountain, PackageOpen, Package, PawPrint, Phone, PlugZap, Projector, Refrigerator, ShieldCheck, ShowerHead, Snowflake, Sofa, Sun, Tent,
  Trash2, Trees, Tv, Users, Utensils, Video, Warehouse, WashingMachine, Waves, Wifi, Wind, Wine, Wrench,
};

const ICON_NAMES = Object.keys(AMENITY_ICONS);

/** For an amenity that is not in the catalogue (typed earlier, or from before the catalogue), a picture chosen by the words in its name. */
const GUESSES: [RegExp, string][] = [
  [/park|garage|carport/i, 'Car'], [/pool|swim|spa|hot tub/i, 'Waves'], [/gym|fitness|workout/i, 'Dumbbell'], [/laundry|washer|dryer/i, 'WashingMachine'],
  [/pet|dog|cat/i, 'PawPrint'], [/air|a\/c|\bac\b|cool/i, 'Snowflake'], [/heat|fireplace|furnace/i, 'Flame'], [/dishwasher|kitchen|stove|oven/i, 'Utensils'],
  [/microwave/i, 'Microwave'], [/fridge|refrigerator/i, 'Refrigerator'], [/balcon|patio|deck|terrace|sun|view/i, 'Sun'], [/storage|closet|pantry|locker|package/i, 'Package'],
  [/elevator|lift/i, 'ArrowUpDown'], [/wi-?fi|internet|fiber|cable/i, 'Wifi'], [/secur|gated|camera|doorman|concierge|key/i, 'ShieldCheck'], [/\bev\b|charg|electric|solar/i, 'PlugZap'],
  [/bike|bicycle/i, 'Bike'], [/hardwood|floor|carpet|tile/i, 'Layers'], [/club|lounge|community|party|rooftop/i, 'Users'], [/garden|yard|lawn|trail|green|tree/i, 'Trees'],
  [/furnish|sofa/i, 'Sofa'], [/trash|waste|recycl/i, 'Trash2'],
];

/** An amenity's icon: the stored one, else a guess from its name, else a tick, so a list never has a gap. */
export function AmenityIcon({ icon, name, size = 16, className }: { icon?: string | null; name?: string; size?: number; className?: string }) {
  const key = icon && AMENITY_ICONS[icon] ? icon : name ? GUESSES.find(([re]) => re.test(name))?.[1] : undefined;
  const Icon = (key && AMENITY_ICONS[key]) || CircleCheck;
  return <Icon size={size} aria-hidden className={className} />;
}

/** Spaces in front of capitals, so "WashingMachine" can be searched as "washing machine". */
const words = (n: string) => n.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();

/** Choose an icon from the set, with a search box. */
export function IconPicker({ value, onChange }: { value: string; onChange: (icon: string) => void }) {
  const [q, setQ] = useState('');
  const shown = useMemo(() => ICON_NAMES.filter((n) => words(n).includes(q.trim().toLowerCase())), [q]);
  return (
    <div>
      <div className="flex min-h-9 items-center gap-2 rounded border border-limestone-300 bg-white px-3 text-sm focus-within:border-federal-700 focus-within:ring-2 focus-within:ring-federal-700/15">
        <Search className="h-4 w-4 text-graphite-400" aria-hidden />
        <input aria-label="Search icons" className="h-9 min-w-0 flex-1 bg-transparent outline-none placeholder:text-graphite-400" placeholder="Search icons, for example pool or parking" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <ul className="mt-2 grid max-h-44 grid-cols-6 gap-1.5 overflow-y-auto sm:grid-cols-8" role="listbox" aria-label="Icons">
        {shown.map((n) => (
          <li key={n}>
            <button type="button" role="option" aria-selected={value === n} aria-label={words(n)} title={words(n)} onClick={() => onChange(n)}
              className={clsx('flex h-10 w-full items-center justify-center rounded border', value === n ? 'border-federal-700 bg-federal-50 text-federal-900' : 'border-limestone-200 text-graphite-700 hover:bg-limestone-100')}>
              <AmenityIcon icon={n} size={18} />
            </button>
          </li>
        ))}
      </ul>
      {shown.length === 0 && <p className="mt-2 text-sm text-graphite-600">No icon matches “{q.trim()}”.</p>}
    </div>
  );
}

interface Amenity { id: string; name: string; icon: string; active?: boolean; platform?: boolean }

/** Name and icon of one amenity, for adding or changing it. */
export function AmenityDialog({ open, onClose, existing, endpoint, onSaved }: { open: boolean; onClose: () => void; existing?: Amenity | null; endpoint: string; onSaved?: (a: Amenity) => void }) {
  const [name, setName] = useState(existing?.name ?? '');
  const [icon, setIcon] = useState(existing?.icon ?? 'CircleCheck');
  const [seen, setSeen] = useState<string | null>(null);
  // Start fresh each time the dialog opens, for a different amenity or a new one.
  const key = open ? (existing?.id ?? 'new') : null;
  if (key !== seen) { setSeen(key); if (open) { setName(existing?.name ?? ''); setIcon(existing?.icon ?? 'CircleCheck'); } }
  const save = useAction<void, Amenity>(() => (existing ? api.put(`${endpoint}/${existing.id}`, { name, icon }) : api.post(endpoint, { name, icon })),
    { success: existing ? 'Amenity updated' : 'Amenity added', invalidate: [endpoint, '/api/leasing/amenities'], onSuccess: (a) => { onSaved?.(a); onClose(); } });
  return (
    <Modal open={open} onClose={onClose} title={existing ? 'Edit amenity' : 'Add an amenity'}
      footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={name.trim().length < 2} onClick={() => save.mutate(undefined as never)}>Save</Button></>}>
      <div className="space-y-4">
        <Field label="Name" required hint="For example Dog wash, Business centre, Storage cage."><Input value={name} maxLength={60} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Icon" required><IconPicker value={icon} onChange={setIcon} /></Field>
        <p className="flex items-center gap-2 text-sm text-graphite-700">Shown as <span className="inline-flex items-center gap-2 rounded-full bg-federal-50 py-1 pl-1 pr-3 font-medium text-federal-900"><span className="flex h-6 w-6 items-center justify-center rounded-full bg-white"><AmenityIcon icon={icon} size={14} /></span>{name.trim() || 'Amenity'}</span></p>
      </div>
    </Modal>
  );
}

/**
 * Choose a property's amenities by searching the catalogue; each shows with its icon. Anyone who can edit properties can also add a new
 * amenity here, which is then available to the rest of the company.
 */
export function AmenityPicker({ value, onChange, canAdd }: { value: string[]; onChange: (names: string[]) => void; canAdd?: boolean }) {
  const { data } = useApi<Amenity[]>('/api/leasing/amenities');
  const icons = useMemo(() => new Map((data ?? []).map((a) => [a.name, a.icon])), [data]);
  const [adding, setAdding] = useState(false);
  // A property can keep an amenity that was later removed from the catalogue; it still shows, with the default tick.
  const known = value.map((n) => ({ id: n, name: n, icon: icons.get(n) ?? 'CircleCheck' }));
  return (
    <div>
      <MultiEntityPicker<Amenity> endpoint="/api/leasing/amenities" value={value} selected={known} onChange={(ids) => onChange(ids)} placeholder="Search amenities, for example pool"
        getId={(a) => a.name} label={(a) => a.name} icon={(a) => <AmenityIcon icon={icons.get(a.name) ?? a.icon} name={a.name} size={15} />} hint="Not in the list? Add it below." max={40} />
      {canAdd && <button type="button" className="mt-2 inline-flex items-center gap-1 text-sm font-semibold text-federal-800 hover:underline" onClick={() => setAdding(true)}><Plus className="h-4 w-4" aria-hidden />Add a new amenity</button>}
      <AmenityDialog open={adding} onClose={() => setAdding(false)} endpoint="/api/leasing/amenities" onSaved={(a) => onChange([...value, a.name])} />
    </div>
  );
}

/**
 * The amenity list page. `platform` manages the list every company starts from; otherwise it is the company's own additions, shown
 * under the platform's (which it can see but not change).
 */
export function AmenityManager({ platform }: { platform?: boolean }) {
  const endpoint = platform ? '/api/leasing/platform/amenities' : '/api/leasing/amenities';
  const { data, isLoading } = useApi<Amenity[]>(platform ? endpoint : '/api/leasing/amenities/mine');
  const { data: shared } = useApi<Amenity[]>(platform ? null : '/api/leasing/amenities');
  const [editing, setEditing] = useState<Amenity | null>(null);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Amenity | null>(null);
  const change = useAction((a: { id: string; body: any }) => api.put(`${endpoint}/${a.id}`, a.body), { success: 'Amenity updated', invalidate: [endpoint, '/api/leasing/amenities', '/api/leasing/amenities/mine'] });
  const remove = useAction((a: Amenity) => api.del(`${endpoint}/${a.id}`), { success: 'Amenity removed', invalidate: [endpoint, '/api/leasing/amenities', '/api/leasing/amenities/mine'], onSuccess: () => setRemoving(null) });
  const columns: Column<Amenity>[] = [
    { key: 'icon', header: '', width: '3.5rem', filter: false, render: (a) => <span className="flex h-8 w-8 items-center justify-center rounded-full bg-federal-50 text-federal-800"><AmenityIcon icon={a.icon} /></span> },
    { key: 'name', header: 'Amenity', value: (a) => a.name, sortable: true, render: (a) => <span className="font-semibold text-graphite-900">{a.name}</span> },
    { key: 'active', header: 'Offered', width: '6rem', filter: false, value: (a) => (a.active ? 1 : 0), render: (a) => <Toggle checked={!!a.active} onChange={(v) => change.mutate({ id: a.id, body: { active: v } })} label={`Offer ${a.name} on property forms`} /> },
    { key: 'edit', header: '', width: '5rem', filter: false, render: (a) => <RowActions name={a.name} onEdit={() => setEditing(a)} onDelete={() => setRemoving(a)} /> },
  ];
  const platformRows = (shared ?? []).filter((a) => a.platform);
  return (
    <>
      <PageHeader title="Amenities" description={platform ? 'The amenities every company can pick for a property. Companies can add their own on top of these.' : 'Add your own amenities to the ones PropertyOS offers. Pick them on a property and they show with their icon on the public listing.'}
        actions={<Button variant="primary" onClick={() => setAdding(true)}><Plus className="h-4 w-4" aria-hidden />Add an amenity</Button>} />
      <DataTable<Amenity> rows={data} loading={isLoading} columns={columns} rowKey={(a) => a.id} searchable searchPlaceholder="Search amenities" exportName="amenities"
        empty={{ title: platform ? 'No platform amenities' : 'You have not added any amenities yet', body: platform ? undefined : 'The PropertyOS list below is already available on every property. Add one only if yours is missing.' }} />
      {!platform && platformRows.length > 0 && (
        <section className="mt-6" aria-label="Offered by PropertyOS">
          <h2 className="mb-2 text-sm font-semibold text-graphite-800">Offered by PropertyOS <Badge>{platformRows.length}</Badge></h2>
          <ul className="grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-3 lg:grid-cols-4">
            {platformRows.map((a) => <li key={a.id} className="flex items-center gap-2 text-sm text-graphite-800"><span className="flex h-7 w-7 items-center justify-center rounded-full bg-limestone-100 text-graphite-700"><AmenityIcon icon={a.icon} size={14} /></span>{a.name}</li>)}
          </ul>
        </section>
      )}
      <AmenityDialog open={adding || !!editing} existing={editing} endpoint={endpoint} onClose={() => { setAdding(false); setEditing(null); }} />
      <ConfirmDialog open={!!removing} title={`Remove ${removing?.name ?? 'this amenity'}?`} body="Properties that already have it keep it. It is no longer offered when picking amenities." confirmLabel="Remove" tone="danger" loading={remove.isPending}
        onClose={() => setRemoving(null)} onConfirm={() => removing && remove.mutate(removing)} />
    </>
  );
}
