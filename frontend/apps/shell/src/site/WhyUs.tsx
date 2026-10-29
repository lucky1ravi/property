import { useTitle } from './data';
import { ButtonLink, Container, CtaBand, PageHero } from './parts';

const REASONS: { title: string; body: string }[] = [
  { title: 'It does the routine work and stops at the important parts', body: 'Listings, replies, reminders, triage and dispatch run on their own. Money, legal terms and safety always wait for a person to approve, so you stay in control of what matters.' },
  { title: 'Every decision comes with its reasons', body: 'When PropertyOS scores an applicant, ranks a vendor or flags a risk, it shows why. You can check the reasoning, override it, and see who did what in the audit log.' },
  { title: 'Built for every person in the business', body: 'Brokers, property managers, leasing agents, accountants, owners, tenants and vendors each sign in to a view made for their job. Nobody has to wade through screens that are not theirs.' },
  { title: 'Your rules, not ours', body: 'Fees, approval limits, response times, renewal terms and state rules are settings your administrators change, with a history of every change. Nothing needs a developer.' },
  { title: 'Each company’s data is kept apart by the database', body: 'Isolation between companies is enforced inside the database itself, not only in the application. A bug in a screen cannot show one company another’s records.' },
  { title: 'One system instead of five', body: 'Leasing, rent, maintenance, documents and messages share one record of each property and person, so nobody re-types anything or chases a spreadsheet.' },
];

export function WhyUs() {
  useTitle('Why PropertyOS');
  return (
    <>
      <PageHero title="Why property teams choose PropertyOS" lead="The goal is simple: fewer tasks that need you, and a clear reason for each one that does.">
        <ButtonLink to="/contact">Book a demo</ButtonLink><ButtonLink to="/plans" variant="secondary">See plans</ButtonLink>
      </PageHero>
      <Container className="py-6">
        <ul className="divide-y divide-limestone-200">
          {REASONS.map((r) => (
            <li key={r.title} className="grid gap-3 py-9 lg:grid-cols-[1fr_1.2fr] lg:gap-14">
              <h2 className="max-w-[24ch] text-2xl font-black leading-tight tracking-[-0.02em] text-graphite-950">{r.title}</h2>
              <p className="max-w-[60ch] text-[17px] leading-relaxed text-graphite-700">{r.body}</p>
            </li>
          ))}
        </ul>
      </Container>
      <section className="bg-limestone-100">
        <Container className="py-16">
          <h2 className="text-3xl font-black tracking-[-0.02em] text-graphite-950">Where a person stays in charge</h2>
          <div className="mt-8 grid gap-6 md:grid-cols-3">
            {[['Leasing', 'The broker approves the applicant.'], ['Rent', 'The accountant waives or approves exceptions.'], ['Maintenance', 'The broker handles emergencies.']].map(([a, b]) => (
              <div key={a} className="rounded-lg border border-limestone-300 bg-white p-6"><h3 className="text-lg font-extrabold text-federal-900">{a}</h3><p className="mt-2 text-graphite-700">{b}</p></div>
            ))}
          </div>
        </Container>
      </section>
      <CtaBand />
    </>
  );
}
