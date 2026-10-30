import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, BookOpenCheck, Fingerprint, Layers3, ShieldCheck, Workflow } from 'lucide-react';
import { PRODUCTS, useSite, useTitle, usd } from './data';
import { ButtonLink, Container, CtaBand } from './parts';
import { QueueShot, ScoreShot, StatementShot, TriageShot } from './shots';

const PILLARS = [
  { icon: ShieldCheck, title: 'Isolated by design', body: 'Each company’s records are kept apart by the database itself.' },
  { icon: BookOpenCheck, title: 'Decisions with reasons', body: 'Every score, ranking and flag shows why it was made.' },
  { icon: Layers3, title: 'Configured, not customised', body: 'Fees, limits, response times and state rules are settings your administrators control.' },
  { icon: Fingerprint, title: 'A full audit trail', body: 'Who did what, and when, recorded and impossible to edit from the product.' },
];

function Showcase({ title, body, points, link, shot, flip }: { title: string; body: string; points: string[]; link: string; shot: ReactNode; flip?: boolean }) {
  return (
    <section className="border-t border-limestone-200 first:border-t-0">
      <Container className="grid items-center gap-12 py-20 lg:grid-cols-2 lg:gap-20">
        <div className={flip ? 'lg:order-2' : ''}>
          <h2 className="max-w-[18ch] text-[34px] font-black leading-[1.08] tracking-[-0.03em] text-graphite-950">{title}</h2>
          <p className="mt-4 max-w-[52ch] text-[17px] leading-relaxed text-graphite-700">{body}</p>
          <ul className="mt-6 space-y-3 text-[15px] text-graphite-800">
            {points.map((p) => <li key={p} className="flex gap-3"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-federal-700" />{p}</li>)}
          </ul>
          <Link to={link} className="mt-7 inline-flex items-center gap-1.5 text-sm font-bold text-federal-800 hover:underline">Learn more <ArrowRight className="h-4 w-4" /></Link>
        </div>
        <div className={flip ? 'lg:order-1' : ''}>{shot}</div>
      </Container>
    </section>
  );
}

export function Home() {
  useTitle('Property management software');
  const { data } = useSite();
  const cheapest = data?.plans.slice().sort((a, b) => a.pricePerUnit - b.pricePerUnit)[0];
  return (
    <>
      <section className="relative overflow-hidden border-b border-federal-800 bg-federal-950 text-white">
        <div aria-hidden className="pointer-events-none absolute inset-0 opacity-40 [background-image:linear-gradient(rgba(156,195,193,.18)_1px,transparent_1px),linear-gradient(90deg,rgba(156,195,193,.18)_1px,transparent_1px)] [background-size:64px_64px] [mask-image:radial-gradient(ellipse_at_70%_10%,black,transparent_68%)]" />
        <div aria-hidden className="absolute -right-20 top-10 h-[440px] w-[440px] rounded-full bg-federal-700/30 blur-3xl" />
        <Container className="relative grid items-center gap-14 py-16 lg:grid-cols-[1.05fr_1fr] lg:py-24">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.16em] text-federal-300">Enterprise property operations</p>
            <h1 className="mt-4 max-w-[15ch] text-[44px] font-black leading-[1.02] tracking-[-0.045em] sm:text-[64px]">Control every property operation from one system.</h1>
            <p className="mt-6 max-w-[52ch] text-lg leading-relaxed text-federal-100">
              PropertyOS unifies leasing, payments, maintenance and portfolio oversight—automating repeatable work while keeping people in control of the decisions that matter.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link to="/contact" className="inline-flex items-center justify-center rounded-md bg-white px-5 py-3 text-sm font-bold text-federal-900 shadow-sm transition-colors hover:bg-federal-50">Request a demo</Link>
              <Link to="/products" className="inline-flex items-center justify-center rounded-md border border-federal-300/60 px-5 py-3 text-sm font-bold text-white transition-colors hover:bg-white/10">Explore the platform</Link>
            </div>
            <div className="mt-10 grid max-w-[40rem] grid-cols-3 gap-4 border-t border-federal-800 pt-6 text-sm">
              {[['One record', 'for every property'], ['Clear controls', 'at every handoff'], ['Built to scale', 'without new silos']].map(([title, detail]) => <div key={title}><p className="font-extrabold text-white">{title}</p><p className="mt-1 text-xs leading-relaxed text-federal-200">{detail}</p></div>)}
            </div>
          </div>
          <div className="relative"><div aria-hidden className="absolute -inset-5 rounded-2xl bg-federal-700/20 blur-xl" /><QueueShot /></div>
        </Container>
      </section>

      <section aria-label="Platform principles" className="border-b border-limestone-200 bg-white">
        <Container className="grid gap-0 py-0 sm:grid-cols-2 lg:grid-cols-4">
          {PILLARS.map(({ icon: Icon, title, body }) => (
            <div key={title} className="flex gap-3.5 border-b border-limestone-200 px-1 py-7 last:border-b-0 sm:px-6 sm:even:border-l lg:border-b-0 lg:border-l lg:first:border-l-0">
              <Icon className="mt-0.5 h-5 w-5 shrink-0 text-federal-700" aria-hidden />
              <div><h2 className="text-[15px] font-extrabold text-graphite-950">{title}</h2><p className="mt-1 text-sm leading-relaxed text-graphite-600">{body}</p></div>
            </div>
          ))}
        </Container>
      </section>

      <section className="bg-limestone-50">
        <Container className="grid gap-10 py-16 lg:grid-cols-[.85fr_1.15fr] lg:items-end">
          <div><p className="text-xs font-bold uppercase tracking-[0.14em] text-federal-700">Designed for operating complexity</p><h2 className="mt-3 max-w-[16ch] text-[34px] font-black leading-[1.08] tracking-[-0.035em] text-graphite-950">A system of record, not another point solution.</h2></div>
          <div className="grid gap-3 sm:grid-cols-3">{[['One shared record', 'Properties, people, leases and work stay connected.'], ['Rules at the core', 'Your approvals, fees and state limits guide every workflow.'], ['Decisions surfaced', 'The platform routes exceptions to the right person.']].map(([title, body], index) => <article key={title} className="rounded-lg border border-limestone-300 bg-white p-5 shadow-panel"><span className="text-xs font-bold text-federal-700">0{index + 1}</span><h3 className="mt-5 font-extrabold text-graphite-950">{title}</h3><p className="mt-2 text-sm leading-relaxed text-graphite-600">{body}</p></article>)}</div>
        </Container>
      </section>

      <Showcase title="Leasing that decides with evidence" flip
        body="Applicants are scored against your own screening rules, and the reasons are listed in plain words. The broker approves; the lease is state-compliant and signed online."
        points={['Enquiries answered in minutes, around the clock', 'Showings booked by area and capacity', 'A recommendation with every reason shown']}
        link="/products#leasing" shot={<ScoreShot />} />
      <Showcase title="Repairs routed before anyone picks up the phone"
        body="Residents describe the problem in their own words. PropertyOS works out the trade, urgency and likely cost, asks the owner only when it must, and dispatches the best available vendor."
        points={['Triage by trade, priority and cost', 'Owner approval above the limit they set', 'Resident confirms the fix and rates the vendor']}
        link="/products#maintenance" shot={<TriageShot />} />
      <Showcase title="Rent and owner statements that close themselves" flip
        body="Charges are scheduled from the lease, late fees stay inside state caps, and tenants at risk are offered a payment plan. Owners get statements and payouts on schedule."
        points={['Invoices and reminders sent on time', 'Exceptions routed to your accountant', 'Owners see their own properties and nothing else']}
        link="/products#rent" shot={<StatementShot />} />

      <section className="bg-federal-950 text-white" aria-labelledby="gov">
        <Container className="py-20">
          <p className="flex items-center gap-2 text-xs font-bold uppercase tracking-[0.14em] text-federal-300"><Workflow className="h-4 w-4" /> Governed automation</p>
          <h2 id="gov" className="mt-3 max-w-[24ch] text-[34px] font-black leading-[1.08] tracking-[-0.03em]">Automation that knows when to stop</h2>
          <p className="mt-4 max-w-[60ch] text-federal-100">Routine steps move on their own. Anything involving money, legal terms or safety waits for a named person, with a time limit and a record.</p>
          <div className="mt-10 grid gap-px overflow-hidden rounded-lg bg-federal-800 md:grid-cols-3">
            {[['Leasing', 'The broker approves every applicant.'], ['Rent', 'The accountant waives or approves every exception.'], ['Maintenance', 'The broker handles every emergency.']].map(([a, b]) => (
              <div key={a} className="bg-federal-900 p-7"><h3 className="text-lg font-extrabold">{a}</h3><p className="mt-2 text-federal-100">{b}</p></div>
            ))}
          </div>
          <div className="mt-8 flex flex-wrap gap-x-8 gap-y-3 text-sm font-bold">
            <Link to="/security" className="inline-flex items-center gap-1.5 hover:underline">How we protect your data <ArrowRight className="h-4 w-4" /></Link>
            <Link to="/why-us" className="inline-flex items-center gap-1.5 hover:underline">Why teams choose PropertyOS <ArrowRight className="h-4 w-4" /></Link>
          </div>
        </Container>
      </section>

      <section aria-labelledby="platform" className="bg-white">
        <Container className="py-20">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <h2 id="platform" className="max-w-[22ch] text-[34px] font-black leading-[1.08] tracking-[-0.03em] text-graphite-950">One platform for the whole property lifecycle</h2>
            <Link to="/products" className="inline-flex items-center gap-1.5 text-sm font-bold text-federal-800 hover:underline">All products <ArrowRight className="h-4 w-4" /></Link>
          </div>
          <ul className="mt-10 grid gap-px overflow-hidden rounded-lg border border-limestone-300 bg-limestone-300 md:grid-cols-2 lg:grid-cols-3">
            {PRODUCTS.map((p) => (
              <li key={p.key} className="bg-white">
                <Link to={`/products#${p.key.toLowerCase()}`} className="block h-full p-7 transition-colors hover:bg-limestone-50">
                  <h3 className="text-lg font-extrabold text-graphite-950">{p.name}</h3>
                  <p className="mt-2 max-w-[38ch] leading-relaxed text-graphite-600">{p.headline}.</p>
                </Link>
              </li>
            ))}
          </ul>
        </Container>
      </section>

      <section className="border-t border-limestone-200 bg-limestone-50">
        <Container className="flex flex-col gap-6 py-14 md:flex-row md:items-center md:justify-between">
          <div>
            <h2 className="text-[28px] font-black tracking-[-0.02em] text-graphite-950">Priced by the units you manage</h2>
            <p className="mt-2 max-w-[58ch] text-graphite-700">{cheapest ? `Plans start at ${usd(cheapest.pricePerUnit)} per unit each month. ` : ''}Every plan runs on the same platform, so growing never means migrating.</p>
          </div>
          <ButtonLink to="/plans" variant="secondary">Compare plans</ButtonLink>
        </Container>
      </section>
      <CtaBand />
    </>
  );
}
