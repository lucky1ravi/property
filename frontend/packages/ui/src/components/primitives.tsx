import { forwardRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import clsx from 'clsx';
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

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

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

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={clsx('animate-spin', className ?? 'h-4 w-4')} viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

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

export function toneFor(status?: string): Tone {
  const s = (status ?? '').toUpperCase();
  if (GOOD.includes(s)) return 'good';
  if (WARN.includes(s)) return 'warn';
  if (CRIT.includes(s)) return 'crit';
  if (['DRAFT', 'NEW', 'ROUTINE'].includes(s)) return 'neutral';
  return 'info';
}

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

export function Badge({ children, tone = 'neutral', className }: { children: ReactNode; tone?: Tone; className?: string }) {
  return <span className={clsx('inline-flex items-center rounded px-1.5 py-0.5 text-2xs font-semibold ring-1 ring-inset', toneClass[tone], className)}>{children}</span>;
}

// ---------------- Avatar ----------------
export function Avatar({ name, size = 32, color }: { name?: string; size?: number; color?: string }) {
  return (
    <span
      className="inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white"
      style={{ width: size, height: size, fontSize: size * 0.38, background: color ?? hashColor(name ?? '') }}
      aria-hidden
    >
      {fmt.initials(name)}
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

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...rest }, ref) {
  return <input ref={ref} className={clsx(inputBase, 'h-9', className)} {...rest} />;
});

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea({ className, ...rest }, ref) {
  return <textarea ref={ref} className={clsx(inputBase, 'py-2 leading-relaxed', className)} {...rest} />;
});

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select({ className, children, ...rest }, ref) {
  return (
    <select ref={ref} className={clsx(inputBase, 'h-9 pr-8', className)} {...rest}>
      {children}
    </select>
  );
});

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

export function Money({ value, cents, className, tone }: { value: number | string | null | undefined; cents?: boolean; className?: string; tone?: 'auto' | 'none' }) {
  const n = typeof value === 'string' ? parseFloat(value) : value ?? 0;
  return (
    <span className={clsx('tabular-nums', tone === 'auto' && n < 0 && 'text-crit-600', className)}>
      {fmt.usd(n, cents)}
    </span>
  );
}
