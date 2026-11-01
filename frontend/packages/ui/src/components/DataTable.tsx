import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import clsx from 'clsx';
import { Search, ArrowUpDown, ArrowUp, ArrowDown, Download, SlidersHorizontal, X, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';
import type { ServerPaging } from '../lib/hooks';
import { Skeleton, Select } from './primitives';
import { EmptyState } from './layout';

/**
 * One column of a {@link DataTable}: its heading, how to render a row's cell and whether it can be sorted.
 */
export interface Column<T> {
  key: string;
  header: ReactNode;
  render?: (row: T) => ReactNode;
  /** Value used for sorting, searching and CSV export. */
  value?: (row: T) => string | number | null | undefined;
  align?: 'left' | 'right' | 'center';
  width?: string;
  sortable?: boolean;
  hideOnMobile?: boolean;
  /** Column filter. Inferred from the data by default; set `false` to turn it off. */
  filter?: 'text' | 'select' | 'date' | 'number' | false;
}

/**
 * Props for {@link DataTable}.
 */
export interface DataTableProps<T> {
  rows: T[] | undefined;
  columns: Column<T>[];
  rowKey: (row: T) => string;
  loading?: boolean;
  onRowClick?: (row: T) => void;
  searchable?: boolean;
  searchPlaceholder?: string;
  toolbar?: ReactNode;
  empty?: { title: string; body?: string; action?: ReactNode };
  selectable?: boolean;
  bulkActions?: (selected: T[], clear: () => void) => ReactNode;
  exportName?: string;
  dense?: boolean;
  rowClassName?: (row: T) => string | undefined;
  initialSort?: { key: string; dir: 'asc' | 'desc' };
  /**
   * Set for lists the server pages (see `usePagedApi`): the table then asks the server for each page and sends it the sort,
   * search and filters, and `rows` is just the current page. Without it the table pages the rows it was given.
   */
  server?: ServerPaging<T>;
  /** Rows-per-page choices. The first is the default. */
  pageSizes?: number[];
}

export const PAGE_SIZES = [20, 50, 100];


// ---------------- Smart column filters ----------------
type FilterKind = 'text' | 'select' | 'date' | 'number';
interface Spec { kind: FilterKind; options?: string[] }
interface FilterState { text?: string; select?: string; preset?: string; from?: string; to?: string; op?: string; a?: string; b?: string }

const ISO_DATE = /^\d{4}-\d{2}-\d{2}/;
const ENUMISH = /status|type|kind|category|priority|state|method|source|role|persona|channel|stage|plan|frequency|severity|scope/i;
const iso = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (n: number) => { const d = new Date(); d.setDate(d.getDate() + n); return iso(d); };
const titleCase = (v: string) => (/^[A-Z0-9_]{2,}$/.test(v) ? v.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()) : v);

const DATE_PRESETS: { id: string; label: string; range: () => [string | null, string | null] }[] = [
  { id: 'today', label: 'Today', range: () => [iso(new Date()), iso(new Date())] },
  { id: 'next7', label: 'Next 7 days', range: () => [iso(new Date()), addDays(7)] },
  { id: 'next30', label: 'Next 30 days', range: () => [iso(new Date()), addDays(30)] },
  { id: 'last7', label: 'Last 7 days', range: () => [addDays(-7), iso(new Date())] },
  { id: 'last30', label: 'Last 30 days', range: () => [addDays(-30), iso(new Date())] },
  { id: 'month', label: 'This month', range: () => { const n = new Date(); return [iso(new Date(n.getFullYear(), n.getMonth(), 1)), iso(new Date(n.getFullYear(), n.getMonth() + 1, 0))]; } },
  { id: 'past', label: 'Before today', range: () => [null, addDays(-1)] },
  { id: 'future', label: 'After today', range: () => [addDays(1), null] },
];
const NUM_OPS: { id: string; label: string }[] = [
  { id: 'eq', label: '= equals' }, { id: 'ne', label: '≠ not equal' }, { id: 'gt', label: '> greater than' }, { id: 'ge', label: '≥ at least' },
  { id: 'lt', label: '< less than' }, { id: 'le', label: '≤ at most' }, { id: 'between', label: 'between' },
];

function inferSpec<T>(col: Column<T>, rows: T[], valueOf: (c: Column<T>, r: T) => unknown): Spec | null {
  if (col.filter === false || col.header === '' || col.key === 'actions') return null;
  if (col.filter) return { kind: col.filter, options: col.filter === 'select' ? distinct(col, rows, valueOf) : undefined };
  const vals = rows.map((r) => valueOf(col, r)).filter((v) => v !== null && v !== undefined && v !== '');
  if (vals.length === 0) return null;
  if (vals.every((v) => typeof v === 'number')) return { kind: 'number' };
  if (vals.every((v) => typeof v === 'string' && ISO_DATE.test(v as string))) return { kind: 'date' };
  if (vals.every((v) => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean')) {
    const d = distinct(col, rows, valueOf);
    const key = `${col.key} ${typeof col.header === 'string' ? col.header : ''}`;
    if (d.length >= 2 && d.length <= 12 && (ENUMISH.test(key) || (rows.length >= 8 && d.length <= 6 && d.every((x) => x.length <= 24)))) return { kind: 'select', options: d };
    return { kind: 'text' };
  }
  return null;
}
function distinct<T>(col: Column<T>, rows: T[], valueOf: (c: Column<T>, r: T) => unknown): string[] {
  return [...new Set(rows.map((r) => valueOf(col, r)).filter((v) => v !== null && v !== undefined && v !== '').map(String))].sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
}
const isActive = (f?: FilterState) => !!f && !!(f.text?.trim() || f.select || f.preset || f.from || f.to || (f.op && (f.a ?? '') !== ''));
function matches(spec: Spec, f: FilterState, v: unknown): boolean {
  switch (spec.kind) {
    case 'text': return !f.text?.trim() || String(v ?? '').toLowerCase().includes(f.text.trim().toLowerCase());
    case 'select': return !f.select || String(v ?? '') === f.select;
    case 'date': {
      let from = f.from || null, to = f.to || null;
      if (f.preset && f.preset !== 'custom') [from, to] = DATE_PRESETS.find((p) => p.id === f.preset)?.range() ?? [null, null];
      if (!from && !to) return true;
      if (typeof v !== 'string' || !ISO_DATE.test(v)) return false;
      const d = v.slice(0, 10);
      return (!from || d >= from) && (!to || d <= to);
    }
    case 'number': {
      if (!f.op || (f.a ?? '') === '') return true;
      if (typeof v !== 'number') return false;
      const a = Number(f.a), b = Number(f.b);
      switch (f.op) {
        case 'eq': return v === a; case 'ne': return v !== a; case 'gt': return v > a; case 'ge': return v >= a;
        case 'lt': return v < a; case 'le': return v <= a;
        case 'between': return (f.b ?? '') === '' ? v >= a : v >= Math.min(a, b) && v <= Math.max(a, b);
      }
      return true;
    }
  }
}

/** The query-string form of the table's page, sort, search and filters, as the list endpoints expect it. */
function toParams(page: number, size: number, sort: { key: string; dir: 'asc' | 'desc' } | null, q: string, filters: Record<string, FilterState>, specs: Record<string, Spec>): Record<string, string> {
  const p: Record<string, string> = { page: String(page), size: String(size) };
  if (sort) { p.sort = sort.key; p.dir = sort.dir; }
  if (q.trim()) p.q = q.trim();
  for (const [key, spec] of Object.entries(specs)) {
    const f = filters[key];
    if (!isActive(f)) continue;
    if (spec.kind === 'text' && f.text?.trim()) p[`f.${key}`] = f.text.trim();
    else if (spec.kind === 'select' && f.select) p[`f.${key}`] = f.select;
    else if (spec.kind === 'date') {
      let from = f.from || null, to = f.to || null;
      if (f.preset && f.preset !== 'custom') [from, to] = DATE_PRESETS.find((x) => x.id === f.preset)?.range() ?? [null, null];
      if (from) p[`from.${key}`] = from;
      if (to) p[`to.${key}`] = to;
    } else if (spec.kind === 'number' && f.op && (f.a ?? '') !== '') {
      p[`num.${key}`] = f.op === 'between' ? ((f.b ?? '') !== '' ? `between:${f.a}:${f.b}` : `ge:${f.a}`) : `${f.op}:${f.a}`;
    }
  }
  return p;
}

/** Page numbers to show: the first, the last and a window around the current page, with gaps marked as null. */
function pageWindow(current: number, count: number): (number | null)[] {
  const keep = new Set([1, count, current - 1, current, current + 1].filter((n) => n >= 1 && n <= count));
  const out: (number | null)[] = [];
  [...keep].sort((a, b) => a - b).forEach((n, i, all) => { if (i > 0 && n - all[i - 1] > 1) out.push(null); out.push(n); });
  return out;
}

function PageFooter({ total, page, size, sizes, onPage, onSize }: { total: number; page: number; size: number; sizes: number[]; onPage: (p: number) => void; onSize: (s: number) => void }) {
  const count = Math.max(1, Math.ceil(total / size));
  const from = total === 0 ? 0 : (page - 1) * size + 1;
  const to = Math.min(total, page * size);
  const btn = 'inline-flex h-8 min-w-8 items-center justify-center rounded px-2 text-[13px] font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-40';
  return (
    <div data-tour="table-paging" className="flex flex-wrap items-center justify-between gap-3 border-t border-limestone-200 px-3 py-2.5 text-[13px] text-graphite-600">
      <span aria-live="polite">{total === 0 ? 'No rows' : `Showing ${from.toLocaleString()}–${to.toLocaleString()} of ${total.toLocaleString()}`}</span>
      <div className="flex flex-wrap items-center gap-4">
        <label className="flex items-center gap-2">Rows per page
          <select value={size} onChange={(e) => onSize(Number(e.target.value))} aria-label="Rows per page"
            className="h-8 rounded border border-limestone-300 bg-white px-2 text-[13px] font-semibold text-graphite-800 focus:border-federal-700 focus:outline-none focus:ring-2 focus:ring-federal-700/15">
            {sizes.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <nav aria-label="Pagination" className="flex items-center gap-1">
          <button type="button" className={clsx(btn, 'text-graphite-700 hover:bg-limestone-100')} disabled={page <= 1} onClick={() => onPage(1)} aria-label="First page"><ChevronsLeft className="h-4 w-4" /></button>
          <button type="button" className={clsx(btn, 'text-graphite-700 hover:bg-limestone-100')} disabled={page <= 1} onClick={() => onPage(page - 1)} aria-label="Previous page"><ChevronLeft className="h-4 w-4" /></button>
          {pageWindow(page, count).map((n, i) => n === null
            ? <span key={`gap${i}`} className="px-1 text-graphite-400" aria-hidden>…</span>
            : <button key={n} type="button" onClick={() => onPage(n)} aria-label={`Page ${n}`} aria-current={n === page ? 'page' : undefined}
                className={clsx(btn, n === page ? 'bg-federal-800 text-white shadow-sm' : 'text-graphite-700 hover:bg-limestone-100')}>{n}</button>)}
          <button type="button" className={clsx(btn, 'text-graphite-700 hover:bg-limestone-100')} disabled={page >= count} onClick={() => onPage(page + 1)} aria-label="Next page"><ChevronRight className="h-4 w-4" /></button>
          <button type="button" className={clsx(btn, 'text-graphite-700 hover:bg-limestone-100')} disabled={page >= count} onClick={() => onPage(count)} aria-label="Last page"><ChevronsRight className="h-4 w-4" /></button>
        </nav>
      </div>
    </div>
  );
}

const fieldCls = 'h-10 md:h-7 w-full min-w-0 rounded border border-limestone-300 bg-white px-1.5 text-xs text-graphite-800 focus:border-federal-700 focus:outline-none focus:ring-2 focus:ring-federal-700/15';

function FilterCell({ spec, state, onChange, label }: { spec: Spec; state: FilterState; onChange: (s: FilterState) => void; label: string }) {
  const set = (p: Partial<FilterState>) => onChange({ ...state, ...p });
  if (spec.kind === 'text') return <input className={fieldCls} placeholder="Contains…" aria-label={`Filter ${label} contains`} value={state.text ?? ''} onChange={(e) => set({ text: e.target.value })} />;
  if (spec.kind === 'select') return (
    <Select className={fieldCls} aria-label={`Filter ${label}`} value={state.select ?? ''} onChange={(e) => set({ select: e.target.value })}>
      <option value="">All</option>{spec.options!.map((o) => <option key={o} value={o}>{titleCase(o)}</option>)}
    </Select>
  );
  if (spec.kind === 'date') return (
    <div className="space-y-1">
      <Select className={fieldCls} aria-label={`Filter ${label} date`} value={state.preset ?? ''} onChange={(e) => onChange({ preset: e.target.value })}>
        <option value="">Any date</option>{DATE_PRESETS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}<option value="custom">Custom range…</option>
      </Select>
      {state.preset === 'custom' && (
        <div className="flex gap-1">
          <input type="date" className={fieldCls} aria-label={`${label} from`} value={state.from ?? ''} onChange={(e) => set({ from: e.target.value })} />
          <input type="date" className={fieldCls} aria-label={`${label} to`} value={state.to ?? ''} onChange={(e) => set({ to: e.target.value })} />
        </div>
      )}
    </div>
  );
  return (
    <div className="space-y-1">
      <Select className={fieldCls} aria-label={`Filter ${label} number`} value={state.op ?? ''} onChange={(e) => set({ op: e.target.value })}>
        <option value="">Any</option>{NUM_OPS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </Select>
      {state.op && (
        <div className="flex gap-1">
          <input type="number" className={fieldCls} placeholder={state.op === 'between' ? 'From' : 'Value'} aria-label={`${label} value`} value={state.a ?? ''} onChange={(e) => set({ a: e.target.value })} />
          {state.op === 'between' && <input type="number" className={fieldCls} placeholder="To" aria-label={`${label} upper value`} value={state.b ?? ''} onChange={(e) => set({ b: e.target.value })} />}
        </div>
      )}
    </div>
  );
}

/** Enterprise data grid: search, sort, bulk selection, CSV export, keyboard-accessible rows. */
export function DataTable<T>({ rows, columns, rowKey, loading, onRowClick, searchable = true, searchPlaceholder = 'Search', toolbar, empty,
  selectable, bulkActions, exportName, dense, rowClassName, initialSort, server, pageSizes = PAGE_SIZES }: DataTableProps<T>) {
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(initialSort ?? null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [filters, setFilters] = useState<Record<string, FilterState>>({});
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(pageSizes[0] ?? 20);
  // Filters start open on desktop; on a phone they sit behind the Filters button so the list is visible first.
  const [showFilters, setShowFilters] = useState(() => (typeof window === 'undefined' || !window.matchMedia ? true : window.matchMedia('(min-width: 768px)').matches));
  const cell = (c: Column<T>, row: T) => (c.render ? c.render(row) : String(valueOf(c, row) ?? '—'));

  const valueOf = (col: Column<T>, row: T) => (col.value ? col.value(row) : (row as any)[col.key]);

  // When the server pages the list, filters are worked out from the first page and then kept, so they don't change under you
  // as you move between pages. Pick-lists become "contains" boxes because the other pages' values aren't known here.
  const frozen = useRef<Record<string, Spec>>({});
  const specs = useMemo(() => {
    const m: Record<string, Spec> = {};
    for (const c of columns) {
      if (server) {
        if (!frozen.current[c.key]) { const sp = inferSpec(c, rows ?? [], valueOf); if (sp) frozen.current[c.key] = sp.kind === 'select' ? { kind: 'text' } : sp; }
        if (frozen.current[c.key]) m[c.key] = frozen.current[c.key];
      } else { const sp = inferSpec(c, rows ?? [], valueOf); if (sp) m[c.key] = sp; }
    }
    return m;
  }, [rows, columns, server]);
  const activeCount = Object.keys(specs).filter((k) => isActive(filters[k])).length;

  const filtered = useMemo(() => {
    let r = rows ?? [];
    if (server) return r; // the server has already searched, filtered and sorted
    for (const c of columns) {
      const sp = specs[c.key], f = filters[c.key];
      if (sp && isActive(f)) r = r.filter((row) => matches(sp, f!, valueOf(c, row)));
    }
    if (q.trim()) {
      const needle = q.toLowerCase();
      r = r.filter((row) => columns.some((c) => String(valueOf(c, row) ?? '').toLowerCase().includes(needle)));
    }
    if (sort) {
      const col = columns.find((c) => c.key === sort.key);
      if (col) {
        r = [...r].sort((a, b) => {
          const va = valueOf(col, a), vb = valueOf(col, b);
          const cmp = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va ?? '').localeCompare(String(vb ?? ''), 'en', { numeric: true });
          return sort.dir === 'asc' ? cmp : -cmp;
        });
      }
    }
    return r;
  }, [rows, q, sort, columns, filters, specs, server]);

  // ---- Paging ----
  const total = server ? server.total : filtered.length;
  const pageCount = Math.max(1, Math.ceil(total / size));
  const current = Math.min(page, pageCount);
  const pageRows = server ? filtered : filtered.slice((current - 1) * size, current * size);
  // Searching, filtering, sorting or changing the page size starts again from the first page.
  const firstRender = useRef(true);
  const filtersKey = JSON.stringify(filters);
  useEffect(() => { if (firstRender.current) { firstRender.current = false; return; } setPage(1); }, [q, filtersKey, sort?.key, sort?.dir, size]);
  // Server mode: ask for the page, once typing has paused.
  const [debounced, setDebounced] = useState({ q, filtersKey });
  useEffect(() => { const t = setTimeout(() => setDebounced({ q, filtersKey }), 300); return () => clearTimeout(t); }, [q, filtersKey]);
  useEffect(() => {
    if (!server) return;
    server.onQuery(toParams(current, size, sort, debounced.q, JSON.parse(debounced.filtersKey) as Record<string, FilterState>, specs));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, size, sort?.key, sort?.dir, debounced, specs]);

  const selectedRows = filtered.filter((r) => selected.has(rowKey(r)));
  const allChecked = pageRows.length > 0 && pageRows.every((r) => selected.has(rowKey(r)));

  const exportCsv = async () => {
    const all = server ? await server.exportAll() : filtered;
    const header = columns.map((c) => (typeof c.header === 'string' ? c.header : c.key));
    const lines = all.map((row) => columns.map((c) => `"${String(valueOf(c, row) ?? '').replace(/"/g, '""')}"`).join(','));
    const blob = new Blob([[header.join(','), ...lines].join('\n')], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${exportName ?? 'export'}-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
  };

  return (
    <div className="rounded-md border border-limestone-200 bg-white shadow-panel" data-tour="table">
      {(searchable || toolbar || exportName) && (
        <div className="flex flex-wrap items-center gap-2 border-b border-limestone-200 px-3 py-2.5">
          {searchable && (
            <div className="relative min-w-[200px] flex-1 sm:max-w-xs" data-tour="table-search">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-graphite-400" />
              <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={searchPlaceholder} aria-label={searchPlaceholder}
                className="h-8 w-full rounded border border-limestone-300 bg-limestone-50 pl-8 pr-3 text-sm focus:border-federal-700 focus:bg-white focus:outline-none focus:ring-2 focus:ring-federal-700/15" />
            </div>
          )}
          <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
            {toolbar}
            {Object.keys(specs).length > 0 && (
              <button data-tour="table-filters" onClick={() => setShowFilters(!showFilters)} aria-pressed={showFilters}
                className={clsx('inline-flex h-8 items-center gap-1.5 rounded px-2.5 text-[13px] font-semibold hover:bg-limestone-100', activeCount ? 'text-federal-800' : 'text-graphite-600')}>
                <SlidersHorizontal className="h-3.5 w-3.5" /> Filters{activeCount > 0 && <span className="rounded-full bg-federal-800 px-1.5 text-[11px] text-white">{activeCount}</span>}
              </button>
            )}
            {activeCount > 0 && (
              <button onClick={() => setFilters({})} className="inline-flex h-8 items-center gap-1 rounded px-2 text-[13px] font-semibold text-graphite-600 hover:bg-limestone-100"><X className="h-3.5 w-3.5" /> Clear</button>
            )}
            {exportName && (
              <button data-tour="table-export" onClick={exportCsv} className="inline-flex h-8 items-center gap-1.5 rounded px-2.5 text-[13px] font-semibold text-graphite-600 hover:bg-limestone-100 hover:text-graphite-900">
                <Download className="h-3.5 w-3.5" /> Export CSV
              </button>
            )}
          </div>
        </div>
      )}
      {showFilters && (Object.keys(specs).length > 0 || columns.some((c) => c.sortable !== false && c.header !== '')) && (
        <div className="space-y-3 border-b border-limestone-200 bg-limestone-50/60 px-3 py-3 md:hidden">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold text-graphite-600">Sort by</span>
            <Select className={fieldCls} value={sort ? `${sort.key}:${sort.dir}` : ''}
              onChange={(e) => { const [k, d] = e.target.value.split(':'); setSort(k ? { key: k, dir: d as 'asc' | 'desc' } : null); }}>
              <option value="">Default order</option>
              {columns.filter((c) => c.sortable !== false && typeof c.header === 'string' && c.header !== '').flatMap((c) => [
                <option key={c.key + 'a'} value={`${c.key}:asc`}>{c.header as string} (low to high)</option>,
                <option key={c.key + 'd'} value={`${c.key}:desc`}>{c.header as string} (high to low)</option>,
              ])}
            </Select>
          </label>
          <div className="grid grid-cols-2 gap-3">
            {columns.filter((c) => specs[c.key]).map((c) => (
              <label key={c.key} className={clsx('block', specs[c.key].kind === 'text' && 'col-span-2')}>
                <span className="mb-1 block text-xs font-semibold text-graphite-600">{typeof c.header === 'string' ? c.header : c.key}</span>
                <FilterCell spec={specs[c.key]} state={filters[c.key] ?? {}} label={typeof c.header === 'string' ? c.header : c.key}
                  onChange={(st) => setFilters({ ...filters, [c.key]: st })} />
              </label>
            ))}
          </div>
        </div>
      )}
      {selectable && selectedRows.length > 0 && bulkActions && (
        <div className="flex items-center gap-3 border-b border-federal-100 bg-federal-50 px-4 py-2 text-sm">
          <span className="font-semibold text-federal-900">{selectedRows.length} selected</span>
          {bulkActions(selectedRows, () => setSelected(new Set()))}
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="hidden w-full border-collapse text-sm md:table">
          <thead>
            <tr className="border-b border-limestone-200 bg-limestone-50/80 text-left">
              {selectable && (
                <th className="w-10 px-3">
                  <input type="checkbox" aria-label="Select all" checked={allChecked}
                    onChange={() => setSelected(allChecked ? new Set() : new Set(pageRows.map(rowKey)))} className="h-4 w-4 accent-federal-900" />
                </th>
              )}
              {columns.map((c) => {
                const active = sort?.key === c.key;
                const canSort = c.sortable !== false && c.header !== '';
                return (
                  <th key={c.key} style={{ width: c.width }}
                    className={clsx('whitespace-nowrap px-3 py-2 text-xs font-semibold text-graphite-500', c.align === 'right' && 'text-right', c.align === 'center' && 'text-center', c.hideOnMobile && 'hidden md:table-cell')}
                    aria-sort={active ? (sort!.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                    {canSort ? (
                      <button className={clsx('inline-flex items-center gap-1 hover:text-graphite-900', active && 'text-graphite-900')}
                        onClick={() => setSort(active && sort!.dir === 'desc' ? null : { key: c.key, dir: active ? 'desc' : 'asc' })}>
                        {c.header}
                        {active ? (sort!.dir === 'asc' ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />) : <ArrowUpDown className="h-3 w-3 opacity-40" />}
                      </button>
                    ) : c.header}
                  </th>
                );
              })}
            </tr>
            {showFilters && Object.keys(specs).length > 0 && (
              <tr className="border-b border-limestone-200 bg-white align-top">
                {selectable && <th className="w-10" />}
                {columns.map((c) => (
                  <th key={c.key} className={clsx('px-3 py-1.5 text-left font-normal', c.hideOnMobile && 'hidden md:table-cell')}>
                    {specs[c.key] && <FilterCell spec={specs[c.key]} state={filters[c.key] ?? {}} label={typeof c.header === 'string' ? c.header : c.key}
                      onChange={(st) => setFilters({ ...filters, [c.key]: st })} />}
                  </th>
                ))}
              </tr>
            )}
          </thead>
          <tbody>
            {loading && Array.from({ length: 5 }).map((_, i) => (
              <tr key={i} className="border-b border-limestone-100">
                {selectable && <td className="px-3 py-3" />}
                {columns.map((c) => <td key={c.key} className="px-3 py-3"><Skeleton className="h-4 w-3/4" /></td>)}
              </tr>
            ))}
            {!loading && pageRows.map((row, ri) => {
              const k = rowKey(row);
              return (
                <tr key={k} style={{ animationDelay: `${Math.min(ri, 14) * 16}ms` }}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(row); } : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  className={clsx('border-b border-limestone-100 last:border-0 motion-safe:animate-row-in', onRowClick && 'cursor-pointer hover:bg-federal-50/40 focus:bg-federal-50/60 focus:outline-none', selected.has(k) && 'bg-federal-50/60', rowClassName?.(row))}>
                  {selectable && (
                    <td className="px-3" onClick={(e) => e.stopPropagation()}>
                      <input type="checkbox" aria-label="Select row" checked={selected.has(k)} className="h-4 w-4 accent-federal-900"
                        onChange={() => { const s = new Set(selected); s.has(k) ? s.delete(k) : s.add(k); setSelected(s); }} />
                    </td>
                  )}
                  {columns.map((c) => (
                    <td key={c.key} className={clsx('px-3 text-graphite-800', dense ? 'py-2' : 'py-3', c.align === 'right' && 'text-right tabular-nums', c.align === 'center' && 'text-center', c.hideOnMobile && 'hidden md:table-cell')}>
                      {c.render ? c.render(row) : String(valueOf(c, row) ?? '—')}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
        {/* Phones and small tablets: one card per row instead of a wide table. */}
        <div className="md:hidden">
          {loading && Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="space-y-2 border-b border-limestone-100 p-4"><Skeleton className="h-4 w-1/2" /><Skeleton className="h-4 w-3/4" /></div>
          ))}
          {!loading && pageRows.map((row, ri) => {
            const k = rowKey(row);
            const [first, ...rest] = columns;
            return (
              <div key={k} style={{ animationDelay: `${Math.min(ri, 10) * 20}ms` }}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(row); } : undefined}
                role={onRowClick ? 'button' : undefined} tabIndex={onRowClick ? 0 : undefined}
                className={clsx('border-b border-limestone-100 p-4 last:border-0 motion-safe:animate-row-in', onRowClick && 'cursor-pointer active:bg-federal-50/60', selected.has(k) && 'bg-federal-50/60', rowClassName?.(row))}>
                <div className="flex items-start gap-3">
                  {selectable && (
                    <input type="checkbox" aria-label="Select row" checked={selected.has(k)} className="mt-1 h-5 w-5 shrink-0 accent-federal-900" onClick={(e) => e.stopPropagation()}
                      onChange={() => { const s = new Set(selected); s.has(k) ? s.delete(k) : s.add(k); setSelected(s); }} />
                  )}
                  <div className="min-w-0 flex-1 break-words text-[15px] font-semibold text-graphite-900">{first && cell(first, row)}</div>
                </div>
                <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                  {rest.filter((c) => !c.hideOnMobile).map((c) => {
                    const labelled = typeof c.header === 'string' && c.header !== '';
                    return (
                      <div key={c.key} className={clsx('min-w-0 break-words', !labelled && 'col-span-2')}>
                        {labelled && <dt className="text-xs text-graphite-500">{c.header}</dt>}
                        <dd className="text-graphite-800">{cell(c, row)}</dd>
                      </div>
                    );
                  })}
                </dl>
              </div>
            );
          })}
        </div>
        {!loading && total === 0 && (
          <EmptyState title={q ? `Nothing matches “${q}”` : activeCount ? 'No rows match these filters' : empty?.title ?? 'Nothing here yet'} body={q ? 'Try a different search.' : activeCount ? 'Adjust or clear the column filters.' : empty?.body} action={q || activeCount ? undefined : empty?.action} />
        )}
      </div>
      {!loading && total > 0 && <PageFooter total={total} page={current} size={size} sizes={pageSizes} onPage={setPage} onSize={setSize} />}
    </div>
  );
}
