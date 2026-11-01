import { useState, type ReactNode } from 'react';
import { Link, Route, Routes, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { motion, useScroll, useTransform } from 'framer-motion';
import { BedDouble, Bath, Ruler, MapPin, Search, UploadCloud, CheckCircle2, CircleAlert, Building2, CalendarDays, CalendarCheck, Car, Waves, Dumbbell, WashingMachine, PawPrint, Snowflake, Flame, Utensils, Sun, Package, ArrowUpDown, Wifi, ShieldCheck, PlugZap, Bike, Layers, Users, Trees, Trash2, Sparkles, DollarSign, Wallet, Home, Sofa, Microwave, Refrigerator, Check, Key, TrendingDown, type LucideIcon } from 'lucide-react';
import { Button, Field, Input, Select, Textarea, Skeleton, Carousel, EmptyState, Panel, useApi, useAction, api, fmt, toastError, errorText, Stars } from '@propertyos/ui';
import { useBase } from '../common';

function Hero() {
  const { scrollY } = useScroll();
  const y = useTransform(scrollY, [0, 400], [0, 90]);
  const o = useTransform(scrollY, [0, 300], [1, 0.3]);
  return (
    <div className="relative mb-8 overflow-hidden rounded-lg bg-federal-950">
      <motion.img style={{ y }} src="https://images.unsplash.com/photo-1560518883-ce09059eeffa?w=1800&q=70" alt="" className="absolute inset-0 h-[130%] w-full object-cover opacity-35" />
      <motion.div style={{ opacity: o }} className="relative px-8 py-14 md:py-20">
        <motion.h1 initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6 }} className="max-w-[18ch] text-[40px] font-extrabold leading-[1.05] tracking-[-0.02em] text-white md:text-[56px]">
          Find a home you’ll want to stay in
        </motion.h1>
        <p className="mt-4 max-w-[52ch] text-lg text-federal-100">Browse available rentals, tour in person or virtually, and apply online. Most applicants hear back within a day.</p>
      </motion.div>
    </div>
  );
}

function Search_() {
  const base = useBase();
  const [city, setCity] = useState('');
  const [beds, setBeds] = useState('');
  const [max, setMax] = useState('');
  const qs = new URLSearchParams({ ...(city && { city }), ...(beds && { beds }), ...(max && { maxRent: max }) }).toString();
  const { data, isLoading } = useApi<any[]>(`/api/leasing/public/listings${qs ? '?' + qs : ''}`);
  return (
    <>
      <Hero />
      <form className="mb-6 grid gap-3 rounded-md border border-limestone-200 bg-white p-4 shadow-panel md:grid-cols-[1fr_160px_160px_auto]" onSubmit={(e) => e.preventDefault()}>
        <Field label="City"><Input value={city} onChange={(e) => setCity(e.target.value)} placeholder="Austin, San Francisco…" /></Field>
        <Field label="Bedrooms"><Select value={beds} onChange={(e) => setBeds(e.target.value)}><option value="">Any</option><option value="1">1+</option><option value="2">2+</option><option value="3">3+</option></Select></Field>
        <Field label="Max rent"><Select value={max} onChange={(e) => setMax(e.target.value)}><option value="">Any</option>{[1500, 2000, 2500, 3500, 5000].map((m) => <option key={m} value={m}>{fmt.usd(m)}</option>)}</Select></Field>
        <div className="flex items-end"><Button variant="primary" icon={<Search size={14} />} type="submit">Search</Button></div>
      </form>
      {isLoading ? <div className="grid gap-5 md:grid-cols-3"><Skeleton className="h-72" /><Skeleton className="h-72" /><Skeleton className="h-72" /></div>
        : !data?.length ? <EmptyState title="No homes match your search" body="Try a different city or a higher rent." />
        : (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {data.map((l, i) => (
              <motion.div key={l.id} initial={{ opacity: 0, y: 14 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true }} transition={{ delay: (i % 3) * 0.06 }}>
                <Link to={`${base}/${l.id}`} className="group block overflow-hidden rounded-md border border-limestone-200 bg-white shadow-panel transition-shadow hover:shadow-lg">
                  <div className="aspect-[4/3] overflow-hidden bg-limestone-200"><img src={l.photos?.[0]} alt={l.title} loading="lazy" className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-105" /></div>
                  <div className="p-4">
                    <div className="flex items-baseline justify-between"><span className="text-xl font-extrabold text-graphite-950">{fmt.usd(l.rent)}<span className="text-sm font-medium text-graphite-500"> /mo</span></span><span className="text-xs text-graphite-500">Available {fmt.shortDate(l.available_date)}</span></div>
                    <div className="mt-1 text-sm font-semibold text-graphite-800">{l.property_name}</div>
                    <div className="mt-0.5 flex items-center gap-1 text-xs text-graphite-500"><MapPin size={12} />{l.street}, {l.city}, {l.state}</div>
                    <div className="mt-3 flex gap-4 text-sm text-graphite-700"><span className="flex items-center gap-1"><BedDouble size={14} />{l.beds} bd</span><span className="flex items-center gap-1"><Bath size={14} />{l.baths} ba</span><span className="flex items-center gap-1"><Ruler size={14} />{l.sqft} sqft</span></div>
                  </div>
                </Link>
              </motion.div>
            ))}
          </div>
        )}
    </>
  );
}

/** An icon for an amenity, found by the words in its name, so a new amenity still gets a sensible picture. */
const AMENITY_ICONS: [RegExp, LucideIcon][] = [
  [/park|garage|carport/i, Car], [/pool|swim|spa|hot tub/i, Waves], [/gym|fitness|workout/i, Dumbbell], [/laundry|washer|dryer/i, WashingMachine],
  [/pet|dog|cat/i, PawPrint], [/air|a\/c|\bac\b|cool/i, Snowflake], [/heat|fireplace|furnace/i, Flame], [/dishwasher|kitchen|stove|oven/i, Utensils],
  [/microwave/i, Microwave], [/fridge|refrigerator/i, Refrigerator], [/balcon|patio|deck|terrace|sun/i, Sun], [/storage|closet|pantry/i, Package],
  [/elevator|lift/i, ArrowUpDown], [/wi-?fi|internet|fiber|cable/i, Wifi], [/secur|gated|camera|doorman|concierge|key/i, ShieldCheck], [/\bev\b|charging|electric/i, PlugZap],
  [/bike|bicycle/i, Bike], [/hardwood|floor|carpet|tile/i, Layers], [/club|lounge|community|party|rooftop/i, Users], [/garden|yard|lawn|park\b|trail|green|tree/i, Trees],
  [/furnish|sofa/i, Sofa], [/trash|waste|recycl/i, Trash2],
];
function amenityIcon(name: string): LucideIcon {
  return AMENITY_ICONS.find(([re]) => re.test(name))?.[1] ?? Check;
}

/** One fact about the home with its icon: "2 bedrooms", "Built 2005". */
function Fact({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: ReactNode }) {
  return (
    <div className="flex items-center gap-3 rounded-md border border-limestone-200 bg-white p-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800"><Icon size={20} aria-hidden /></span>
      <span className="min-w-0"><span className="block text-xs text-graphite-500">{label}</span><span className="block truncate text-sm font-bold text-graphite-950">{value}</span></span>
    </div>
  );
}

function Detail() {
  const { id } = useParams();
  const base = useBase();
  const { data: l, isLoading } = useApi<any>(`/api/leasing/public/listings/${id}`);
  const [mode, setMode] = useState<'enquire' | 'tour' | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [f, setF] = useState<any>({ name: '', email: '', phone: '', message: '', scheduledAt: '' });
  const set = (k: string) => (e: any) => setF({ ...f, [k]: e.target.value });
  const enquire = useAction(() => api.post<any>(`/api/leasing/public/listings/${id}/enquiries`, { name: f.name, email: f.email, phone: f.phone, message: f.message }), { silent: true, onSuccess: (r) => setDone(r.reply) });
  const tour = useAction(() => api.post<any>(`/api/leasing/public/listings/${id}/showings`, { name: f.name, email: f.email, type: 'SELF_GUIDED', scheduledAt: new Date(f.scheduledAt).toISOString() }), { silent: true, onSuccess: () => setDone('Tour requested. We’ll confirm the time by email.') });
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
          <div className="mt-5 flex flex-wrap items-start justify-between gap-3">
            <div>
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

          {amenities.length > 0 && <>
            <h2 className="mb-3 mt-7 text-lg font-bold text-graphite-950">Amenities</h2>
            <ul className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3" data-testid="amenities">
              {amenities.map((a) => { const Icon = amenityIcon(a); return <li key={a} className="flex items-center gap-2.5 text-sm text-graphite-800"><span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800"><Icon size={16} aria-hidden /></span>{a}</li>; })}
            </ul>
          </>}

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
    ...f, monthlyIncome: Number(f.monthlyIncome), monthsEmployed: f.monthsEmployed ? Number(f.monthsEmployed) : undefined, currentRent: f.currentRent ? Number(f.currentRent) : undefined, occupants: Number(f.occupants),
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
  return (
    <Routes>
      <Route index element={<Search_ />} />
      <Route path="applications/:id/documents" element={<ApplicantDocuments />} />
      <Route path=":id" element={<Detail />} />
      <Route path=":id/apply" element={<Apply />} />
    </Routes>
  );
}
