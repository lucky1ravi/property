import { Children, Fragment, forwardRef, isValidElement, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ChangeEvent, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import clsx from 'clsx';
import { createPortal } from 'react-dom';
import { Check, ChevronDown, Search as SearchIcon } from 'lucide-react';
import { fmt } from '../lib/format';

// ---------------- Button ----------------
type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'accent' | 'quiet';
type Size = 'sm' | 'md' | 'lg';

const variants: Record<Variant, string> = {
  primary: 'bg-federal-900 text-white hover:bg-federal-800 active:bg-federal-950 shadow-sm',
  accent: 'bg-brick-600 text-white hover:bg-brick-700 active:bg-brick-800 shadow-sm',
  secondary: 'bg-white text-graphite-900 border border-limestone-300 hover:border-graphite-400 hover:bg-limestone-50',
  ghost: 'text-federal-900 hover:bg-federal-50',
  quiet: 'text-graphite-600 hover:text-graphite-900 hover:bg-limestone-200/60',
  danger: 'bg-white text-crit-600 border border-crit-100 hover:bg-crit-50 hover:border-crit-600/40',
};
const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-[13px] gap-1.5',
  md: 'h-9 px-4 text-sm gap-2',
  lg: 'h-11 px-5 text-[15px] gap-2',
};

/**
 * Props for {@link Button}.
 */
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

/**
 * Button with variants and a loading state.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading, icon, className, children, disabled, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      className={clsx(
        'inline-flex items-center justify-center rounded font-semibold whitespace-nowrap transition-colors',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slateblue-600',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        variants[variant],
        sizes[size],
        className,
      )}
      {...rest}
    >
      {loading ? <Spinner className="h-3.5 w-3.5" /> : icon}
      {children}
    </button>
  );
});

/**
 * Small loading indicator.
 */
export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={clsx('animate-spin', className ?? 'h-4 w-4')} viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

/**
 * Grey placeholder block shown while data loads.
 */
export function Skeleton({ className }: { className?: string }) {
  return <div className={clsx('animate-pulse rounded bg-limestone-200', className)} />;
}

// ---------------- Status ----------------
type Tone = 'good' | 'warn' | 'crit' | 'info' | 'neutral' | 'brand';

const toneClass: Record<Tone, string> = {
  good: 'bg-good-50 text-good-700 ring-good-600/20',
  warn: 'bg-warn-50 text-warn-700 ring-warn-600/25',
  crit: 'bg-crit-50 text-crit-700 ring-crit-600/20',
  info: 'bg-slateblue-50 text-slateblue-700 ring-slateblue-600/20',
  neutral: 'bg-limestone-100 text-graphite-600 ring-graphite-400/25',
  brand: 'bg-federal-50 text-federal-800 ring-federal-700/20',
};

const GOOD = ['LIVE', 'ACTIVE', 'APPROVED', 'PAID', 'CLOSED', 'COMPLETED', 'VERIFIED', 'ATTENDED', 'SIGNED', 'PASS', 'OCCUPIED', 'SENT_PAID', 'SUCCEEDED', 'DONE', 'RESOLVED', 'ON_TRACK', 'LOW'];
const WARN = ['AWAITING_APPROVAL', 'DOCS_PENDING', 'FLAGGED', 'OWNER_REVIEW', 'PARTIALLY_PAID', 'PENDING', 'NOTICE_GIVEN', 'RENEWAL_OFFERED', 'URGENT', 'TENANT_SIGNED', 'SENT', 'DUE', 'AT_RISK', 'MEDIUM', 'VACANT', 'PAUSED', 'ENDED', 'SCHEDULED', 'REVIEW'];
const CRIT = ['OVERDUE', 'REJECTED', 'DELINQUENT', 'EMERGENCY', 'NO_SHOW', 'FAILED', 'BREACHED', 'VOID', 'DECLINED', 'HIGH', 'CRITICAL', 'CANCELLED', 'WITHDRAWN'];

/**
 * Chooses a colour tone for a status word such as OVERDUE or APPROVED.
 */
export function toneFor(status?: string): Tone {
  const s = (status ?? '').toUpperCase();
  if (GOOD.includes(s)) return 'good';
  if (WARN.includes(s)) return 'warn';
  if (CRIT.includes(s)) return 'crit';
  if (['DRAFT', 'NEW', 'ROUTINE'].includes(s)) return 'neutral';
  return 'info';
}

/**
 * Rounded label for a status, coloured by {@link toneFor} unless a tone is given.
 */
export function StatusPill({ status, label, tone, className }: { status?: string; label?: string; tone?: Tone; className?: string }) {
  const t = tone ?? toneFor(status);
  return (
    <span className={clsx('inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset whitespace-nowrap', toneClass[t], className)}>
      <span className={clsx('h-1.5 w-1.5 rounded-full', {
        'bg-good-600': t === 'good', 'bg-warn-600': t === 'warn', 'bg-crit-600': t === 'crit',
        'bg-slateblue-600': t === 'info', 'bg-graphite-400': t === 'neutral', 'bg-federal-700': t === 'brand',
      })} />
      {label ?? fmt.title(status)}
    </span>
  );
}

/**
 * Small coloured label.
 */
export function Badge({ children, tone = 'neutral', className }: { children: ReactNode; tone?: Tone; className?: string }) {
  return <span className={clsx('inline-flex items-center rounded px-1.5 py-0.5 text-2xs font-semibold ring-1 ring-inset', toneClass[tone], className)}>{children}</span>;
}

// ---------------- Avatar ----------------
export function Avatar({ name, size = 32, color, seed, src, ring = false }: { name?: string; size?: number; color?: string; seed?: string; src?: string | null; ring?: boolean }) {
  const [failed, setFailed] = useState(false);
  const key = (seed || name || '').trim().toLowerCase();
  const url = src ?? (key ? `https://i.pravatar.cc/${Math.max(64, size * 2)}?u=${encodeURIComponent(key)}` : null);
  const base = `inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full font-semibold text-white ${ring ? 'ring-2 ring-white shadow-sm' : ''}`;
  if (url && !failed) {
    return (
      <span className={`${base} bg-limestone-200`} style={{ width: size, height: size }}>
        <img src={url} alt={name ? `${name}, profile photo` : ''} width={size} height={size} loading="lazy" onError={() => setFailed(true)} className="h-full w-full object-cover" />
      </span>
    );
  }
  return (
    <span className={base} style={{ width: size, height: size, fontSize: size * 0.38, background: color ?? hashColor(name ?? '') }} aria-hidden>
      {fmt.initials(name)}
    </span>
  );
}

/** Round photo + name (+ optional secondary line) for table cells and lists. */
export function PersonCell({ name, sub, size = 28, seed }: { name?: string | null; sub?: ReactNode; size?: number; seed?: string }) {
  if (!name) return <span className="text-graphite-400">—</span>;
  return (
    <span className="inline-flex items-center gap-2.5">
      <Avatar name={name} size={size} seed={seed} />
      <span className="min-w-0"><span className="block truncate">{name}</span>{sub && <span className="block truncate text-xs font-normal text-graphite-500">{sub}</span>}</span>
    </span>
  );
}

const AVATAR_COLORS = ['#0F3D3E', '#1F6869', '#3B6E9C', '#A23E2B', '#2E7D5B', '#6B4E9B', '#8B5E34', '#2D5579'];
function hashColor(s: string) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

// ---------------- Form controls ----------------
export function Field({ label, hint, error, children, className, required }: { label: string; hint?: string; error?: string; children: ReactNode; className?: string; required?: boolean }) {
  return (
    <label className={clsx('block', className)}>
      <span className="mb-1 block text-[13px] font-semibold text-graphite-800">
        {label}
        {required && <span className="text-brick-600"> *</span>}
      </span>
      {children}
      {hint && !error && <span className="mt-1 block text-xs text-graphite-500">{hint}</span>}
      {error && <span className="mt-1 block text-xs font-medium text-crit-600">{error}</span>}
    </label>
  );
}

const inputBase =
  'w-full rounded border border-limestone-300 bg-white px-3 text-sm text-graphite-900 placeholder:text-graphite-400 ' +
  'focus:border-federal-700 focus:outline-none focus:ring-2 focus:ring-federal-700/15 disabled:bg-limestone-100';

/**
 * Styled text input; forwards its ref.
 */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...rest }, ref) {
  return <input ref={ref} className={clsx(inputBase, 'h-9', className)} {...rest} />;
});

/**
 * Styled multi-line input; forwards its ref.
 */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea({ className, ...rest }, ref) {
  return <textarea ref={ref} className={clsx(inputBase, 'py-2 leading-relaxed', className)} {...rest} />;
});

interface Opt { value: string; label: string; disabled?: boolean }

function textOf(n: ReactNode): string {
  if (n == null || typeof n === 'boolean') return '';
  if (typeof n === 'string' || typeof n === 'number') return String(n);
  if (Array.isArray(n)) return n.map(textOf).join('');
  if (isValidElement(n)) return textOf((n.props as any).children);
  return '';
}

function collectOptions(children: ReactNode, out: Opt[] = []): Opt[] {
  Children.forEach(children, (c) => {
    if (!isValidElement(c)) return;
    const props = c.props as any;
    if (c.type === 'option') {
      const label = textOf(props.children);
      out.push({ value: props.value != null ? String(props.value) : label, label, disabled: props.disabled });
    } else if (c.type === 'optgroup' || c.type === Fragment) collectOptions(props.children, out);
  });
  return out;
}

/**
 * A drop-in replacement for the native select that every dropdown in the product uses: same props and the same
 * `onChange(e)` with `e.target.value`, plus a search box, keyboard control and a list that is never clipped by a dialog.
 */
export const Select = forwardRef<HTMLButtonElement, Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange'> & { onChange?: (e: ChangeEvent<HTMLSelectElement>) => void }>(function Select(
  { className, children, value, defaultValue, onChange, disabled, name, id, required, ...rest }, ref) {
  const options = useMemo(() => collectOptions(children), [children]);
  const [inner, setInner] = useState<string>(String(defaultValue ?? ''));
  const current = value !== undefined ? String(value ?? '') : inner;
  const selected = options.find((o) => o.value === current);
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [hi, setHi] = useState(0);
  const [pos, setPos] = useState<{ left: number; top: number; width: number; up: boolean }>({ left: 0, top: 0, width: 240, up: false });
  const btn = useRef<HTMLButtonElement | null>(null);
  const box = useRef<HTMLDivElement | null>(null);
  const search = useRef<HTMLInputElement | null>(null);
  const listId = useId();
  const shown = useMemo(() => {
    const n = q.trim().toLowerCase();
    return n ? options.filter((o) => o.label.toLowerCase().includes(n) || o.value.toLowerCase().includes(n)) : options;
  }, [options, q]);

  const place = () => {
    const r = btn.current?.getBoundingClientRect();
    if (!r) return;
    const room = window.innerHeight - r.bottom;
    const up = room < 280 && r.top > room;
    setPos({ left: r.left, top: up ? r.top : r.bottom, width: Math.max(r.width, 200), up });
  };
  useLayoutEffect(() => { if (open) place(); }, [open]);
  useEffect(() => {
    if (!open) return;
    setQ('');
    setHi(Math.max(0, options.findIndex((o) => o.value === current)));
    const t = setTimeout(() => search.current?.focus(), 0);
    const away = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node) && !btn.current?.contains(e.target as Node)) setOpen(false); };
    const move = (e: Event) => { if (!box.current?.contains(e.target as Node)) place(); };
    document.addEventListener('mousedown', away);
    window.addEventListener('resize', place);
    window.addEventListener('scroll', move, true);
    return () => { clearTimeout(t); document.removeEventListener('mousedown', away); window.removeEventListener('resize', place); window.removeEventListener('scroll', move, true); };
  }, [open]);
  useEffect(() => { setHi(0); }, [q]);
  useEffect(() => { box.current?.querySelector<HTMLElement>('[data-hi="true"]')?.scrollIntoView({ block: 'nearest' }); }, [hi, open]);

  const choose = (o: Opt) => {
    if (o.disabled) return;
    setInner(o.value);
    setOpen(false);
    btn.current?.focus();
    if (o.value !== current) onChange?.({ target: { value: o.value, name }, currentTarget: { value: o.value, name } } as unknown as ChangeEvent<HTMLSelectElement>);
  };
  const keys = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setHi((h) => Math.min(shown.length - 1, h + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHi((h) => Math.max(0, h - 1)); }
    else if (e.key === 'Enter') { e.preventDefault(); if (shown[hi]) choose(shown[hi]); }
    else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); e.nativeEvent.stopPropagation(); setOpen(false); btn.current?.focus(); }
    else if (e.key === 'Tab') setOpen(false);
  };

  return (
    <>
      <button ref={(el) => { btn.current = el; if (typeof ref === 'function') ref(el); else if (ref) (ref as any).current = el; }} type="button" id={id} disabled={disabled}
        role="combobox" aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? listId : undefined} aria-label={(rest as any)['aria-label']} title={rest.title}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => { if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { e.preventDefault(); setOpen(true); } }}
        className={clsx(inputBase, 'relative flex h-9 items-center pr-8 text-left disabled:cursor-not-allowed', className)}>
        <span className={clsx('block truncate', !selected?.value && 'text-graphite-500')}>{selected?.label ?? ''}</span>
        <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-graphite-500" aria-hidden />
      </button>
      {name && <input type="hidden" name={name} value={current} required={required} />}
      {open && createPortal(
        <div ref={box} onKeyDown={keys} style={{ position: 'fixed', left: pos.left, top: pos.up ? undefined : pos.top + 4, bottom: pos.up ? window.innerHeight - pos.top + 4 : undefined, width: pos.width, zIndex: 1000 }}
          className="overflow-hidden rounded-md border border-limestone-300 bg-white shadow-lg">
          <div className="flex items-center gap-2 border-b border-limestone-200 px-3 py-2">
            <SearchIcon className="h-4 w-4 shrink-0 text-graphite-400" aria-hidden />
            <input ref={search} value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search" aria-label="Search options" aria-controls={listId}
              className="w-full bg-transparent text-sm text-graphite-900 outline-none placeholder:text-graphite-400" />
          </div>
          <ul id={listId} role="listbox" className="max-h-64 overflow-y-auto py-1">
            {shown.length === 0 && <li className="px-3 py-2 text-sm text-graphite-500">No matches</li>}
            {shown.map((o, i) => (
              <li key={`${o.value}-${i}`} role="option" aria-selected={o.value === current} aria-disabled={o.disabled} data-hi={i === hi}
                onMouseEnter={() => setHi(i)} onClick={() => choose(o)}
                className={clsx('flex cursor-pointer items-center justify-between gap-2 px-3 py-1.5 text-sm', i === hi && 'bg-limestone-100', o.disabled ? 'cursor-not-allowed text-graphite-400' : 'text-graphite-900', o.value === current && 'font-semibold')}>
                <span className="truncate">{o.label || '—'}</span>{o.value === current && <Check className="h-4 w-4 shrink-0 text-federal-800" aria-hidden />}
              </li>))}
          </ul>
        </div>, document.body)}
    </>
  );
});

/**
 * On/off switch with an optional label.
 */
export function Toggle({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label?: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={clsx(
        'relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slateblue-600 disabled:opacity-50',
        checked ? 'bg-federal-800' : 'bg-limestone-400',
      )}
    >
      <span className={clsx('inline-block h-4 w-4 rounded-full bg-white shadow transition-transform', checked ? 'translate-x-[18px]' : 'translate-x-0.5')} />
    </button>
  );
}

/**
 * Shows an amount in US dollars; whole dollars unless {@code cents} is set.
 */
export function Money({ value, cents, className, tone }: { value: number | string | null | undefined; cents?: boolean; className?: string; tone?: 'auto' | 'none' }) {
  const n = typeof value === 'string' ? parseFloat(value) : value ?? 0;
  return (
    <span className={clsx('tabular-nums', tone === 'auto' && n < 0 && 'text-crit-600', className)}>
      {fmt.usd(n, cents)}
    </span>
  );
}
