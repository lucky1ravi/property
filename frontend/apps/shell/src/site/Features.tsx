import { PRODUCTS, useSite, useTitle } from './data';
import { ButtonLink, Container, CtaBand, LoadingBlock, PageHero, SiteError } from './parts';

export function Features() {
  useTitle('Features');
  const { data, isLoading, isError } = useSite();
  return (
    <>
      <PageHero title="Everything a property business runs on" lead="Each feature below is part of the platform today. The label beside it shows the lowest plan that includes it.">
        <ButtonLink to="/plans">Compare plans</ButtonLink>
      </PageHero>
      {isLoading && <LoadingBlock />}
      {isError && <SiteError />}
      {data && (
        <Container className="space-y-14 py-14">
          {PRODUCTS.map((p) => {
            const items = data.features.filter((f) => f.product === p.key);
            if (!items.length) return null;
            return (
              <section key={p.key} aria-labelledby={`h-${p.key}`}>
                <h2 id={`h-${p.key}`} className="border-b-2 border-federal-900 pb-2 text-2xl font-black tracking-[-0.02em] text-federal-900">{p.name}</h2>
                <ul className="mt-6 grid gap-x-10 gap-y-7 md:grid-cols-2">
                  {items.map((f) => {
                    const lowest = data.plans.find((pl) => pl.allFeatures || pl.features.includes(f.key));
                    return (
                      <li key={f.key}>
                        <div className="flex flex-wrap items-baseline gap-x-3">
                          <h3 className="text-lg font-extrabold text-graphite-950">{f.title}</h3>
                          <span className="text-xs font-semibold text-federal-800">{lowest ? `From ${lowest.name}` : 'On request'}</span>
                        </div>
                        <p className="mt-1.5 max-w-[52ch] leading-relaxed text-graphite-700">{f.summary}</p>
                      </li>
                    );
                  })}
                </ul>
              </section>
            );
          })}
        </Container>
      )}
      <CtaBand />
    </>
  );
}
