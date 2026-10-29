import { useSite, useTitle } from './data';
import { ButtonLink, Container, CtaBand, Included, LoadingBlock, PageHero, PlanCard, SiteError } from './parts';

const FAQ: [string, string][] = [
  ['How is PropertyOS priced?', 'You pay a price per unit you manage each month, with a monthly minimum for the plan. A unit is one rentable unit.'],
  ['Can I add a feature my plan doesn’t include?', 'Yes. Features outside your plan can be switched on for your company as an add-on. Contact us and we’ll set it up.'],
  ['Can I change plans later?', 'Yes. We change your plan and the features on it straight away, and your data stays where it is.'],
  ['Who sets up my company?', 'We create your company, its roles and its first administrator. They get a temporary password and choose their own at first sign-in.'],
];

export function Plans() {
  useTitle('Plans and pricing');
  const { data, isLoading, isError } = useSite();
  const titles = Object.fromEntries((data?.features ?? []).map((f) => [f.key, f.title]));
  return (
    <>
      <PageHero title="Plans that grow with your portfolio" lead="Pick the plan that matches how you work today. Every plan runs on the same platform, so moving up never means starting over.">
        <ButtonLink to="/contact?topic=PRICING">Get a quote</ButtonLink>
      </PageHero>
      {isLoading && <LoadingBlock />}
      {isError && <SiteError />}
      {data && (
        <>
          <Container className="py-14">
            <div className="grid gap-6 lg:grid-cols-3">{data.plans.map((p) => <PlanCard key={p.key} plan={p} titles={titles} />)}</div>
          </Container>

          <Container className="pb-16">
            <h2 className="text-2xl font-black tracking-[-0.02em] text-graphite-950">Compare every feature</h2>
            <div className="mt-6 overflow-x-auto rounded-lg border border-limestone-300">
              <table className="w-full min-w-[560px] border-collapse text-left text-sm">
                <caption className="sr-only">Features included in each plan</caption>
                <thead className="bg-limestone-100">
                  <tr>
                    <th scope="col" className="px-4 py-3 font-bold text-graphite-900">Feature</th>
                    {data.plans.map((p) => <th key={p.key} scope="col" className="px-4 py-3 text-center font-bold text-graphite-900">{p.name}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {data.features.map((f) => (
                    <tr key={f.key} className="border-t border-limestone-200">
                      <th scope="row" className="px-4 py-3 align-top font-semibold text-graphite-900">{f.title}<span className="block text-xs font-normal text-graphite-600">{f.summary}</span></th>
                      {data.plans.map((p) => <td key={p.key} className="px-4 py-3 text-center"><Included yes={p.allFeatures || p.features.includes(f.key)} /></td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Container>
        </>
      )}

      <section className="bg-limestone-100">
        <Container className="grid gap-10 py-16 lg:grid-cols-[1fr_2fr]">
          <h2 className="text-3xl font-black tracking-[-0.02em] text-graphite-950">Questions about pricing</h2>
          <dl className="divide-y divide-limestone-300">
            {FAQ.map(([q, a]) => <div key={q} className="py-5"><dt className="font-bold text-graphite-950">{q}</dt><dd className="mt-1.5 max-w-[62ch] leading-relaxed text-graphite-700">{a}</dd></div>)}
          </dl>
        </Container>
      </section>
      <CtaBand title="Not sure which plan fits?" body="Tell us how many doors you manage and we’ll recommend one. See also what each part of the product does on the products page." />
    </>
  );
}
