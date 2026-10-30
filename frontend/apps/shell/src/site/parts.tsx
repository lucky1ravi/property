import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Check, Minus } from 'lucide-react';
import { Skeleton } from '@propertyos/ui';
import { usd, type SitePlan } from './data';

export function Container({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`mx-auto max-w-[1280px] px-6 ${className}`}>{children}</div>;
}

/** Top of an inner page: a plain heading and one sentence of context. */
export function PageHero({ title, lead, children }: { title: string; lead: string; children?: ReactNode }) {
  return (
    <section className="relative overflow-hidden border-b border-federal-800 bg-federal-950 text-white">
      <div aria-hidden className="pointer-events-none absolute inset-0 opacity-40 [background-image:linear-gradient(rgba(156,195,193,.18)_1px,transparent_1px),linear-gradient(90deg,rgba(156,195,193,.18)_1px,transparent_1px)] [background-size:64px_64px] [mask-image:radial-gradient(ellipse_at_75%_0%,black,transparent_65%)]" />
      <div aria-hidden className="absolute -right-24 top-0 h-80 w-80 rounded-full bg-federal-700/30 blur-3xl" />
      <Container className="relative py-16 sm:py-24">
        <p className="text-xs font-bold uppercase tracking-[0.16em] text-federal-300">PropertyOS platform</p>
        <h1 className="mt-4 max-w-[18ch] text-[40px] font-black leading-[1.03] tracking-[-0.04em] sm:text-[58px]">{title}</h1>
        <p className="mt-5 max-w-[58ch] text-lg leading-relaxed text-federal-100">{lead}</p>
        {children && <div className="mt-8 flex flex-wrap gap-3">{children}</div>}
      </Container>
    </section>
  );
}

export function ButtonLink({ to, children, variant = 'primary' }: { to: string; children: ReactNode; variant?: 'primary' | 'secondary' | 'light' }) {
  const styles = {
    primary: 'bg-federal-900 text-white shadow-sm hover:bg-federal-800',
    secondary: 'border border-federal-900 bg-white text-federal-900 hover:bg-federal-50',
    light: 'bg-white text-federal-900 shadow-sm hover:bg-federal-50',
  }[variant];
  return <Link to={to} className={`inline-flex items-center justify-center rounded-md px-5 py-3 text-sm font-bold transition-colors ${styles}`}>{children}</Link>;
}

/** Closing call to action used at the bottom of most pages. */
export function CtaBand({ title = 'See PropertyOS with your own portfolio in mind', body = 'Tell us how many doors you manage and what takes the most time. We’ll show you where PropertyOS fits.' }: { title?: string; body?: string }) {
  return (
    <section className="relative overflow-hidden bg-federal-950">
      <div aria-hidden className="absolute inset-0 opacity-40 [background-image:linear-gradient(135deg,transparent_45%,rgba(156,195,193,.22)_45%,transparent_46%)] [background-size:24px_24px]" />
      <Container className="relative flex flex-col gap-6 py-14 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-federal-300">See the operating model</p>
          <h2 className="mt-2 max-w-[26ch] text-3xl font-black tracking-[-0.02em] text-white">{title}</h2>
          <p className="mt-2 max-w-[56ch] text-federal-100">{body}</p>
        </div>
        <div className="flex shrink-0 gap-3"><ButtonLink to="/contact" variant="light">Request a demo</ButtonLink></div>
      </Container>
    </section>
  );
}

export function LoadingBlock() {
  return <Container className="py-16"><Skeleton className="h-64" /></Container>;
}

export function SiteError() {
  return (
    <Container className="py-20">
      <div role="alert" className="max-w-xl rounded border border-crit-200 bg-crit-50 px-5 py-4 text-crit-800">
        We couldn’t load this page’s details. Check your connection and reload. If it keeps happening, <Link className="font-semibold underline" to="/contact">contact us</Link>.
      </div>
    </Container>
  );
}

/** One plan as a card. The highlighted plan is the one the operator marked most popular. */
export function PlanCard({ plan, titles }: { plan: SitePlan; titles: Record<string, string> }) {
  return (
    <article className={`flex flex-col rounded-lg border bg-white p-7 ${plan.highlight ? 'border-federal-900 shadow-lift ring-1 ring-federal-900' : 'border-limestone-300'}`}>
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-2xl font-black tracking-[-0.02em] text-graphite-950">{plan.name}</h2>
        {plan.highlight && <span className="rounded-full bg-federal-900 px-3 py-1 text-xs font-bold text-white">Most popular</span>}
      </div>
      <p className="mt-2 min-h-[3.5rem] text-[15px] leading-relaxed text-graphite-700">{plan.tagline}</p>
      <p className="mt-5"><span className="text-4xl font-black tracking-[-0.03em] text-graphite-950">{usd(plan.pricePerUnit)}</span> <span className="text-sm text-graphite-600">per unit, per month</span></p>
      <p className="mt-1 text-sm text-graphite-600">{usd(plan.minimumMonthly)} monthly minimum</p>
      {plan.audience && <p className="mt-3 text-sm font-semibold text-federal-800">{plan.audience}</p>}
      <ul className="mt-6 flex-1 space-y-2.5 border-t border-limestone-200 pt-6 text-[15px]">
        {plan.allFeatures && <li className="font-semibold text-graphite-900">Every feature, including new ones as they ship</li>}
        {!plan.allFeatures && plan.features.map((f) => (
          <li key={f} className="flex gap-2.5"><Check className="mt-0.5 h-4 w-4 shrink-0 text-federal-700" aria-hidden />{titles[f] ?? f}</li>
        ))}
      </ul>
      <div className="mt-7"><ButtonLink to={`/contact?topic=PRICING&plan=${plan.key}`} variant={plan.highlight ? 'primary' : 'secondary'}>Talk to us about {plan.name}</ButtonLink></div>
    </article>
  );
}

export function Included({ yes }: { yes: boolean }) {
  return yes ? <><Check className="mx-auto h-5 w-5 text-federal-700" aria-hidden /><span className="sr-only">Included</span></>
    : <><Minus className="mx-auto h-5 w-5 text-graphite-300" aria-hidden /><span className="sr-only">Not included</span></>;
}
