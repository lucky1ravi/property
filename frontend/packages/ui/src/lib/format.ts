const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 });
const num = new Intl.NumberFormat('en-US');

export const fmt = {
  usd: (v: number | string | null | undefined, cents = false) => {
    const n = typeof v === 'string' ? parseFloat(v) : v ?? 0;
    return (cents ? usd2 : usd0).format(isNaN(n as number) ? 0 : (n as number));
  },
  num: (v: number | null | undefined) => num.format(v ?? 0),
  pct: (v: number | null | undefined, digits = 0) => `${(v ?? 0).toFixed(digits)}%`,
  date: (v?: string | null) => {
    if (!v) return '—';
    const d = new Date(v.length === 10 ? v + 'T12:00:00' : v);
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  },
  shortDate: (v?: string | null) => {
    if (!v) return '—';
    const d = new Date(v.length === 10 ? v + 'T12:00:00' : v);
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  },
  dateTime: (v?: string | null) => {
    if (!v) return '—';
    return new Date(v).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  },
  relative: (v?: string | null) => {
    if (!v) return '—';
    const diff = (Date.now() - new Date(v).getTime()) / 1000;
    const abs = Math.abs(diff);
    const future = diff < 0;
    const unit = abs < 60 ? ['second', abs] : abs < 3600 ? ['minute', abs / 60] : abs < 86400 ? ['hour', abs / 3600] : ['day', abs / 86400];
    const n = Math.floor(unit[1] as number);
    if ((unit[0] === 'second') && n < 45) return future ? 'in a moment' : 'just now';
    const label = `${n} ${unit[0]}${n === 1 ? '' : 's'}`;
    return future ? `in ${label}` : `${label} ago`;
  },
  title: (v?: string | null) =>
    !v ? '' : v.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase()),
  initials: (name?: string) =>
    (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p[0]?.toUpperCase())
      .join(''),
};
