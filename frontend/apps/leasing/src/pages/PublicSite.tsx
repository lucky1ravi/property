import { useState } from 'react';
import { Link, Route, Routes, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { motion, useScroll, useTransform } from 'framer-motion';
import { BedDouble, Bath, Ruler, MapPin, Search, UploadCloud, CheckCircle2, CircleAlert } from 'lucide-react';
import { Button, Field, Input, Select, Textarea, Skeleton, Carousel, EmptyState, Panel, useApi, useAction, api, fmt, toastError, errorText } from '@propertyos/ui';
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
  return (
    <div>
      <Link to={base} className="mb-4 inline-block text-sm font-semibold text-federal-900 hover:underline">All homes</Link>
      <div className="grid gap-8 lg:grid-cols-[1fr_380px]">
        <div>
          <Carousel images={l.photos ?? []} alt={l.title} />
          <h1 className="mt-5 text-2xl font-extrabold text-graphite-950">{l.title}</h1>
          <p className="mt-1 flex items-center gap-1 text-graphite-600"><MapPin size={14} />{l.street}, {l.city}, {l.state} {l.zip}</p>
          <div className="mt-4 flex gap-6 text-graphite-800"><span>{l.beds} bed</span><span>{l.baths} bath</span><span>{l.sqft} sqft</span></div>
          <p className="mt-5 max-w-[68ch] leading-relaxed text-graphite-700">{l.description}</p>
          <ul className="mt-5 flex flex-wrap gap-2">{(l.highlights ?? []).map((h: string) => <li key={h} className="rounded-full border border-limestone-300 bg-white px-3 py-1 text-sm text-graphite-700">{h}</li>)}</ul>
        </div>
        <aside className="h-fit rounded-md border border-limestone-200 bg-white p-5 shadow-panel lg:sticky lg:top-6">
          <div className="text-3xl font-extrabold text-graphite-950">{fmt.usd(l.rent)}<span className="text-base font-medium text-graphite-500"> /mo</span></div>
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
