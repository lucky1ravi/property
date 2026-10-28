import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis, Line, ComposedChart, Legend } from 'recharts';
import { fmt } from '../lib/format';

export const CHART = {
  primary: '#1F6869',
  primarySoft: '#9CC3C1',
  accent: '#A23E2B',
  info: '#3B6E9C',
  good: '#2E7D5B',
  warn: '#B7791F',
  grid: '#E8ECEA',
  axis: '#8A9592',
  series: ['#1F6869', '#3B6E9C', '#A23E2B', '#B7791F', '#6B4E9B', '#2E7D5B'],
};

const tooltipStyle = { borderRadius: 6, border: '1px solid #D5DCD9', fontSize: 12, boxShadow: '0 8px 24px -12px rgba(8,36,37,.3)' };

type Fmt = 'usd' | 'num' | 'pct';
const fmtv = (f: Fmt) => (v: number) => (f === 'usd' ? fmt.usd(v) : f === 'pct' ? `${v}%` : fmt.num(v));
const compact = (f: Fmt) => (v: number) => (f === 'usd' ? (Math.abs(v) >= 1000 ? `$${(v / 1000).toFixed(v >= 10000 ? 0 : 1)}k` : `$${v}`) : f === 'pct' ? `${v}%` : fmt.num(v));

export function TrendChart({ data, x, y, format = 'usd', height = 220, color = CHART.primary }: { data: any[]; x: string; y: string; format?: Fmt; height?: number; color?: string }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id={`g-${y}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.22} />
            <stop offset="100%" stopColor={color} stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid stroke={CHART.grid} vertical={false} />
        <XAxis dataKey={x} tick={{ fontSize: 11, fill: CHART.axis }} axisLine={false} tickLine={false} />
        <YAxis tickFormatter={compact(format)} tick={{ fontSize: 11, fill: CHART.axis }} axisLine={false} tickLine={false} width={48} />
        <Tooltip formatter={(v: number) => fmtv(format)(v)} contentStyle={tooltipStyle} />
        <Area type="monotone" dataKey={y} stroke={color} strokeWidth={2} fill={`url(#g-${y})`} />
      </AreaChart>
    </ResponsiveContainer>
  );
}

/** Two series compared over periods, e.g. rent billed vs collected. */
export function CompareChart({ data, x, bars, line, format = 'usd', height = 240 }: { data: any[]; x: string; bars: { key: string; label: string; color?: string }[]; line?: { key: string; label: string; color?: string }; format?: Fmt; height?: number }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <ComposedChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid stroke={CHART.grid} vertical={false} />
        <XAxis dataKey={x} tick={{ fontSize: 11, fill: CHART.axis }} axisLine={false} tickLine={false} />
        <YAxis tickFormatter={compact(format)} tick={{ fontSize: 11, fill: CHART.axis }} axisLine={false} tickLine={false} width={48} />
        <Tooltip formatter={(v: number) => fmtv(format)(v)} contentStyle={tooltipStyle} />
        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />
        {bars.map((b, i) => <Bar key={b.key} dataKey={b.key} name={b.label} fill={b.color ?? CHART.series[i]} radius={[3, 3, 0, 0]} maxBarSize={28} />)}
        {line && <Line type="monotone" dataKey={line.key} name={line.label} stroke={line.color ?? CHART.accent} strokeWidth={2} dot={false} />}
      </ComposedChart>
    </ResponsiveContainer>
  );
}

/** Ranked horizontal bars, e.g. spend by category or doors per broker. */
export function RankBars({ data, label, value, format = 'num', height, highlight }: { data: any[]; label: string; value: string; format?: Fmt; height?: number; highlight?: (row: any) => boolean }) {
  const h = height ?? Math.max(120, data.length * 34);
  return (
    <ResponsiveContainer width="100%" height={h}>
      <BarChart data={data} layout="vertical" margin={{ top: 0, right: 40, left: 0, bottom: 0 }}>
        <XAxis type="number" hide />
        <YAxis type="category" dataKey={label} width={140} tick={{ fontSize: 12, fill: '#3A4644' }} axisLine={false} tickLine={false} />
        <Tooltip formatter={(v: number) => fmtv(format)(v)} contentStyle={tooltipStyle} cursor={{ fill: '#F5F7F6' }} />
        <Bar dataKey={value} radius={[0, 3, 3, 0]} maxBarSize={18} label={{ position: 'right', fontSize: 11, fill: '#4F5B59', formatter: compact(format) }}>
          {data.map((row, i) => <Cell key={i} fill={highlight?.(row) ? CHART.accent : CHART.primary} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
