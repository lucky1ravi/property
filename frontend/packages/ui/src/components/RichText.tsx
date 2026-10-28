import { isValidElement, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { RankBars } from './charts';

/** Fenced ```chart blocks from the assistant: {"title": "...", "data": [{"label": "...", "value": 1}], "format": "usd"} */
function ChartBlock({ source }: { source: string }) {
  try {
    const c = JSON.parse(source);
    const data = (c.data ?? []).filter((d: any) => d && d.label != null && Number.isFinite(Number(d.value))).map((d: any) => ({ label: String(d.label), value: Number(d.value) }));
    if (data.length < 2) throw new Error('not enough data');
    return (
      <figure className="my-3 rounded-md border border-limestone-200 bg-white p-3">
        {c.title && <figcaption className="mb-2 text-xs font-bold text-graphite-800">{c.title}</figcaption>}
        <RankBars data={data} label="label" value="value" format={c.format === 'usd' ? 'usd' : 'num'} />
      </figure>
    );
  } catch {
    return null;
  }
}

const text = (n: ReactNode): string => (typeof n === 'string' ? n : Array.isArray(n) ? n.map(text).join('') : isValidElement(n) ? text((n.props as any).children) : '');
const numeric = (s: string) => /^[\s$€£+\-–]*[\d,]+(\.\d+)?\s*(%|h|days?|sqft)?$/.test(s.trim());

const components: Components = {
  p: ({ children }) => <p className="my-2 leading-relaxed first:mt-0 last:mb-0">{children}</p>,
  strong: ({ children }) => <strong className="font-bold text-graphite-950">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  h1: ({ children }) => <h3 className="mb-1 mt-3 text-base font-extrabold text-graphite-950">{children}</h3>,
  h2: ({ children }) => <h3 className="mb-1 mt-3 text-base font-extrabold text-graphite-950">{children}</h3>,
  h3: ({ children }) => <h4 className="mb-1 mt-3 text-sm font-extrabold text-graphite-950">{children}</h4>,
  ul: ({ children }) => <ul className="my-2 list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }) => <ol className="my-2 list-decimal space-y-1 pl-5">{children}</ol>,
  a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer" className="font-semibold text-federal-800 underline underline-offset-2">{children}</a>,
  hr: () => <hr className="my-3 border-limestone-200" />,
  blockquote: ({ children }) => <blockquote className="my-2 border-l-4 border-federal-200 bg-federal-50/60 px-3 py-1.5 text-graphite-700">{children}</blockquote>,
  img: ({ src, alt }) => <img src={src} alt={alt ?? ''} loading="lazy" className="my-2 max-h-44 w-full rounded-md border border-limestone-200 object-cover" />,
  table: ({ children }) => <div className="my-3 overflow-x-auto rounded-md border border-limestone-200"><table className="min-w-full border-collapse text-left text-[13px]">{children}</table></div>,
  thead: ({ children }) => <thead className="bg-limestone-100 text-graphite-700">{children}</thead>,
  th: ({ children }) => <th className={`whitespace-nowrap px-3 py-2 text-xs font-bold ${numeric(text(children)) ? 'text-right' : ''}`}>{children}</th>,
  td: ({ children }) => <td className={`border-t border-limestone-200 px-3 py-2 align-top ${numeric(text(children)) ? 'whitespace-nowrap text-right tabular-nums' : ''}`}>{children}</td>,
  pre: ({ children }) => <>{children}</>,
  code: ({ className, children }) => {
    const src = String(children).replace(/\n$/, '');
    if (/language-chart/.test(className ?? '')) return <ChartBlock source={src} />;
    return /language-/.test(className ?? '')
      ? <pre className="my-2 overflow-x-auto rounded bg-graphite-950 p-3 text-xs text-limestone-100"><code>{src}</code></pre>
      : <code className="rounded bg-limestone-100 px-1 py-0.5 text-[12px]">{children}</code>;
  },
};

/** Renders assistant answers: bold, lists, tables, images and simple bar charts. */
export function RichText({ children }: { children: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{children}</ReactMarkdown>;
}
