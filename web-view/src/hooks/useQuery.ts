import { useEffect, useRef, useState } from 'react';

export type QueryState<T> =
  | { status: 'loading'; refresh: () => void; refreshing: boolean }
  | { status: 'error'; error: Error; refresh: () => void; refreshing: boolean }
  | { status: 'success'; data: T; refresh: () => void; refreshing: boolean };

export type QueryResult<T> = QueryState<T>;

export interface UseQueryOptions {
  cacheKey?: string;
  ttlMs?: number;
}

const MAX_RETRIES = 2;
const DEFAULT_TTL_MS = 5 * 60_000;

const cache = new Map<string, { data: unknown; expiresAt: number }>();

const inFlight = new Map<string, Promise<unknown>>();

function cacheGet<T>(key: string): T | undefined {
  const entry = cache.get(key);
  if (!entry || entry.expiresAt <= Date.now()) return undefined;
  return entry.data as T;
}

function cacheSet<T>(key: string, data: T, ttlMs: number): void {
  const now = Date.now();
  for (const [k, v] of cache) {
    if (v.expiresAt <= now) cache.delete(k);
  }
  cache.set(key, { data, expiresAt: now + ttlMs });
}

export function useQuery<T>(
  load: () => Promise<T>,
  deps: ReadonlyArray<unknown>,
  options?: UseQueryOptions,
): QueryResult<T> {
  const cacheKey = options?.cacheKey;
  const ttlMs = options?.ttlMs ?? DEFAULT_TTL_MS;
  const fullKey = cacheKey ? `${cacheKey}:${JSON.stringify(deps)}` : undefined;
  const [refreshTick, setRefreshTick] = useState(0);
  const [refreshing, setRefreshing] = useState(false);

  const refresh = useRef(() => {
    if (fullKey) cache.delete(fullKey);
    setRefreshing(true);
    setRefreshTick((t) => t + 1);
  }).current;

  const [state, setState] = useState<QueryState<T>>(() => {
    const cached = fullKey ? cacheGet<T>(fullKey) : undefined;
    return cached !== undefined
      ? { status: 'success', data: cached, refresh, refreshing: false }
      : { status: 'loading', refresh, refreshing: false };
  });
  const hasDataRef = useRef(state.status === 'success');
  const prevFullKeyRef = useRef<string | undefined>(undefined);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    let cancelled = false;
    let attempt = 0;

    const cached = fullKey ? cacheGet<T>(fullKey) : undefined;
    if (cached !== undefined) {
      hasDataRef.current = true;
      setState({ status: 'success', data: cached, refresh, refreshing: false });
      setRefreshing(false);
      return;
    }

    const keyChanged = fullKey && prevFullKeyRef.current && fullKey !== prevFullKeyRef.current;
    if (!hasDataRef.current || keyChanged) {
      setState({ status: 'loading', refresh, refreshing: true });
    }
    prevFullKeyRef.current = fullKey;

    function run() {
      const shared = fullKey ? inFlight.get(fullKey) : undefined;
      const promise = shared ?? load();
      if (fullKey && !shared) {
        inFlight.set(fullKey, promise);
        promise.finally(() => {
          if (inFlight.get(fullKey) === promise) inFlight.delete(fullKey);
        });
      }

      (promise as Promise<T>)
        .then((data) => {
          if (cancelled) return;
          hasDataRef.current = true;
          if (fullKey) cacheSet(fullKey, data, ttlMs);
          setState({ status: 'success', data, refresh, refreshing: false });
          setRefreshing(false);
        })
        .catch((error: unknown) => {
          if (cancelled) return;
          if (hasDataRef.current && attempt < MAX_RETRIES) {
            attempt += 1;
            setTimeout(() => {
              if (!cancelled) run();
            }, 400 * attempt);
            return;
          }
          setState({ status: 'error', error: error instanceof Error ? error : new Error(String(error)), refresh, refreshing: false });
          setRefreshing(false);
        });
    }
    run();

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, refreshTick]);

  return { ...state, refresh, refreshing };
}