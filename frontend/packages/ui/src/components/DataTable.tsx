import { type ReactNode, useMemo, useState } from 'react';
import clsx from 'clsx';
import { Search, ArrowUpDown, ArrowUp, ArrowDown, Download } from 'lucide-react';
import { Skeleton } from './primitives';
import { EmptyState } from './layout';

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
}

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
}

/** Enterprise data grid: search, sort, bulk selection, CSV export, keyboard-accessible rows. */
export function DataTable<T>({ rows, columns, rowKey, loading, onRowClick, searchable = true, searchPlaceholder = 'Search', toolbar, empty,
  selectable, bulkActions, exportName, dense, rowClassName, initialSort }: DataTableProps<T>) {
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(initialSort ?? null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const valueOf = (col: Column<T>, row: T) => (col.value ? col.value(row) : (row as any)[col.key]);

  const filtered = useMemo(() => {
    let r = rows ?? [];
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
  }, [rows, q, sort, columns]);

  const selectedRows = filtered.filter((r) => selected.has(rowKey(r)));
  const allChecked = filtered.length > 0 && selectedRows.length === filtered.length;

  const exportCsv = () => {
    const header = columns.map((c) => (typeof c.header === 'string' ? c.header : c.key));
    const lines = filtered.map((row) => columns.map((c) => `"${String(valueOf(c, row) ?? '').replace(/"/g, '""')}"`).join(','));
    const blob = new Blob([[header.join(','), ...lines].join('\n')], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${exportName ?? 'export'}-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
  };

  return (
    <div className="rounded-md border border-limestone-200 bg-white shadow-panel">
      {(searchable || toolbar || exportName) && (
        <div className="flex flex-wrap items-center gap-2 border-b border-limestone-200 px-3 py-2.5">
          {searchable && (
            <div className="relative min-w-[200px] flex-1 sm:max-w-xs">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-graphite-400" />
              <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={searchPlaceholder} aria-label={searchPlaceholder}
                className="h-8 w-full rounded border border-limestone-300 bg-limestone-50 pl-8 pr-3 text-sm focus:border-federal-700 focus:bg-white focus:outline-none focus:ring-2 focus:ring-federal-700/15" />
            </div>
          )}
          <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
            {toolbar}
            {exportName && (
              <button onClick={exportCsv} className="inline-flex h-8 items-center gap-1.5 rounded px-2.5 text-[13px] font-semibold text-graphite-600 hover:bg-limestone-100 hover:text-graphite-900">
                <Download className="h-3.5 w-3.5" /> Export CSV
              </button>
            )}
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
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-b border-limestone-200 bg-limestone-50/80 text-left">
              {selectable && (
                <th className="w-10 px-3">
                  <input type="checkbox" aria-label="Select all" checked={allChecked}
                    onChange={() => setSelected(allChecked ? new Set() : new Set(filtered.map(rowKey)))} className="h-4 w-4 accent-federal-900" />
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
          </thead>
          <tbody>
            {loading && Array.from({ length: 5 }).map((_, i) => (
              <tr key={i} className="border-b border-limestone-100">
                {selectable && <td className="px-3 py-3" />}
                {columns.map((c) => <td key={c.key} className="px-3 py-3"><Skeleton className="h-4 w-3/4" /></td>)}
              </tr>
            ))}
            {!loading && filtered.map((row) => {
              const k = rowKey(row);
              return (
                <tr key={k}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(row); } : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  className={clsx('border-b border-limestone-100 last:border-0', onRowClick && 'cursor-pointer hover:bg-federal-50/40 focus:bg-federal-50/60 focus:outline-none', selected.has(k) && 'bg-federal-50/60', rowClassName?.(row))}>
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
        {!loading && filtered.length === 0 && (
          <EmptyState title={q ? `Nothing matches “${q}”` : empty?.title ?? 'Nothing here yet'} body={q ? 'Try a different search.' : empty?.body} action={q ? undefined : empty?.action} />
        )}
      </div>
    </div>
  );
}
