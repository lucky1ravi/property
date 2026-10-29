import type { ReactNode } from 'react';
import { AlertTriangle, Check, ChevronRight, Wrench } from 'lucide-react';

/**
 * Illustrations of the product's screens, drawn in HTML so they stay sharp at any size.
 * They show sample data only and are hidden from screen readers.
 */
export function Frame({ title, children, className = '' }: { title: string; children: ReactNode; className?: string }) {
  return (
    <figure aria-hidden="true" className={`overflow-hidden rounded-xl border border-limestone-300 bg-white shadow-[0_30px_80px_-30px_rgba(8,36,37,0.45)] ${className}`}>
      <div className="flex items-center gap-2 border-b border-limestone-200 bg-limestone-100 px-4 py-2.5">
        <span className="h-2.5 w-2.5 rounded-full bg-limestone-400" /><span className="h-2.5 w-2.5 rounded-full bg-limestone-400" /><span className="h-2.5 w-2.5 rounded-full bg-limestone-400" />
        <span className="ml-3 text-xs font-semibold text-graphite-500">{title}</span>
        <span className="ml-auto rounded bg-white px-2 py-0.5 text-[10px] font-semibold text-graphite-500">Sample data</span>
      </div>
      {children}
    </figure>
  );
}

const pill = (tone: 'good' | 'warn' | 'crit' | 'brand', text: string) => {
  const t = { good: 'bg-federal-100 text-federal-900', warn: 'bg-amber-100 text-amber-900', crit: 'bg-brick-100 text-brick-800', brand: 'bg-slateblue-100 text-slateblue-700' }[tone];
  return <span className={`rounded-full px-2 py-0.5 text-[11px] font-bold ${t}`}>{text}</span>;
};

/** The broker's daily queue: the few decisions that need a person. */
export function QueueShot() {
  const rows: [string, string, ReactNode, string][] = [
    ['Approve applicant', 'Maria Gomez · 14 Oak Street, Unit 2', pill('good', 'Score 82'), 'Review'],
    ['Waive late fee', 'Daniel Okafor · Unit 3B · $75', pill('warn', 'Exception'), 'Review'],
    ['Emergency repair', 'Burst pipe · Unit 7A · vendor dispatched', pill('crit', 'Urgent'), 'Open'],
    ['Owner approval', 'Roof patch · $1,240 · above owner limit', pill('brand', 'Waiting'), 'Open'],
  ];
  return (
    <Frame title="PropertyOS · Today">
      <div className="grid grid-cols-3 divide-x divide-limestone-200 border-b border-limestone-200">
        {[['Need you', '4'], ['Handled automatically', '46'], ['Waiting on others', '9']].map(([k, v]) => (
          <div key={k} className="px-4 py-3"><div className="text-2xl font-black tracking-tight text-graphite-950">{v}</div><div className="text-[11px] font-semibold text-graphite-500">{k}</div></div>
        ))}
      </div>
      <ul className="divide-y divide-limestone-200">
        {rows.map(([t, d, badge, cta]) => (
          <li key={t} className="flex items-center gap-3 px-4 py-3">
            <div className="min-w-0 flex-1"><div className="text-sm font-bold text-graphite-950">{t}</div><div className="truncate text-xs text-graphite-600">{d}</div></div>
            {badge}
            <span className="hidden rounded border border-limestone-300 px-3 py-1 text-xs font-bold text-federal-900 sm:inline">{cta}</span>
          </li>
        ))}
      </ul>
    </Frame>
  );
}

/** An applicant score that shows its reasons. */
export function ScoreShot() {
  const reasons: [boolean, string][] = [[true, 'Income is 3.1 times the monthly rent'], [true, 'No evictions or judgments on record'], [true, 'Employment verified, 4 years'], [false, 'Two late rent payments in the last 24 months']];
  return (
    <Frame title="Application · Maria Gomez">
      <div className="p-5">
        <div className="flex items-center gap-5">
          <div className="relative h-20 w-20 shrink-0">
            <svg viewBox="0 0 36 36" className="h-20 w-20 -rotate-90"><circle cx="18" cy="18" r="15.9" fill="none" stroke="#E8ECEA" strokeWidth="3.5" /><circle cx="18" cy="18" r="15.9" fill="none" stroke="#1F6869" strokeWidth="3.5" strokeDasharray="82 100" strokeLinecap="round" /></svg>
            <span className="absolute inset-0 flex items-center justify-center text-2xl font-black text-graphite-950">82</span>
          </div>
          <div><div className="text-base font-extrabold text-graphite-950">Recommended: approve</div><div className="text-xs text-graphite-600">Scored against your screening rules. Needs broker approval.</div></div>
        </div>
        <ul className="mt-5 space-y-2.5">
          {reasons.map(([ok, text]) => (
            <li key={text} className="flex items-start gap-2.5 text-sm text-graphite-800">
              {ok ? <Check className="mt-0.5 h-4 w-4 shrink-0 text-federal-700" /> : <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />}{text}
            </li>
          ))}
        </ul>
        <div className="mt-5 flex gap-2"><span className="rounded bg-federal-900 px-4 py-2 text-xs font-bold text-white">Approve</span><span className="rounded border border-limestone-300 px-4 py-2 text-xs font-bold text-graphite-800">Ask for more</span></div>
      </div>
    </Frame>
  );
}

/** A repair request triaged and routed. */
export function TriageShot() {
  return (
    <Frame title="Work order · Unit 7A">
      <div className="p-5">
        <p className="rounded-lg bg-limestone-100 p-3 text-sm italic text-graphite-700">“There’s water dripping from the kitchen ceiling and it’s getting worse.”</p>
        <dl className="mt-4 grid grid-cols-3 gap-3 text-center">
          {[['Trade', 'Plumbing'], ['Priority', 'Urgent'], ['Estimate', '$180 to $320']].map(([k, v]) => (
            <div key={k} className="rounded-lg border border-limestone-200 px-2 py-2.5"><dt className="text-[11px] font-semibold text-graphite-500">{k}</dt><dd className="mt-0.5 text-sm font-extrabold text-graphite-950">{v}</dd></div>
          ))}
        </dl>
        <div className="mt-4 text-xs font-bold text-graphite-600">Best available vendors</div>
        <ul className="mt-2 divide-y divide-limestone-200 rounded-lg border border-limestone-200">
          {[['Northside Plumbing', '4.8 rating · 40 min away', 'Dispatched'], ['Reliable Rooter', '4.6 rating · 55 min away', 'Backup']].map(([n, d, s], i) => (
            <li key={n} className="flex items-center gap-3 px-3 py-2.5"><Wrench className="h-4 w-4 text-federal-700" /><span className="min-w-0 flex-1"><span className="block text-sm font-bold text-graphite-950">{n}</span><span className="block text-xs text-graphite-600">{d}</span></span>{pill(i === 0 ? 'good' : 'brand', s)}</li>
          ))}
        </ul>
        <div className="mt-3 flex items-center gap-1 text-xs font-semibold text-graphite-600"><ChevronRight className="h-3.5 w-3.5" />Owner approval is requested only above their limit.</div>
      </div>
    </Frame>
  );
}

/** An owner statement that closes itself. */
export function StatementShot() {
  const lines: [string, string, number][] = [['Rent collected', '$18,450.00', 100], ['Management fee', '− $1,476.00', 8], ['Repairs and maintenance', '− $1,240.00', 7], ['Net payout to owner', '$15,734.00', 85]];
  return (
    <Frame title="Owner statement · October">
      <div className="p-5">
        <div className="text-sm font-extrabold text-graphite-950">Harbor Street Portfolio</div>
        <div className="text-xs text-graphite-600">12 units · all invoices reconciled</div>
        <ul className="mt-4 space-y-4">
          {lines.map(([k, v, w], i) => (
            <li key={k}>
              <div className="flex justify-between text-sm"><span className={i === 3 ? 'font-extrabold text-graphite-950' : 'text-graphite-700'}>{k}</span><span className={`tabular-nums ${i === 3 ? 'font-extrabold text-federal-900' : 'font-semibold text-graphite-900'}`}>{v}</span></div>
              <div className="mt-1.5 h-1.5 rounded-full bg-limestone-200"><div className={`h-1.5 rounded-full ${i === 3 ? 'bg-federal-700' : i === 0 ? 'bg-federal-300' : 'bg-brick-500'}`} style={{ width: `${w}%` }} /></div>
            </li>
          ))}
        </ul>
        <div className="mt-5 flex items-center gap-2 text-xs font-semibold text-federal-800"><Check className="h-4 w-4" />Sent to the owner and paid out on schedule</div>
      </div>
    </Frame>
  );
}
