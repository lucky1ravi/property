import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { BedDouble, Bath, Ruler, MapPin, Search, Building2, CalendarCheck, SlidersHorizontal, X, ChevronLeft, ChevronRight, LayoutGrid, Home, Building } from 'lucide-react';
import { Button, Field, Input, Select, Skeleton, Carousel, EmptyState, useApi, fmt, Stars, AmenityIcon, Badge } from '@propertyos/ui';
import { useBase } from '../common';

const PAGE_SIZE = 12;

type View = 'properties' | 'homes';

const SORTS: Record<View, { value: string; label: string }[]> = {
  properties: [
    { value: 'published_at:desc', label: 'Newest' }, { value: 'min_rent:asc', label: 'Rent: low to high' }, { value: 'min_rent:desc', label: 'Rent: high to low' },
    { value: 'units_available:desc', label: 'Most homes available' }, { value: 'next_available:asc', label: 'Available soonest' }, { value: 'name:asc', label: 'Name A to Z' },
  ],
  homes: [
    { value: 'published_at:desc', label: 'Newest' }, { value: 'rent:asc', label: 'Rent: low to high' }, { value: 'rent:desc', label: 'Rent: high to low' },
    { value: 'sqft:desc', label: 'Largest first' }, { value: 'available_date:asc', label: 'Available soonest' },
  ],
};

/** The filters the page offers; each is kept in the address so a search can be shared, bookmarked and returned to with the back button. */
const FILTERS = ['keyword', 'city', 'type', 'beds', 'baths', 'minRent', 'maxRent', 'minSqft', 'maxSqft', 'availableBy', 'amenities'] as const;

const typeName = (t: string) => t.charAt(0) + t.slice(1).toLowerCase().replace(/_/g, ' ');
const bedsText = (n: number | string) => (Number(n) === 0 ? 'Studio' : `${n} bd`);

function Hero() {
  return (
    <div className="relative mb-6 overflow-hidden rounded-lg bg-federal-950">
      <img src="https://images.unsplash.com/photo-1560518883-ce09059eeffa?w=1800&q=70" alt="" className="absolute inset-0 h-full w-full object-cover opacity-35" />
      <div className="relative px-6 py-10 md:px-8 md:py-14">
        <motion.h1 initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6 }} className="max-w-[20ch] text-[34px] font-extrabold leading-[1.05] tracking-[-0.02em] text-white md:text-[48px]">
          Find a home you’ll want to stay in
        </motion.h1>
        <p className="mt-3 max-w-[56ch] text-base text-federal-100 md:text-lg">Browse available rentals, tour in person or virtually, and apply online. Most applicants hear back within a day.</p>
      </div>
    </div>
  );
}

/** Photos for a card, or a plain picture of a building when there are none. */
function Photos({ photos, alt }: { photos: string[]; alt: string }) {
  if (!photos.length) return <div className="flex aspect-[4/3] items-center justify-center bg-limestone-200 text-limestone-500"><Building2 size={40} aria-hidden /></div>;
  return <Carousel images={photos} alt={alt} aspect="aspect-[4/3]" className="!rounded-none" />;
}

function Rating({ avg, count }: { avg: any; count: any }) {
  return Number(count) > 0 ? <Stars value={avg} count={count} size={13} /> : <span className="text-xs text-graphite-500">No ratings yet</span>;
}

function Company({ name }: { name?: string }) {
  return name ? <div className="mt-1 flex items-center gap-1 text-xs text-graphite-500"><Building size={12} aria-hidden />Listed by {name}</div> : null;
}

/** One property, with the homes available inside it. */
function PropertyCard({ p, base }: { p: any; base: string }) {
  const [all, setAll] = useState(false);
  const units: any[] = p.units ?? [];
  const shown = all ? units : units.slice(0, 3);
  const type = p.type ? typeName(p.type) : null;
  const only = units.length === 1 ? units[0] : null;
  return (
    <article className="overflow-hidden rounded-md border border-limestone-200 bg-white shadow-panel" data-testid="property-card" aria-label={p.name}>
      <Photos photos={p.photos ?? []} alt={p.name} />
      <div className="p-4">
        <div className="flex items-start justify-between gap-2">
          <h2 className="min-w-0 text-base font-extrabold text-graphite-950">{only ? <Link to={`${base}/${only.id}`} className="hover:underline">{p.name}</Link> : p.name}</h2>
          <Rating avg={p.rating_avg} count={p.rating_count} />
        </div>
        <div className="mt-0.5 flex items-center gap-1 text-xs text-graphite-500"><MapPin size={12} aria-hidden />{p.street}, {p.city}, {p.state}</div>
        <Company name={p.company_name} />
        <div className="mt-3 flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <span className="text-xl font-extrabold text-graphite-950">{units.length > 1 && Number(p.min_rent) !== Number(p.max_rent) ? `From ${fmt.usd(p.min_rent)}` : fmt.usd(p.min_rent)}<span className="text-sm font-medium text-graphite-500"> /mo</span></span>
          <Badge>{Number(p.units_available)} {Number(p.units_available) === 1 ? 'home' : 'homes'} available</Badge>
          {type && <span className="text-xs text-graphite-500">{type}</span>}
        </div>
        <ul className="mt-3 divide-y divide-limestone-200 rounded border border-limestone-200 text-sm" aria-label={`Homes available at ${p.name}`}>
          {shown.map((u) => (
            <li key={u.id}>
              <Link to={`${base}/${u.id}`} className="flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 px-3 py-2 hover:bg-federal-50">
                <span className="flex items-center gap-3 text-graphite-700">
                  <span className="w-12 font-semibold text-graphite-900">{u.unit_number ? `#${u.unit_number}` : 'Home'}</span>
                  <span className="flex items-center gap-1"><BedDouble size={13} aria-hidden />{bedsText(u.beds)}</span>
                  <span className="flex items-center gap-1"><Bath size={13} aria-hidden />{u.baths}</span>
                  <span className="hidden items-center gap-1 sm:flex"><Ruler size={13} aria-hidden />{Number(u.sqft).toLocaleString('en-US')}</span>
                </span>
                <span className="flex items-baseline gap-2"><span className="font-bold text-graphite-950">{fmt.usd(u.rent)}</span><span className="text-xs text-graphite-500">{u.available_date ? fmt.shortDate(u.available_date) : 'Now'}</span></span>
              </Link>
            </li>
          ))}
        </ul>
        {units.length > 3 && <button type="button" className="mt-2 text-sm font-semibold text-federal-800 hover:underline" onClick={() => setAll(!all)} aria-expanded={all}>{all ? 'Show fewer homes' : `Show all ${units.length} homes`}</button>}
        {(p.amenities ?? []).length > 0 && <p className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-xs text-graphite-600" aria-label="Amenities">{(p.amenities as string[]).slice(0, 4).map((a) => <span key={a}>{a}</span>)}{p.amenities.length > 4 && <span>+{p.amenities.length - 4} more</span>}</p>}
      </div>
    </article>
  );
}

/** One home. */
function HomeCard({ l, base }: { l: any; base: string }) {
  return (
    <article className="overflow-hidden rounded-md border border-limestone-200 bg-white shadow-panel" data-testid="home-card" aria-label={l.title}>
      <Photos photos={l.photos ?? []} alt={l.title} />
      <Link to={`${base}/${l.id}`} className="block p-4 hover:bg-federal-50/40">
        <div className="flex items-baseline justify-between gap-2"><span className="text-xl font-extrabold text-graphite-950">{fmt.usd(l.rent)}<span className="text-sm font-medium text-graphite-500"> /mo</span></span><span className="text-xs text-graphite-500">Available {l.available_date ? fmt.shortDate(l.available_date) : 'now'}</span></div>
        <div className="mt-1 text-sm font-semibold text-graphite-800">{l.property_name}{l.unit_number ? <span className="font-normal text-graphite-500"> · #{l.unit_number}</span> : null}</div>
        <div className="mt-0.5 flex items-center gap-1 text-xs text-graphite-500"><MapPin size={12} aria-hidden />{l.street}, {l.city}, {l.state}</div>
        <Company name={l.company_name} />
        <div className="mt-3 flex items-center justify-between gap-3 text-sm text-graphite-700">
          <span className="flex gap-4"><span className="flex items-center gap-1"><BedDouble size={14} aria-hidden />{bedsText(l.beds)}</span><span className="flex items-center gap-1"><Bath size={14} aria-hidden />{l.baths} ba</span><span className="flex items-center gap-1"><Ruler size={14} aria-hidden />{Number(l.sqft).toLocaleString('en-US')} sqft</span></span>
          <Rating avg={l.property_rating} count={l.property_rating_count} />
        </div>
      </Link>
    </article>
  );
}

/** Previous, next and the page numbers near the current one. */
function Pager({ page, pages, onPage }: { page: number; pages: number; onPage: (n: number) => void }) {
  if (pages <= 1) return null;
  const wanted = new Set([1, pages, page - 1, page, page + 1].filter((n) => n >= 1 && n <= pages));
  const list = [...wanted].sort((a, b) => a - b);
  return (
    <nav aria-label="Pages" className="mt-8 flex flex-wrap items-center justify-center gap-1.5">
      <Button size="sm" variant="secondary" disabled={page <= 1} onClick={() => onPage(page - 1)} icon={<ChevronLeft size={14} />}>Previous</Button>
      {list.map((n, i) => (
        <span key={n} className="flex items-center gap-1.5">
          {i > 0 && n - list[i - 1] > 1 && <span className="px-1 text-graphite-400" aria-hidden>…</span>}
          <button type="button" aria-label={`Page ${n}`} aria-current={n === page ? 'page' : undefined} onClick={() => onPage(n)}
            className={`h-8 min-w-8 rounded px-2 text-sm font-semibold ${n === page ? 'bg-federal-900 text-white' : 'border border-limestone-300 bg-white text-graphite-800 hover:bg-limestone-100'}`}>{n}</button>
        </span>
      ))}
      <Button size="sm" variant="secondary" disabled={page >= pages} onClick={() => onPage(page + 1)}>Next<ChevronRight size={14} aria-hidden /></Button>
    </nav>
  );
}

export function SearchPage() {
  const base = useBase();
  const [sp, setSp] = useSearchParams();
  const view: View = sp.get('view') === 'homes' ? 'homes' : 'properties';
  const page = Math.max(1, Number(sp.get('page')) || 1);
  const sort = SORTS[view].some((s) => s.value === sp.get('sort')) ? sp.get('sort')! : SORTS[view][0].value;
  const [more, setMore] = useState(() => ['baths', 'minRent', 'minSqft', 'maxSqft', 'availableBy', 'amenities', 'type', 'city'].some((k) => sp.get(k)));
  const [keyword, setKeyword] = useState(sp.get('keyword') ?? '');
  // The search box applies after a short pause in typing; everything else applies at once.
  useEffect(() => { const t = setTimeout(() => { if ((sp.get('keyword') ?? '') !== keyword.trim()) update({ keyword: keyword.trim() }); }, 350); return () => clearTimeout(t); }, [keyword]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setKeyword(sp.get('keyword') ?? ''); }, [sp.get('keyword')]); // eslint-disable-line react-hooks/exhaustive-deps

  /** Change some filters and go back to the first page. Empty values are removed from the address. */
  const update = (changes: Record<string, string | null | undefined>, keepPage = false) => {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(changes)) { if (v) next.set(k, v); else next.delete(k); }
    if (!keepPage) next.delete('page');
    setSp(next, { replace: true });
  };
  const get = (k: string) => sp.get(k) ?? '';
  const chosen = useMemo(() => (get('amenities') ? get('amenities').split(',').filter(Boolean) : []), [sp]); // eslint-disable-line react-hooks/exhaustive-deps

  const { data: facets } = useApi<any>('/api/leasing/public/facets');
  const [col, dir] = sort.split(':');
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE), sort: col, dir });
  FILTERS.forEach((k) => { if (sp.get(k)) query.set(k, sp.get(k)!); });
  const { data, isLoading, isFetching } = useApi<any>(`/api/leasing/public/${view === 'homes' ? 'listings' : 'properties'}?${query.toString()}`);
  const rows: any[] = data?.rows ?? [];
  const total: number = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  // A shared link can point past the end once listings change; go to the last page rather than show nothing.
  useEffect(() => { if (data && total > 0 && page > pages) update({ page: String(pages) }, true); }, [data, total, page, pages]); // eslint-disable-line react-hooks/exhaustive-deps

  const active = FILTERS.filter((k) => sp.get(k));
  const label = (k: string): string => {
    const v = get(k);
    switch (k) {
      case 'keyword': return `“${v}”`;
      case 'city': return v; case 'type': return typeName(v);
      case 'beds': return v === '0' ? 'Studio or larger' : `${v}+ beds`;
      case 'baths': return `${v}+ baths`;
      case 'minRent': return `From ${fmt.usd(v)}`; case 'maxRent': return `Up to ${fmt.usd(v)}`;
      case 'minSqft': return `${Number(v).toLocaleString('en-US')}+ sq ft`; case 'maxSqft': return `Up to ${Number(v).toLocaleString('en-US')} sq ft`;
      case 'availableBy': return `Available by ${fmt.shortDate(v)}`;
      default: return chosen.join(', ');
    }
  };
  const toggleAmenity = (name: string) => update({ amenities: (chosen.includes(name) ? chosen.filter((a) => a !== name) : [...chosen, name]).join(',') });
  const clearAll = () => { setKeyword(''); setSp(new URLSearchParams(view === 'homes' ? { view: 'homes' } : {}), { replace: true }); };
  const noun = view === 'homes' ? (total === 1 ? 'home' : 'homes') : (total === 1 ? 'property' : 'properties');
  const first = total === 0 ? 0 : (page - 1) * PAGE_SIZE + 1, last = Math.min(total, page * PAGE_SIZE);

  return (
    <>
      <Hero />
      <form className="mb-4 rounded-md border border-limestone-200 bg-white p-4 shadow-panel" onSubmit={(e) => { e.preventDefault(); update({ keyword: keyword.trim() }); }} role="search" aria-label="Search homes">
        <div className="grid gap-3 md:grid-cols-[minmax(0,2fr)_repeat(3,minmax(0,1fr))]">
          <Field label="Search"><Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="Property, street, city or ZIP" aria-label="Search by property, street, city or ZIP" /></Field>
          <Field label="Bedrooms"><Select value={get('beds')} onChange={(e) => update({ beds: e.target.value })}><option value="">Any</option><option value="0">Studio+</option><option value="1">1+</option><option value="2">2+</option><option value="3">3+</option><option value="4">4+</option></Select></Field>
          <Field label="Max rent"><Select value={get('maxRent')} onChange={(e) => update({ maxRent: e.target.value })}><option value="">Any</option>{[1000, 1500, 2000, 2500, 3000, 3500, 5000].map((m) => <option key={m} value={m}>{fmt.usd(m)}</option>)}</Select></Field>
          <Field label="Property type"><Select value={get('type')} onChange={(e) => update({ type: e.target.value })}><option value="">Any</option>{(facets?.types ?? []).map((t: any) => <option key={t.name} value={t.name}>{typeName(t.name)} ({t.homes})</option>)}</Select></Field>
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
          <button type="button" className="inline-flex items-center gap-1.5 text-sm font-semibold text-federal-800 hover:underline" aria-expanded={more} onClick={() => setMore(!more)}><SlidersHorizontal size={14} aria-hidden />{more ? 'Fewer filters' : 'More filters'}</button>
          <Button variant="primary" icon={<Search size={14} />} type="submit">Search</Button>
        </div>
        {more && (
          <div className="mt-4 space-y-4 border-t border-limestone-200 pt-4">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Field label="City"><Select value={get('city')} onChange={(e) => update({ city: e.target.value })}><option value="">Any</option>{(facets?.cities ?? []).map((c: any) => <option key={`${c.name}${c.state}`} value={c.name}>{c.name}, {c.state} ({c.homes})</option>)}</Select></Field>
              <Field label="Bathrooms"><Select value={get('baths')} onChange={(e) => update({ baths: e.target.value })}><option value="">Any</option><option value="1">1+</option><option value="1.5">1.5+</option><option value="2">2+</option><option value="3">3+</option></Select></Field>
              <Field label="Lowest rent ($)"><Input type="number" min={0} inputMode="numeric" value={get('minRent')} onChange={(e) => update({ minRent: e.target.value })} placeholder={facets?.min_rent ? String(Math.floor(Number(facets.min_rent))) : ''} /></Field>
              <Field label="Available by"><Input type="date" value={get('availableBy')} onChange={(e) => update({ availableBy: e.target.value })} /></Field>
              <Field label="Smallest (sq ft)"><Input type="number" min={0} inputMode="numeric" value={get('minSqft')} onChange={(e) => update({ minSqft: e.target.value })} /></Field>
              <Field label="Largest (sq ft)"><Input type="number" min={0} inputMode="numeric" value={get('maxSqft')} onChange={(e) => update({ maxSqft: e.target.value })} /></Field>
            </div>
            {(facets?.amenities ?? []).length > 0 && (
              <fieldset>
                <legend className="mb-2 text-sm font-semibold text-graphite-800">Amenities <span className="font-normal text-graphite-500">(homes must have all you choose)</span></legend>
                <div className="flex flex-wrap gap-2">
                  {(facets.amenities as any[]).map((a) => {
                    const on = chosen.includes(a.name);
                    return (
                      <button type="button" key={a.name} aria-pressed={on} onClick={() => toggleAmenity(a.name)}
                        className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm ${on ? 'border-federal-700 bg-federal-50 font-semibold text-federal-900' : 'border-limestone-300 bg-white text-graphite-700 hover:bg-limestone-100'}`}>
                        <AmenityIcon icon={a.icon} name={a.name} size={14} />{a.name}<span className="text-xs text-graphite-500">{a.homes}</span>
                      </button>
                    );
                  })}
                </div>
              </fieldset>
            )}
          </div>
        )}
      </form>

      {active.length > 0 && (
        <div className="mb-4 flex flex-wrap items-center gap-2" aria-label="Filters in use">
          {active.map((k) => (
            <span key={k} className="inline-flex items-center gap-1 rounded-full border border-limestone-300 bg-white py-0.5 pl-3 pr-1 text-sm text-graphite-800">
              {label(k)}
              <button type="button" aria-label={`Remove filter ${label(k)}`} className="rounded-full p-1 text-graphite-500 hover:bg-limestone-200 hover:text-graphite-900" onClick={() => { if (k === 'keyword') setKeyword(''); update({ [k]: null }); }}><X size={12} /></button>
            </span>
          ))}
          <button type="button" className="text-sm font-semibold text-federal-800 hover:underline" onClick={clearAll}>Clear all</button>
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-graphite-700" aria-live="polite" data-testid="result-count">
          {isLoading ? 'Searching…' : total === 0 ? `No ${noun} found` : <>Showing <strong>{first}–{last}</strong> of <strong>{total.toLocaleString('en-US')}</strong> {noun}</>}
          {!isLoading && view === 'properties' && total > 0 && facets?.homes ? <span className="text-graphite-500"> · {Number(facets.homes).toLocaleString('en-US')} homes available in all</span> : null}
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex overflow-hidden rounded border border-limestone-300" role="group" aria-label="How to list results">
            {([['properties', 'By property', Building2], ['homes', 'All homes', Home]] as const).map(([v, text, Icon]) => (
              <button key={v} type="button" aria-pressed={view === v} onClick={() => { const n = new URLSearchParams(sp); n.delete('page'); n.delete('sort'); if (v === 'homes') n.set('view', 'homes'); else n.delete('view'); setSp(n, { replace: true }); }}
                className={`inline-flex h-9 items-center gap-1.5 px-3 text-sm font-semibold ${view === v ? 'bg-federal-900 text-white' : 'bg-white text-graphite-800 hover:bg-limestone-100'}`}><Icon size={14} aria-hidden />{text}</button>
            ))}
          </div>
          <label className="flex items-center gap-2 text-sm text-graphite-700">Sort<Select aria-label="Sort results" value={sort} onChange={(e) => update({ sort: e.target.value === SORTS[view][0].value ? null : e.target.value })}>{SORTS[view].map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}</Select></label>
        </div>
      </div>

      {isLoading ? <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3"><Skeleton className="h-96" /><Skeleton className="h-96" /><Skeleton className="h-96" /></div>
        : rows.length === 0 ? <EmptyState icon={<LayoutGrid />} title="No homes match your search" body={active.length ? 'Try removing a filter, or widen the rent or size.' : 'There are no homes available right now. Check back soon.'} action={active.length ? <Button onClick={clearAll}>Clear all filters</Button> : undefined} />
        : (
          <div className={`grid items-start gap-5 sm:grid-cols-2 lg:grid-cols-3 ${isFetching ? 'opacity-70 transition-opacity' : ''}`}>
            {rows.map((r) => (view === 'homes' ? <HomeCard key={r.id} l={r} base={base} /> : <PropertyCard key={r.id} p={r} base={base} />))}
          </div>
        )}
      <Pager page={Math.min(page, pages)} pages={pages} onPage={(n) => { update({ page: n === 1 ? null : String(n) }, true); window.scrollTo({ top: 0, behavior: 'smooth' }); }} />
      <p className="mt-6 text-center text-xs text-graphite-500"><CalendarCheck className="mr-1 inline" size={12} aria-hidden />Availability and rent are set by each company and can change.</p>
    </>
  );
}
