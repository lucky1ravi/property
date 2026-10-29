import { useTitle } from './data';
import { ButtonLink, Container, CtaBand, PageHero } from './parts';

const POINTS: { title: string; body: string }[] = [
  { title: 'Companies are separated in the database', body: 'Every record belongs to one company, and the database refuses to return another company’s rows, even if an application screen asked for them. We test this by signing in as one company and trying to see another’s people, roles and files.' },
  { title: 'Access follows the job', body: 'Roles decide what each person can see and do. Owners, tenants and vendors see only their own properties, leases or jobs. Administrators can change roles, and every change is recorded.' },
  { title: 'An audit trail nobody can edit', body: 'Sign-ins, approvals, configuration changes and status changes are logged with who and when. Entries cannot be edited or deleted from the product.' },
  { title: 'Passwords are never stored in the clear', body: 'New administrators receive a one-time temporary password and must choose their own before they can sign in. Passwords are stored as salted hashes.' },
  { title: 'Safeguards at the edge', body: 'Requests are checked and rate limited before they reach a service. Services trust each other only with signed, internal credentials.' },
  { title: 'Files are private', body: 'Documents and photos are stored privately and per company. They are served only to people who are allowed to see them.' },
];

export function Security() {
  useTitle('Security');
  return (
    <>
      <PageHero title="Built so one company can never see another’s data" lead="Property records hold people’s names, leases and money. These are the protections that keep them private.">
        <ButtonLink to="/contact">Ask a security question</ButtonLink>
      </PageHero>
      <Container className="py-14">
        <ul className="grid gap-x-12 gap-y-10 md:grid-cols-2">
          {POINTS.map((p) => (
            <li key={p.title}>
              <h2 className="text-xl font-extrabold tracking-[-0.01em] text-graphite-950">{p.title}</h2>
              <p className="mt-2 max-w-[56ch] leading-relaxed text-graphite-700">{p.body}</p>
            </li>
          ))}
        </ul>
      </Container>
      <CtaBand title="Need details for your own review?" body="Send us your security questionnaire or questions and we’ll answer them in writing." />
    </>
  );
}
