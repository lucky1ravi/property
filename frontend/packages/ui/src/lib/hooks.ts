import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
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
      opts.onSuccess?.(r);
    },
    onError: (e) => toastError(e),
  });
}

export { api };
