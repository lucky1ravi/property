import { useEffect, useState, type ReactNode } from 'react';
import { Link, Route, Routes, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { motion, useScroll, useTransform } from 'framer-motion';
import { ChevronDown, BedDouble, Bath, Ruler, MapPin, Search, UploadCloud, CheckCircle2, CircleAlert, Building2, CalendarDays, CalendarCheck, Car, Waves, Dumbbell, WashingMachine, PawPrint, Snowflake, Flame, Utensils, Sun, Package, ArrowUpDown, Wifi, ShieldCheck, PlugZap, Bike, Layers, Users, Trees, Trash2, Sparkles, DollarSign, Wallet, Home, Sofa, Microwave, Refrigerator, Check, Key, TrendingDown, Phone, Mail, Globe, type LucideIcon } from 'lucide-react';
import { Button, Field, Input, Select, Textarea, Skeleton, Carousel, EmptyState, Panel, AmenityIcon, useApi, useAction, api, fmt, toastError, errorText, Stars } from '@propertyos/ui';
import { useBase } from '../common';
import { SearchPage } from './PublicSearch';

/** One fact about the home with its icon: "2 bedrooms", "Built 2005". */
/** A campaign's tracking link is /homes?cmp=CODE. The code is kept in this browser so it still counts when the visitor browses on before enquiring. */
const CAMPAIGN_KEY = 'pos.cmp';
function rememberCampaign() {
  try {
    const c = new URLSearchParams(window.location.search).get('cmp');
    if (c && /^[a-z0-9]{1,16}$/i.test(c)) localStorage.setItem(CAMPAIGN_KEY, c);
  } catch { /* storage can be blocked; the visitor simply isn't attributed */ }
}
function trackedCampaign(): string | undefined {
  try { return localStorage.getItem(CAMPAIGN_KEY) ?? undefined; } catch { return undefined; }
}

function Fact({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: ReactNode }) {
  return (
    <div className="flex items-center gap-3 rounded-md border border-limestone-200 bg-white p-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800"><Icon size={20} aria-hidden /></span>
      <span className="min-w-0"><span className="block text-xs text-graphite-500">{label}</span><span className="block truncate text-sm font-bold text-graphite-950">{value}</span></span>
    </div>
  );
}

/** Amenities as a dropdown. Closed, it says how many there are and names the first few; open, it lists them all with their icons. */
function Amenities({ amenities, icons }: { amenities: string[]; icons?: Record<string, string> }) {
  const [open, setOpen] = useState(false);
  const preview = amenities.slice(0, 3).join(', ') + (amenities.length > 3 ? ` and ${amenities.length - 3} more` : '');
  return (<section aria-label="Amenities">
    <h2 className="mb-3 mt-7 text-lg font-bold text-graphite-950">
      <button type="button" id="amenities-toggle" aria-expanded={open} aria-controls="amenities-panel" onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center justify-between gap-3 rounded-md border border-limestone-200 bg-white px-4 py-3 text-left hover:bg-federal-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2">
        <span className="min-w-0">
          <span className="block">Amenities <span className="font-normal text-graphite-500">({amenities.length})</span></span>
          {!open && <span className="mt-0.5 block truncate text-sm font-normal text-graphite-600">{preview}</span>}
        </span>
        <ChevronDown size={18} aria-hidden className={`shrink-0 text-graphite-600 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
    </h2>
    <div id="amenities-panel" role="region" aria-labelledby="amenities-toggle" hidden={!open}>
      <ul className="grid grid-cols-2 gap-x-6 gap-y-3 px-1 sm:grid-cols-3" data-testid="amenities">
        {amenities.map((a) => <li key={a} className="flex items-center gap-2.5 text-sm text-graphite-800"><span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800"><AmenityIcon icon={icons?.[a]} name={a} size={16} /></span>{a}</li>)}
      </ul>
    </div>
  </section>);
}

/** What residents say about the building and the home: the average, how the stars are spread, and the latest written comments. */
function Reviews({ listingId }: { listingId: string }) {
  const { data } = useApi<any>(`/api/leasing/public/listings/${listingId}/reviews`);
  if (!data) return null;
  const groups = [{ key: 'property', title: 'The building', g: data.property }, { key: 'unit', title: 'This home', g: data.unit }].filter((x) => x.g && Number(x.g.count) > 0);
  if (groups.length === 0) return null;
  return (<section aria-label="Resident ratings and comments" data-testid="reviews">
    <h2 className="mb-3 mt-7 text-lg font-bold text-graphite-950">What residents say</h2>
    <div className="space-y-5">
      {groups.map(({ key, title, g }) => {
        const total = Number(g.count);
        const comments = (g.comments ?? []) as any[];
        return (<div key={key} className="rounded-md border border-limestone-200 bg-white p-4" data-testid={`reviews-${key}`}>
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div><div className="text-sm font-semibold text-graphite-900">{title}</div><Stars value={g.average} count={g.count} size={16} className="mt-1" /></div>
            <div className="w-full max-w-xs space-y-1" role="img" aria-label={`Rating spread for ${title.toLowerCase()}`}>
              {[5, 4, 3, 2, 1].map((n) => { const c = Number(g.distribution?.[n] ?? 0); return (<div key={n} className="flex items-center gap-2 text-xs text-graphite-600"><span className="w-12 shrink-0 whitespace-nowrap">{n} star</span><span className="h-2 flex-1 overflow-hidden rounded-full bg-limestone-200"><span className="block h-full rounded-full bg-[#C8963E]" style={{ width: `${(c / total) * 100}%` }} /></span><span className="w-5 text-right">{c}</span></div>); })}
            </div>
          </div>
          {comments.length > 0 ? <ul className="mt-4 divide-y divide-limestone-200">{comments.map((c, i) => (<li key={i} className="py-3 first:pt-0 last:pb-0">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1"><Stars value={c.stars} size={13} /><span className="text-sm font-semibold text-graphite-900">{c.name}</span><span className="text-xs text-graphite-500">{fmt.date(c.created_at)}</span></div>
            <p className="mt-1 max-w-[68ch] text-sm leading-relaxed text-graphite-700">{c.comment}</p>
            {c.reply && <div className="mt-2 max-w-[68ch] rounded border-l-2 border-federal-700 bg-limestone-50 px-3 py-2 text-sm text-graphite-800" data-testid="review-reply"><div className="text-xs font-semibold text-graphite-700">Response from {c.reply_by} · {fmt.date(c.replied_at)}</div>{c.reply}</div>}</li>))}</ul>
            : <p className="mt-3 text-sm text-graphite-500">Residents have rated this but not written a comment yet.</p>}
        </div>);
      })}
    </div>
  </section>);
}

function Detail() {
  const { id } = useParams();
  const base = useBase();
  const { data: l, isLoading } = useApi<any>(`/api/leasing/public/listings/${id}`);
  const [mode, setMode] = useState<'enquire' | 'tour' | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [f, setF] = useState<any>({ name: '', email: '', phone: '', message: '', scheduledAt: '' });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const enquire = useAction(() => api.post<any>(`/api/leasing/public/listings/${id}/enquiries`, { name: f.name, email: f.email, phone: f.phone, message: f.message, campaign: trackedCampaign() }), { silent: true, onSuccess: (r) => setDone(r.reply) });
  const tour = useAction(() => api.post<any>(`/api/leasing/public/listings/${id}/showings`, { name: f.name, email: f.email, type: 'SELF_GUIDED', scheduledAt: new Date(f.scheduledAt).toISOString(), campaign: trackedCampaign() }), { silent: true, onSuccess: () => setDone('Tour requested. We’ll confirm the time by email.') });
  if (isLoading) return <Skeleton className="h-96" />;
  if (!l) return <EmptyState title="This home is no longer available" action={<Link to={base}><Button>See other homes</Button></Link>} />;
  const amenities: string[] = Array.isArray(l.amenities) ? l.amenities : [];
  const perSqft = Number(l.sqft) > 0 ? Number(l.rent) / Number(l.sqft) : null;
  const type = l.property_type ? String(l.property_type).charAt(0) + String(l.property_type).slice(1).toLowerCase().replace(/_/g, ' ') : null;
  const bedsLabel = Number(l.beds) === 0 ? 'Studio' : `${l.beds} bedroom${Number(l.beds) === 1 ? '' : 's'}`;
  return (
    <div>
      <Link to={base} className="mb-4 inline-block text-sm font-semibold text-federal-900 hover:underline">All homes</Link>
      <div className="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_380px]">
        <div>
          <Carousel images={l.photos ?? []} alt={l.title} />
          {(l.photos ?? []).filter(Boolean).length === 0 && <div className="flex aspect-[16/9] items-center justify-center rounded-md bg-limestone-200 text-limestone-500" role="img" aria-label="No photos yet"><Building2 size={48} aria-hidden /></div>}
          <div className="mt-5 flex flex-wrap items-start justify-between gap-3">
            <div>
              {l.sponsored && <span className="mb-1 inline-block rounded bg-graphite-900/85 px-2 py-0.5 text-2xs font-semibold uppercase tracking-wide text-white" data-testid="sponsored">Sponsored</span>}
              <h1 className="text-2xl font-extrabold text-graphite-950">{l.title}</h1>
              <p className="mt-1 flex items-center gap-1 text-graphite-600"><MapPin size={14} />{l.street}, {l.city}, {l.state} {l.zip}</p>
            </div>
            {Number(l.property_rating_count) > 0 && <div className="rounded-md border border-limestone-200 bg-white px-3 py-2 text-right" aria-label="Resident rating of this property">
              <Stars value={l.property_rating} count={l.property_rating_count} size={15} /><div className="mt-0.5 text-2xs text-graphite-500">rated by residents</div></div>}
          </div>

          <h2 className="mb-3 mt-6 text-lg font-bold text-graphite-950">The home</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3" data-testid="home-facts">
            <Fact icon={BedDouble} label="Bedrooms" value={bedsLabel} />
            <Fact icon={Bath} label="Bathrooms" value={`${l.baths} bath${Number(l.baths) === 1 ? '' : 's'}`} />
            <Fact icon={Ruler} label="Size" value={`${Number(l.sqft).toLocaleString('en-US')} sq ft`} />
            {type && <Fact icon={Building2} label="Property type" value={type} />}
            {l.year_built ? <Fact icon={CalendarDays} label="Built" value={l.year_built} /> : null}
            <Fact icon={CalendarCheck} label="Available" value={fmt.date(l.available_date)} />
            {l.unit_number && <Fact icon={Key} label="Unit" value={l.unit_number} />}
            <Fact icon={Wallet} label="Security deposit" value={fmt.usd(l.deposit)} />
            {perSqft && <Fact icon={TrendingDown} label="Rent per sq ft" value={`$${perSqft.toFixed(2)}`} />}
          </div>

          <h2 className="mb-2 mt-7 text-lg font-bold text-graphite-950">About this home</h2>
          <p className="max-w-[68ch] leading-relaxed text-graphite-700">{l.description}</p>
          {(l.highlights ?? []).length > 0 && <ul className="mt-4 flex flex-wrap gap-2">{(l.highlights ?? []).map((h: string) => <li key={h} className="inline-flex items-center gap-1.5 rounded-full border border-limestone-300 bg-white px-3 py-1 text-sm text-graphite-700"><Sparkles size={13} className="text-[#C8963E]" aria-hidden />{h}</li>)}</ul>}

          {amenities.length > 0 && <Amenities amenities={amenities} icons={l.amenity_icons} />}

          {(l.other_homes ?? []).length > 0 && <>
            <h2 className="mb-3 mt-7 text-lg font-bold text-graphite-950">Other homes at {l.property_name}</h2>
            <ul className="divide-y divide-limestone-200 rounded-md border border-limestone-200 bg-white text-sm" data-testid="other-homes">
              {(l.other_homes as any[]).map((o) => (
                <li key={o.id}><Link to={`${base}/${o.id}`} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-4 py-3 hover:bg-federal-50">
                  <span className="flex items-center gap-4 text-graphite-700"><span className="w-14 font-semibold text-graphite-900">{o.unit_number ? `#${o.unit_number}` : 'Home'}</span><span className="inline-flex items-center gap-1"><BedDouble size={14} aria-hidden />{Number(o.beds) === 0 ? 'Studio' : `${o.beds} bd`}</span><span className="inline-flex items-center gap-1"><Bath size={14} aria-hidden />{o.baths} ba</span><span className="inline-flex items-center gap-1"><Ruler size={14} aria-hidden />{Number(o.sqft).toLocaleString('en-US')} sqft</span></span>
                  <span className="flex items-baseline gap-3"><span className="font-bold text-graphite-950">{fmt.usd(o.rent)}</span><span className="text-xs text-graphite-500">{o.available_date ? fmt.shortDate(o.available_date) : 'Now'}</span></span>
                </Link></li>
              ))}
            </ul>
          </>}

          <Reviews listingId={String(id)} />

          <h2 className="mb-3 mt-7 text-lg font-bold text-graphite-950">Costs</h2>
          <dl className="max-w-md divide-y divide-limestone-200 rounded-md border border-limestone-200 bg-white text-sm">
            <div className="flex justify-between px-4 py-2.5"><dt className="flex items-center gap-2 text-graphite-600"><DollarSign size={15} aria-hidden />Monthly rent</dt><dd className="font-bold text-graphite-950">{fmt.usd(l.rent)}</dd></div>
            <div className="flex justify-between px-4 py-2.5"><dt className="flex items-center gap-2 text-graphite-600"><Wallet size={15} aria-hidden />Security deposit</dt><dd className="font-bold text-graphite-950">{fmt.usd(l.deposit)}</dd></div>
            <div className="flex justify-between px-4 py-2.5"><dt className="flex items-center gap-2 text-graphite-600"><Home size={15} aria-hidden />Due to move in</dt><dd className="font-bold text-graphite-950">{fmt.usd(Number(l.rent) + Number(l.deposit))}</dd></div>
          </dl>
          <p className="mt-2 text-xs text-graphite-500">The first month’s rent may be reduced if you move in part-way through a month. Applicable taxes and fees are shown on your lease.</p>
        </div>
        <aside className="h-fit rounded-md border border-limestone-200 bg-white p-5 shadow-panel lg:sticky lg:top-6">
          <div className="text-3xl font-extrabold text-graphite-950">{fmt.usd(l.rent)}<span className="text-base font-medium text-graphite-500"> /mo</span></div>
          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-graphite-600"><span className="inline-flex items-center gap-1"><BedDouble size={14} aria-hidden />{Number(l.beds) === 0 ? 'Studio' : `${l.beds} bd`}</span><span className="inline-flex items-center gap-1"><Bath size={14} aria-hidden />{l.baths} ba</span><span className="inline-flex items-center gap-1"><Ruler size={14} aria-hidden />{Number(l.sqft).toLocaleString('en-US')} sqft</span></div>
          <div className="mt-1 text-sm text-graphite-600">Deposit {fmt.usd(l.deposit)} · Available {fmt.date(l.available_date)}</div>
          {done ? <div className="mt-4 rounded bg-good-50 p-3 text-sm text-graphite-800">{done}</div> : mode ? (
            <form className="mt-4 space-y-3" onSubmit={(e) => { e.preventDefault(); mode === 'enquire' ? enquire.mutate(undefined) : tour.mutate(undefined); }}>
              <Field label="Full name" required><Input required value={f.name} onChange={set('name')} /></Field>
              <Field label="Email" required><Input type="email" required value={f.email} onChange={set('email')} /></Field>
              {mode === 'enquire' ? (<>
                <Field label="Phone"><Input value={f.phone} onChange={set('phone')} /></Field>
                <Field label="Message"><Textarea rows={3} value={f.message} onChange={set('message')} placeholder="Ask about pets, parking, move-in date…" /></Field>
              </>) : <Field label="Preferred time" required><Input type="datetime-local" required value={f.scheduledAt} onChange={set('scheduledAt')} /></Field>}
              <Button variant="primary" type="submit" className="w-full" loading={enquire.isPending || tour.isPending}>{mode === 'enquire' ? 'Send message' : 'Request tour'}</Button>
            </form>
          ) : (
            <div className="mt-4 space-y-2">
              <Link to={`${base}/${id}/apply`}><Button variant="primary" className="w-full" size="lg">Apply now</Button></Link>
              <Button className="w-full" onClick={() => setMode('tour')}>Request a tour</Button>
              <Button variant="ghost" className="w-full" onClick={() => setMode('enquire')}>Ask a question</Button>
            </div>
          )}
          {l.company && (
            <div className="mt-5 border-t border-limestone-200 pt-4" data-testid="listed-by">
              <div className="text-xs font-semibold uppercase tracking-wide text-graphite-500">Listed by</div>
              <div className="mt-2 flex items-center gap-3">
                {l.company.logo ? <img src={l.company.logo} alt="" className="h-10 w-10 shrink-0 rounded object-contain" /> : <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded bg-federal-50 text-federal-800"><Building2 size={20} aria-hidden /></span>}
                <span className="min-w-0 font-bold text-graphite-950">{l.company.name}</span>
              </div>
              <ul className="mt-3 space-y-1.5 text-sm">
                {l.company.contactPhone && <li className="flex items-center gap-2"><Phone size={14} aria-hidden className="text-graphite-500" /><a className="text-federal-900 hover:underline" href={`tel:${String(l.company.contactPhone).replace(/[^+\d]/g, '')}`}>{l.company.contactPhone}</a></li>}
                {l.company.contactEmail && <li className="flex items-center gap-2"><Mail size={14} aria-hidden className="text-graphite-500" /><a className="break-all text-federal-900 hover:underline" href={`mailto:${l.company.contactEmail}`}>{l.company.contactEmail}</a></li>}
                {l.company.website && /^https?:\/\//i.test(l.company.website) && <li className="flex items-center gap-2"><Globe size={14} aria-hidden className="text-graphite-500" /><a className="break-all text-federal-900 hover:underline" href={l.company.website} target="_blank" rel="noopener noreferrer">{String(l.company.website).replace(/^https?:\/\//i, '')}</a></li>}
              </ul>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function Apply() {
  const { id } = useParams();
  const base = useBase();
  const nav = useNavigate();
  const { data: l } = useApi<any>(`/api/leasing/public/listings/${id}`);
  const [f, setF] = useState<any>({ firstName: '', lastName: '', email: '', phone: '', monthlyIncome: '', employer: '', monthsEmployed: '', currentRent: '', occupants: 1, pets: '', moveInDate: '', consent: false });
  const [res, setRes] = useState<any>(null);
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const submit = useAction(() => api.post<any>(`/api/leasing/public/listings/${id}/applications`, {
    ...f, campaign: trackedCampaign(), monthlyIncome: Number(f.monthlyIncome), monthsEmployed: f.monthsEmployed ? Number(f.monthsEmployed) : undefined, currentRent: f.currentRent ? Number(f.currentRent) : undefined, occupants: Number(f.occupants),
  }), { silent: true, onSuccess: setRes });
  if (res) return (
    <div className="space-y-5">
      <Panel title="Application received"><p className="text-graphite-700">{res.message}</p></Panel>
      {res.uploadToken && <ApplicantFiles applicationId={res.id} token={res.uploadToken} />}
      <Button variant="secondary" onClick={() => nav(base)}>Back to homes</Button>
    </div>
  );
  return (
    <div className="mx-auto max-w-2xl">
      <Link to={`${base}/${id}`} className="mb-4 inline-block text-sm font-semibold text-federal-900 hover:underline">Back to the home</Link>
      <h1 className="text-2xl font-extrabold text-graphite-950">Apply for {l?.title ?? 'this home'}</h1>
      <p className="mb-5 mt-1 text-graphite-600">About 5 minutes. We check credit and background after you consent below.</p>
      <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); submit.mutate(undefined); }}>
        <Panel title="About you">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="First name" required><Input required value={f.firstName} onChange={set('firstName')} /></Field>
            <Field label="Last name" required><Input required value={f.lastName} onChange={set('lastName')} /></Field>
            <Field label="Email" required><Input type="email" required value={f.email} onChange={set('email')} /></Field>
            <Field label="Phone"><Input value={f.phone} onChange={set('phone')} /></Field>
            <Field label="Desired move-in date"><Input type="date" value={f.moveInDate} onChange={set('moveInDate')} /></Field>
            <Field label="Occupants"><Input type="number" min={1} value={f.occupants} onChange={set('occupants')} /></Field>
            <Field label="Pets" className="sm:col-span-2"><Input value={f.pets} onChange={set('pets')} placeholder="None, or describe" /></Field>
          </div>
        </Panel>
        <Panel title="Income and history">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Monthly income before taxes (USD)" required><Input type="number" min={0} required value={f.monthlyIncome} onChange={set('monthlyIncome')} /></Field>
            <Field label="Employer"><Input value={f.employer} onChange={set('employer')} /></Field>
            <Field label="Months at employer"><Input type="number" min={0} value={f.monthsEmployed} onChange={set('monthsEmployed')} /></Field>
            <Field label="Current monthly rent (USD)"><Input type="number" min={0} value={f.currentRent} onChange={set('currentRent')} /></Field>
          </div>
        </Panel>
        <label className="flex gap-3 rounded-md border border-limestone-300 bg-white p-4 text-sm text-graphite-800">
          <input type="checkbox" className="mt-1" checked={f.consent} onChange={(e) => setF({ ...f, consent: e.target.checked })} />
          <span>I authorize a credit and background check. I understand a person reviews every application, and I’ll get an adverse action notice if a decision relies on a consumer report.</span>
        </label>
        <Button variant="primary" size="lg" type="submit" loading={submit.isPending} disabled={!f.consent}>Submit application</Button>
      </form>
    </div>
  );
}

/** Applicants have no account. They add documents with the private link from their confirmation (or from the leasing team). */
function ApplicantFiles({ applicationId, token }: { applicationId: string; token: string }) {
  const url = `/api/documents/public/applications/${applicationId}/files?token=${encodeURIComponent(token)}`;
  const { data, refetch, error } = useApi<any>(url, { retry: false });
  const [busy, setBusy] = useState<string | null>(null);
  const [type, setType] = useState('');
  const send = async (file?: File | null, docType?: string) => {
    if (!file) return;
    setBusy(docType ?? 'file');
    try {
      const fd = new FormData();
      fd.append('file', file);
      if (docType || type) fd.append('docType', docType || type);
      await api.upload(url, fd);
      await refetch();
    } catch (e) { toastError(e, 'We couldn’t add that file'); }
    finally { setBusy(null); }
  };
  if (error) return <Panel title="Add your documents"><p className="text-sm text-crit-700">{errorText(error)}</p></Panel>;
  const sent = new Set((data?.files ?? []).filter((f: any) => f.review_status !== 'REJECTED').map((f: any) => f.type));
  return (
    <Panel title="Add your documents" description="A photo ID and proof of income speed up the decision. PDF, JPG or PNG, up to 15 MB each.">
      {!data ? <Skeleton className="h-24" /> : <div className="space-y-4">
        <ul className="divide-y divide-limestone-200 rounded border border-limestone-200">
          {(data.checklist ?? []).map((c: any) => (
            <li key={c.type} className="flex flex-wrap items-center justify-between gap-3 px-3 py-2.5 text-sm">
              <span className="flex items-center gap-2">{c.state === 'ON_FILE' || c.state === 'IN_REVIEW' ? <CheckCircle2 className="h-4 w-4 text-good-700" /> : <CircleAlert className="h-4 w-4 text-warn-700" />}
                <strong>{c.type}</strong><span className="text-graphite-500">{c.state === 'ON_FILE' ? 'Received' : c.state === 'IN_REVIEW' ? 'Received, being checked' : c.state === 'REJECTED' ? 'Please send a new file' : 'Needed'}</span></span>
              {c.state !== 'ON_FILE' && <label className="inline-flex cursor-pointer items-center gap-1.5 rounded border border-limestone-300 bg-white px-3 py-1.5 text-sm font-semibold text-graphite-900 hover:bg-limestone-50">
                <UploadCloud className="h-4 w-4" />{busy === c.type ? 'Sending…' : 'Choose file'}
                <input type="file" className="sr-only" accept=".pdf,.png,.jpg,.jpeg,.heic" onChange={(e) => { send(e.target.files?.[0], c.type); e.target.value = ''; }} /></label>}
            </li>))}
        </ul>
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Something else" className="min-w-[200px] flex-1"><Select value={type} onChange={(e) => setType(e.target.value)}><option value="">Other document</option>{(data.types ?? []).filter((t: string) => !(data.checklist ?? []).some((c: any) => c.type === t)).map((t: string) => <option key={t}>{t}</option>)}</Select></Field>
          <label className="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded border border-limestone-300 bg-white px-3 text-sm font-semibold text-graphite-900 hover:bg-limestone-50">
            <UploadCloud className="h-4 w-4" />{busy === 'file' ? 'Sending…' : 'Add a file'}
            <input type="file" className="sr-only" accept=".pdf,.png,.jpg,.jpeg,.heic" onChange={(e) => { send(e.target.files?.[0]); e.target.value = ''; }} /></label>
        </div>
        {(data.files ?? []).length > 0 && <p className="text-xs text-graphite-500">Sent so far: {(data.files as any[]).map((f) => `${f.type}${f.review_status === 'REJECTED' ? ' (needs a new file)' : ''}`).join(', ')}.</p>}
      </div>}
    </Panel>
  );
}

function ApplicantDocuments() {
  const { id } = useParams();
  const [sp] = useSearchParams();
  const token = sp.get('token');
  if (!id || !token) return <EmptyState title="This link is incomplete" body="Use the full link from your application confirmation, or ask the leasing team to send it again." />;
  return <div className="mx-auto max-w-2xl"><h1 className="mb-1 text-2xl font-extrabold text-graphite-950">Your application documents</h1><p className="mb-5 text-graphite-600">Send what the leasing team asked for. You can come back to this page any time while your application is open.</p><ApplicantFiles applicationId={id} token={token} /></div>;
}

export function PublicSite() {
  useEffect(rememberCampaign, []);
  return (
    <Routes>
      <Route index element={<SearchPage />} />
      <Route path="applications/:id/documents" element={<ApplicantDocuments />} />
      <Route path=":id" element={<Detail />} />
      <Route path=":id/apply" element={<Apply />} />
    </Routes>
  );
}
