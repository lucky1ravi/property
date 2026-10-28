import { type ReactNode, useState } from 'react';
import clsx from 'clsx';
import { motion } from 'framer-motion';

export function PageHeader({ title, description, actions, meta, back }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; meta?: ReactNode; back?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
      <div className="min-w-0">
        {back && <div className="mb-2">{back}</div>}
        <h1 className="text-[26px] font-extrabold leading-tight tracking-[-0.015em] text-graphite-950">{title}</h1>
        {description && <p className="mt-1 max-w-[68ch] text-[15px] text-graphite-600">{description}</p>}
        {meta && <div className="mt-2 flex flex-wrap items-center gap-2 text-sm text-graphite-600">{meta}</div>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** A section of a page. Not a floating card: a ruled panel whose header carries its title and actions. */
export function Panel({ title, description, actions, children, className, padded = true, tone = 'plain' }: {
  title?: ReactNode; description?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; padded?: boolean; tone?: 'plain' | 'tinted' | 'alert';
}) {
  return (
    <section className={clsx('rounded-md border bg-white shadow-panel',
      tone === 'tinted' && 'border-federal-100 bg-federal-50/50',
      tone === 'alert' && 'border-brick-200 bg-brick-50/40',
      tone === 'plain' && 'border-limestone-200', className)}>
      {(title || actions) && (
        <header className="flex items-start justify-between gap-4 border-b border-limestone-200 px-5 py-3.5">
          <div className="min-w-0">
            {title && <h2 className="text-[15px] font-bold text-graphite-900">{title}</h2>}
            {description && <p className="mt-0.5 text-[13px] text-graphite-500">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={clsx(padded && 'p-5')}>{children}</div>
    </section>
  );
}

export function Tabs<T extends string>({ tabs, value, onChange, className }: { tabs: { key: T; label: ReactNode; count?: number }[]; value: T; onChange: (v: T) => void; className?: string }) {
  return (
    <div role="tablist" className={clsx('flex gap-1 overflow-x-auto border-b border-limestone-200', className)}>
      {tabs.map((t) => (
        <button
          key={t.key}
          role="tab"
          aria-selected={value === t.key}
          onClick={() => onChange(t.key)}
          className={clsx('relative -mb-px whitespace-nowrap px-3 py-2.5 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slateblue-600/40',
            value === t.key ? 'text-federal-900' : 'text-graphite-500 hover:text-graphite-800')}
        >
          {t.label}
          {t.count !== undefined && (
            <span className={clsx('ml-1.5 rounded-full px-1.5 py-px text-2xs tabular-nums', value === t.key ? 'bg-federal-900 text-white' : 'bg-limestone-200 text-graphite-600')}>{t.count}</span>
          )}
          {value === t.key && <motion.span layoutId={`tab-underline-${tabs.map((x) => x.key).join('')}`} className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-federal-900" />}
        </button>
      ))}
    </div>
  );
}

export function EmptyState({ icon, title, body, action }: { icon?: ReactNode; title: string; body?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-12 text-center">
      {icon && <div className="mb-3 text-federal-700">{icon}</div>}
      <p className="text-[15px] font-bold text-graphite-900">{title}</p>
      {body && <p className="mt-1 max-w-[46ch] text-sm text-graphite-500">{body}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/** A figure with its label; `emphasis` makes one number on a page the anchor. */
export function Stat({ label, value, sub, trend, emphasis, onClick }: { label: string; value: ReactNode; sub?: ReactNode; trend?: { value: string; good: boolean }; emphasis?: boolean; onClick?: () => void }) {
  const Comp: any = onClick ? 'button' : 'div';
  return (
    <Comp onClick={onClick} className={clsx('min-w-0 text-left', onClick && 'rounded transition-colors hover:bg-limestone-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-slateblue-600')}>
      <div className="text-[13px] font-medium text-graphite-500">{label}</div>
      <div className={clsx('mt-1 font-extrabold tabular-nums tracking-[-0.02em] text-graphite-950', emphasis ? 'text-[34px] leading-none' : 'text-[22px] leading-tight')}>{value}</div>
      {(sub || trend) && (
        <div className="mt-1 flex items-center gap-2 text-xs text-graphite-500">
          {trend && <span className={clsx('font-semibold', trend.good ? 'text-good-700' : 'text-crit-700')}>{trend.value}</span>}
          {sub}
        </div>
      )}
    </Comp>
  );
}

/** A horizontal strip of figures separated by rules (instead of a grid of identical cards). */
export function StatStrip({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={clsx('grid grid-cols-2 divide-limestone-200 rounded-md border border-limestone-200 bg-white shadow-panel md:flex md:divide-x [&>*]:px-5 [&>*]:py-4 md:[&>*]:flex-1', className)}>{children}</div>;
}

export function Collapsible({ title, children, defaultOpen = false }: { title: ReactNode; children: ReactNode; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="border-b border-limestone-200 last:border-0">
      <button onClick={() => setOpen(!open)} className="flex w-full items-center justify-between py-3 text-left text-sm font-semibold text-graphite-900" aria-expanded={open}>
        {title}
        <span className={clsx('text-graphite-400 transition-transform', open && 'rotate-180')}>⌄</span>
      </button>
      {open && <div className="pb-4">{children}</div>}
    </div>
  );
}

export function KeyValues({ items, cols = 2 }: { items: { label: string; value: ReactNode }[]; cols?: 1 | 2 | 3 }) {
  return (
    <dl className={clsx('grid gap-x-6 gap-y-3', cols === 1 && 'grid-cols-1', cols === 2 && 'grid-cols-1 sm:grid-cols-2', cols === 3 && 'grid-cols-2 lg:grid-cols-3')}>
      {items.map((i) => (
        <div key={i.label} className="min-w-0">
          <dt className="text-xs font-medium text-graphite-500">{i.label}</dt>
          <dd className="mt-0.5 truncate text-sm font-medium text-graphite-900">{i.value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}
