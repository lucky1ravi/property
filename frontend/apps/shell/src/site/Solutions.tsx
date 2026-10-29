import { useTitle } from './data';
import { ButtonLink, Container, CtaBand, PageHero } from './parts';

const ROLES: { id: string; role: string; headline: string; points: string[] }[] = [
  { id: 'brokers', role: 'Brokers and company owners', headline: 'See what needs you, and nothing else', points: ['A daily queue of approvals and exceptions', 'Opportunity reports across the whole portfolio', 'Control of fees, limits and rules without a developer'] },
  { id: 'managers', role: 'Property managers and leasing agents', headline: 'Fewer calls, faster turnarounds', points: ['Enquiries answered and showings booked for you', 'Repairs triaged and dispatched with the right vendor', 'One record of each property, lease and resident'] },
  { id: 'accountants', role: 'Accountants', headline: 'Books that reconcile as the work happens', points: ['Charges, invoices and deposits tied to the lease', 'Late fees held inside state caps', 'Exceptions arrive with the context to decide'] },
  { id: 'owners', role: 'Property owners', headline: 'Your properties and payouts, without asking', points: ['Statements and payouts on schedule', 'Approve larger repairs from one screen', 'See your properties and no one else’s'] },
  { id: 'residents', role: 'Residents', headline: 'Pay, ask and report in one place', points: ['Pay rent and see what is due', 'Report a problem in plain words and follow it to the fix', 'Sign leases and renewals online'] },
  { id: 'vendors', role: 'Vendors', headline: 'Clear jobs, quick sign-off', points: ['See the jobs sent to you with the details you need', 'Quote and close out work', 'Build a track record through resident ratings'] },
];

export function Solutions() {
  useTitle('Solutions');
  return (
    <>
      <PageHero title="A view built for every role" lead="Each person signs in to the screens, data and approvals that match their job, and nothing more.">
        <ButtonLink to="/contact">Request a demo</ButtonLink><ButtonLink to="/features" variant="secondary">See features</ButtonLink>
      </PageHero>
      <Container className="py-6">
        <ul className="divide-y divide-limestone-200">
          {ROLES.map((r) => (
            <li key={r.id} id={r.id} className="grid scroll-mt-24 gap-6 py-12 lg:grid-cols-[1fr_1.2fr] lg:gap-16">
              <div><h2 className="text-sm font-bold text-federal-700">{r.role}</h2><p className="mt-2 max-w-[20ch] text-[28px] font-black leading-tight tracking-[-0.025em] text-graphite-950">{r.headline}</p></div>
              <ul className="space-y-3 self-center text-[16px] text-graphite-800">
                {r.points.map((p) => <li key={p} className="flex gap-3 border-b border-limestone-200 pb-3 last:border-0"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-federal-700" />{p}</li>)}
              </ul>
            </li>
          ))}
        </ul>
      </Container>
      <CtaBand />
    </>
  );
}
