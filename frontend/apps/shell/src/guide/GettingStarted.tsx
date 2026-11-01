import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { ArrowRight, Check, Compass, X } from 'lucide-react';
import clsx from 'clsx';
import { useApi, useSession } from '@propertyos/ui';
import type { NavGroup } from '../layout/nav';
import { useGuide } from './context';
import { useGuideState } from './state';

interface Item { key: string; title: string; why: string; to: string; done: boolean }

/** A person who has never used the product needs a first move. This list gives them one, and ticks itself off from what they have really done. */
export function GettingStarted({ persona, groups }: { persona: string; groups: NavGroup[] }) {
  const { can } = useSession();
  const { state, save } = useGuideState();
  const { startAppTour } = useGuide();
  const nav = useNavigate();
  const setup = persona === 'admin' || persona === 'manager';
  // Only what is needed to know "has any been added": one row, plus the total.
  const count = (perm: string, ep: string) => useApi<any>(setup && can(perm) ? `${ep}?page=1&size=1` : null, { staleTime: 30_000 }); // eslint-disable-line react-hooks/rules-of-hooks
  const owners = count('OWNER:VIEW', '/api/leasing/owners');
  const properties = count('PROPERTY:VIEW', '/api/leasing/properties');
  const units = count('PROPERTY:VIEW', '/api/leasing/units');
  const listings = count('LISTING:VIEW', '/api/leasing/listings');
  const users = count('USER:VIEW', '/api/identity/users');
  const total = (q: { data?: any }) => Number(q.data?.total ?? 0);

  if (state.checklistHidden) return null;
  const visible = new Set(groups.flatMap((g) => g.items.flatMap((i) => (i.children ? i.children.map((c) => c.to) : [i.to]))));
  const has = (to: string) => visible.has(to);

  if (setup) {
    const items: (Item | false)[] = [
      can('OWNER:VIEW') && { key: 'owner', title: 'Add an owner', why: 'Every property belongs to an owner. Add the people or companies you manage for.', to: '/app/leasing/owners', done: total(owners) > 0 },
      can('PROPERTY:VIEW') && { key: 'property', title: 'Add a property', why: 'A building or home you manage. You need an owner first.', to: '/app/leasing/properties', done: total(properties) > 0 },
      can('PROPERTY:VIEW') && { key: 'unit', title: 'Add its units', why: 'The apartments or homes inside a property that can be rented.', to: '/app/leasing/units', done: total(units) > 0 },
      can('LISTING:VIEW') && has('/app/leasing/listings') && { key: 'listing', title: 'Advertise a vacant unit', why: 'A listing puts it on your website so people can enquire and apply.', to: '/app/leasing/listings', done: total(listings) > 0 },
      can('USER:VIEW') && { key: 'team', title: 'Invite your team', why: 'Give colleagues their own sign-in and choose what they can do.', to: '/app/admin/access', done: total(users) > 1 },
      { key: 'tour', title: 'Take the tour', why: 'A one-minute look at where everything is.', to: '', done: !!state.welcome && !!state.tours?.__app },
    ];
    const list = items.filter(Boolean) as Item[];
    const done = list.filter((i) => i.done).length;
    const allDone = done === list.length;
    const next = list.find((i) => !i.done);
    return (
      <motion.section aria-label="Getting started" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} className="mb-6 overflow-hidden rounded-xl border border-federal-100 bg-white shadow-panel">
        <div className="flex items-start justify-between gap-4 border-b border-limestone-200 bg-federal-50/60 px-5 py-4">
          <div>
            <h2 className="text-base font-extrabold normal-case tracking-normal text-graphite-950">{allDone ? 'You are all set up' : 'Get started'}</h2>
            <p className="mt-0.5 text-sm text-graphite-600">{allDone ? 'Everything on the list is done. You can hide this.' : `${done} of ${list.length} done. Follow the steps in order; each one takes a couple of minutes.`}</p>
            <div className="mt-3 h-1.5 w-56 max-w-full overflow-hidden rounded-full bg-limestone-200" role="progressbar" aria-valuemin={0} aria-valuemax={list.length} aria-valuenow={done} aria-label="Setup progress">
              <motion.div className="h-full rounded-full bg-federal-700" initial={false} animate={{ width: `${(done / Math.max(1, list.length)) * 100}%` }} transition={{ duration: 0.4 }} />
            </div>
          </div>
          <button type="button" onClick={() => save({ checklistHidden: true })} aria-label="Hide this list" className="rounded p-1.5 text-graphite-400 hover:bg-limestone-100 hover:text-graphite-800"><X className="h-4 w-4" /></button>
        </div>
        <ol className="divide-y divide-limestone-200">
          {list.map((i) => (
            <li key={i.key} className={clsx('flex items-center gap-4 px-5 py-3', next?.key === i.key && 'bg-federal-50/30')}>
              <span className={clsx('inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2', i.done ? 'border-federal-700 bg-federal-700 text-white' : 'border-limestone-300 bg-white')} aria-hidden>{i.done && <Check className="h-3.5 w-3.5" />}</span>
              <div className="min-w-0 flex-1">
                <p className={clsx('text-sm font-bold', i.done ? 'text-graphite-500 line-through' : 'text-graphite-900')}>{i.title}<span className="sr-only">{i.done ? ' (done)' : ''}</span></p>
                {!i.done && <p className="text-sm text-graphite-600">{i.why}</p>}
              </div>
              {!i.done && (i.to
                ? <button type="button" onClick={() => nav(i.to)} className={clsx('inline-flex h-8 shrink-0 items-center gap-1.5 rounded px-3 text-sm font-bold', next?.key === i.key ? 'bg-federal-800 text-white hover:bg-federal-900' : 'border border-limestone-300 text-graphite-800 hover:bg-limestone-50')}>Go<ArrowRight className="h-3.5 w-3.5" /></button>
                : <button type="button" onClick={startAppTour} className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded border border-limestone-300 px-3 text-sm font-bold text-graphite-800 hover:bg-limestone-50"><Compass className="h-3.5 w-3.5" />Start</button>)}
            </li>
          ))}
        </ol>
      </motion.section>
    );
  }

  // Everyone else: three good first moves for their role, as links. Nothing to tick off.
  const QUICK: Record<string, { title: string; why: string; to: string }[]> = {
    broker: [{ title: 'Check your leads', why: 'Start with the highest score.', to: '/app/leasing/leads' }, { title: 'Confirm showings', why: 'Tour requests waiting for you.', to: '/app/leasing/showings' }, { title: 'Review applications', why: 'Run screening and decide.', to: '/app/leasing/applications' }],
    agent: [{ title: 'Check your leads', why: 'Start with the highest score.', to: '/app/leasing/leads' }, { title: 'Confirm showings', why: 'Tour requests waiting for you.', to: '/app/leasing/showings' }, { title: 'Review applications', why: 'Run screening and decide.', to: '/app/leasing/applications' }],
    accountant: [{ title: 'See who is overdue', why: 'Ranked, with a next step for each.', to: '/app/payments/collections' }, { title: 'Review invoices', why: 'Everything raised to residents.', to: '/app/payments/invoices' }, { title: 'Owner statements', why: 'Draft and publish for owners.', to: '/app/payments/statements' }],
    tenant: [{ title: 'Pay your rent', why: 'See what is due and pay.', to: '/app/payments' }, { title: 'Read your lease', why: 'Terms, dates and signatures.', to: '/app/leasing/my-lease' }, { title: 'Report a problem', why: 'Tell us what needs fixing.', to: '/app/maintenance' }],
    owner: [{ title: 'See your properties', why: 'Occupancy and rent.', to: '/app/leasing/properties' }, { title: 'Read your statements', why: 'What came in and what you are paid.', to: '/app/payments/statements' }, { title: 'Approve repairs', why: 'Jobs above your limit wait for you.', to: '/app/maintenance' }],
    vendor: [{ title: 'See your jobs', why: 'Everything assigned to you.', to: '/app/maintenance' }],
    platform: [{ title: 'Onboard a company', why: 'Create a company and its administrator.', to: '/app/admin/platform' }, { title: 'Review the plans', why: 'Prices and what each includes.', to: '/app/admin/plans' }],
  };
  const links = (QUICK[persona] ?? []).filter((l) => has(l.to));
  if (links.length === 0) return null;
  return (
    <motion.section aria-label="Quick start" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} className="mb-6 rounded-xl border border-federal-100 bg-white p-5 shadow-panel">
      <div className="flex items-start justify-between gap-4">
        <div><h2 className="text-base font-extrabold normal-case tracking-normal text-graphite-950">Where to start</h2><p className="mt-0.5 text-sm text-graphite-600">The most useful first steps for your role.</p></div>
        <div className="flex items-center gap-1">
          <button type="button" onClick={startAppTour} className="inline-flex h-8 items-center gap-1.5 rounded px-2.5 text-sm font-semibold text-federal-800 hover:bg-federal-50"><Compass className="h-4 w-4" />Take the tour</button>
          <button type="button" onClick={() => save({ checklistHidden: true })} aria-label="Hide this" className="rounded p-1.5 text-graphite-400 hover:bg-limestone-100 hover:text-graphite-800"><X className="h-4 w-4" /></button>
        </div>
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-3">
        {links.map((l) => (
          <button key={l.to} type="button" onClick={() => nav(l.to)} className="group rounded-lg border border-limestone-200 bg-limestone-50/60 p-4 text-left transition-colors hover:border-federal-300 hover:bg-federal-50/50">
            <span className="flex items-center justify-between text-sm font-bold text-graphite-900">{l.title}<ArrowRight className="h-4 w-4 text-graphite-400 transition-transform group-hover:translate-x-0.5 group-hover:text-federal-700" /></span>
            <span className="mt-1 block text-sm text-graphite-600">{l.why}</span>
          </button>
        ))}
      </div>
    </motion.section>
  );
}
