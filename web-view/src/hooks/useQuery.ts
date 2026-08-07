import { useEffect, useRef, useState } from 'react';

export type QueryState<T> =
  | { status: 'loading' }
  | { status: 'error'; error: Error }
  | { status: 'success'; data: T };

export type QueryResult<T> = QueryState<T> & {
  /** Force a fresh fetch, bypassing any cached result — for an explicit user-triggered "Refresh"
   * action, where serving a stale cached value would silently defeat the point of clicking it. */
  refresh: () => void;
  /** True while a refresh-triggered fetch is in flight — so the Refresh button can show a spinner
   * without losing the current data on screen. Resets to false when the fetch resolves. */
  refreshing: boolean;
};

export interface UseQueryOptions {
  /** Opt-in cross-mount cache key (combined with `deps`) — e.g. a page that unmounts and
   * remounts when the user navigates away and back (browser back button, an in-app "back to
   * dashboard" link) would otherwise refetch from scratch every time. Omit to keep the previous
   * behavior (always fetch fresh on mount). */
  cacheKey?: string;
  /** How long a cached result stays fresh enough to serve without refetching. Ignored without
   * `cacheKey`. Defaults to 5 minutes — a 30s window sounds safe but isn't: anyone who actually
   * reads a changelog entry before clicking back blows past it trivially, so "navigate away and
   * back" would still show a full reload spinner most of the time. Pipeline/version data doesn't
   * change that fast, and the explicit Refresh button covers anyone who wants guaranteed-fresh
   * data right now. */
  ttlMs?: number;
}

const MAX_RETRIES = 2;
const DEFAULT_TTL_MS = 5 * 60_000;

const cache = new Map<string, { data: unknown; expiresAt: number }>();

// Same StrictMode double-invoke problem as client.ts's previewInFlight — without this, every
// cacheKey'd query with two active callers (e.g. StrictMode's dev-only remount simulation, or two
// components asking for the same repo's data at once) fires its (often expensive, real backend)
// load() twice instead of sharing the one in-flight call.
const inFlight = new Map<string, Promise<unknown>>();

function cacheGet<T>(key: string): T | undefined {
  const entry = cache.get(key);
  if (!entry || entry.expiresAt <= Date.now()) return undefined;
  return entry.data as T;
}

// A read only checks expiry on the key it was asked for — an entry nobody ever reads again (e.g.
// a repo/branch/page combo visited once and never revisited) would otherwise sit in `cache`
// forever for the life of the tab. Sweeping on every write is cheap (this map stays in the
// hundreds of entries for realistic usage) and keeps it bounded to "still-live" data only.
function cacheSet<T>(key: string, data: T, ttlMs: number): void {
  const now = Date.now();
  for (const [k, v] of cache) {
    if (v.expiresAt <= now) cache.delete(k);
  }
  cache.set(key, { data, expiresAt: now + ttlMs });
}

/**
 * Runs `load` on deps change. Stale in-flight requests are ignored on resolution.
 * Re-fetches keep the previous success visible (no loading flash). Failures after
 * prior success get 2 quick retries; persistent failure surfaces as error instead
 * of leaving stale data on screen indefinitely.
 *
 * With `options.cacheKey`, a fresh-enough prior result (within `ttlMs`) is served instantly on
 * mount with no network call at all — the case this guards against is navigating away and
 * immediately back, which would otherwise redo every fetch this hook made.
 */
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

  const [state, setState] = useState<QueryState<T>>(() => {
    const cached = fullKey ? cacheGet<T>(fullKey) : undefined;
    return cached !== undefined ? { status: 'success', data: cached } : { status: 'loading' };
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
      setState({ status: 'success', data: cached });
      // No fetch is starting for this effect run — without this, a refresh() whose deps change
      // (switch repo, page through history) before its fetch resolves leaves `refreshing` stuck
      // true forever: the in-flight fetch's own completion becomes a no-op (`cancelled` by the
      // dep change), and this cache-hit path is the only other place that settles the query.
      setRefreshing(false);
      return;
    }

    // Show loading when deps change to a different entity (key changed), but NOT on
    // refresh (same key, cache cleared by refresh()). This distinguishes switching
    // repos/projects from clicking "Refresh" — the former should show a loading
    // indicator so the user knows new data is arriving; the latter should keep the
    // previous data visible while re-fetching (no loading flash).
    const keyChanged = fullKey && prevFullKeyRef.current && fullKey !== prevFullKeyRef.current;
    if (!hasDataRef.current || keyChanged) {
      setState({ status: 'loading' });
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
          setState({ status: 'success', data });
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
          setState({ status: 'error', error: error instanceof Error ? error : new Error(String(error)) });
          setRefreshing(false);
        });
    }
    run();

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, refreshTick]);

  const refresh = useRef(() => {
    if (fullKey) cache.delete(fullKey);
    setRefreshing(true);
    setRefreshTick((t) => t + 1);
  }).current;

  return { ...state, refresh, refreshing };
}