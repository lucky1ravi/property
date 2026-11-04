import { useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import { api } from './api';
import { toast, toastError } from './toast';

/** GET with caching; key defaults to the path. */
export function useApi<T = any>(path: string | null, opts: Partial<UseQueryOptions<T>> = {}) {
  return useQuery<T>({
    queryKey: [path],
    queryFn: () => api.get<T>(path as string),
    enabled: !!path,
    staleTime: 15_000,
    ...opts,
  } as UseQueryOptions<T>);
}

/** What a {@link DataTable} needs to show a list the server pages. Spread it onto the table as `server={…}`. */
export interface ServerPaging<T = any> {
  total: number;
  /** Called by the table whenever its page, size, sort, search or filters change. */
  onQuery: (params: Record<string, string>) => void;
  /** Fetches every row matching the current search and filters, for CSV export. */
  exportAll: () => Promise<T[]>;
}

/**
 * GET a list one page at a time. The database does the paging, sorting, searching and filtering; pass `server` to the
 * table and it drives this hook. Use it instead of {@link useApi} for any list that can grow. The list endpoint answers
 * `{rows, total}` (or `{items, total}`) when it is asked for a page.
 *
 * @param path the list endpoint, with any fixed filters already in its query string
 * @param opts `initialSort` should match the table's `initialSort` so the first request is not repeated
 */
export function usePagedApi<T = any>(path: string | null, opts: { initialSort?: { key: string; dir: 'asc' | 'desc' }; /** Fetch again this often (milliseconds) while the list is on screen, so new rows appear by themselves. */ refetchInterval?: number } = {}) {
  const base: Record<string, string> = { page: '1', size: '20', ...(opts.initialSort ? { sort: opts.initialSort.key, dir: opts.initialSort.dir } : {}) };
  const [params, setParams] = useState<Record<string, string>>(base);
  const withParams = (p: string, extra: Record<string, string>) => `${p}${p.includes('?') ? '&' : '?'}${new URLSearchParams(extra).toString()}`;
  const query = useApi<any>(path ? withParams(path, params) : null, { placeholderData: keepPreviousData, ...(opts.refetchInterval ? { refetchInterval: opts.refetchInterval } : {}) });
  const data = query.data;
  const rows: T[] | undefined = Array.isArray(data) ? data : data?.rows ?? data?.items;
  const total: number = Array.isArray(data) ? data.length : typeof data?.total === 'number' ? data.total : rows?.length ?? 0;
  const latest = useRef(params);
  latest.current = params;
  const server: ServerPaging<T> = {
    total,
    onQuery: (next) => setParams((cur) => (JSON.stringify(cur) === JSON.stringify(next) ? cur : next)),
    exportAll: async () => {
      const { page: _p, size: _s, ...rest } = latest.current;
      const all = await api.get<any>(withParams(path as string, { ...rest, page: '1', size: '5000', export: '1' }));
      return (Array.isArray(all) ? all : all?.rows ?? all?.items ?? []) as T[];
    },
  };
  return { rows, total, data, server, loading: query.isLoading, isLoading: query.isLoading, isFetching: query.isFetching, refetch: query.refetch, error: query.error };
}

/**
 * Mutation that toasts on success/failure and refreshes related queries.
 * `invalidate` lists path prefixes whose cached GETs should refetch.
 */
export function useAction<TVars = any, TRes = any>(
  fn: (vars: TVars) => Promise<TRes>,
  opts: { success?: string | ((r: TRes) => string); invalidate?: string[]; onSuccess?: (r: TRes) => void; silent?: boolean } = {},
) {
  const qc = useQueryClient();
  return useMutation<TRes, Error, TVars>({
    mutationFn: fn,
    onSuccess: (r) => {
      if (opts.success && !opts.silent) toast(typeof opts.success === 'function' ? opts.success(r) : opts.success);
      (opts.invalidate ?? []).forEach((prefix) =>
        qc.invalidateQueries({ predicate: (q) => String(q.queryKey[0] ?? '').startsWith(prefix) }),
      );
      // Every micro-front-end has its own cache, so a change to who you are or how the company is set up is announced to all of them.
      if ((opts.invalidate ?? []).includes('session') && typeof window !== 'undefined') window.dispatchEvent(new Event('pos:refresh-session'));
      opts.onSuccess?.(r);
    },
    onError: (e) => {
      toastError(e);
      // A refused action can still have left a mark (a declined payment is recorded, a conflict means the screen was stale), so show what is now true.
      (opts.invalidate ?? []).forEach((prefix) =>
        qc.invalidateQueries({ predicate: (q) => String(q.queryKey[0] ?? '').startsWith(prefix) }),
      );
    },
  });
}

export { api };
