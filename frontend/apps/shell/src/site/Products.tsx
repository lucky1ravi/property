import { PRODUCTS, useSite, useTitle } from './data';
import { ButtonLink, Container, CtaBand, PageHero } from './parts';

export function Products() {
  useTitle('Products');
  const { data } = useSite();
  return (
    <>
      <PageHero title="One platform, six jobs done" lead="PropertyOS covers leasing, rent, maintenance, automation, broker insight and documents. Each part works alone and works better with the others.">
        <ButtonLink to="/plans">See plans</ButtonLink><ButtonLink to="/contact" variant="secondary">Book a demo</ButtonLink>
      </PageHero>
      <Container className="divide-y divide-limestone-200 py-6">
        {PRODUCTS.map((p) => {
          const items = (data?.features ?? []).filter((f) => f.product === p.key);
          return (
            <section key={p.key} id={p.key.toLowerCase()} className="grid gap-8 py-12 lg:grid-cols-[1.1fr_1fr]">
              <div>
                <h2 className="text-sm font-bold text-federal-700">{p.name}</h2>
                <p className="mt-2 max-w-[22ch] text-3xl font-black leading-tight tracking-[-0.025em] text-graphite-950">{p.headline}</p>
                <p className="mt-4 max-w-[58ch] text-[17px] leading-relaxed text-graphite-700">{p.body}</p>
                <p className="mt-4 text-sm text-graphite-600"><span className="font-semibold text-graphite-800">Used by:</span> {p.who}</p>
                {items.length > 0 && (
                  <ul className="mt-5 flex flex-wrap gap-2">
                    {items.map((f) => <li key={f.key} className="rounded-full bg-federal-50 px-3 py-1 text-sm font-semibold text-federal-900">{f.title}</li>)}
                  </ul>
                )}
              </div>
              <ol aria-label={`How ${p.name} flows`} className="self-center rounded-lg bg-limestone-100 p-6">
                {p.path.map((s, i) => (
                  <li key={s} className="flex items-center gap-3 py-2.5 text-[15px] font-semibold text-graphite-900">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-federal-900 text-xs font-bold text-white">{i + 1}</span>{s}
                  </li>
                ))}
              </ol>
            </section>
          );
        })}
      </Container>
      <CtaBand />
    </>
  );
}
