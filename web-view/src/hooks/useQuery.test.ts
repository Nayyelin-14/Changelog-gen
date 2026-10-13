import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { clearQueryCache, useQuery, type QueryResult } from '@/hooks/useQuery';
import { setStoredProvider } from '@/lib/provider';

/** QueryResult is a discriminated union — narrow to the success variant for assertions. */
function successData<T>(result: { current: QueryResult<T> }): T {
  const cur = result.current;
  if (cur.status !== 'success') throw new Error(`expected success, got ${cur.status}`);
  return cur.data;
}

describe('useQuery cache', () => {
  beforeEach(() => {
    localStorage.clear();
    setStoredProvider('azure');
    clearQueryCache();
  });

  it('serves fresh results from cache within the TTL', async () => {
    const load = vi.fn(async () => 'value');
    const { result, rerender } = renderHook(() => useQuery(load, [], { cacheKey: 'k', ttlMs: 60_000 }));

    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(successData(result)).toBe('value');
    rerender();
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('a remount within the TTL serves the cached result without refetching', async () => {
    const load = vi.fn(async () => 'value');
    const first = renderHook(() => useQuery(load, [], { cacheKey: 'k', ttlMs: 60_000 }));
    await waitFor(() => expect(first.result.current.status).toBe('success'));
    first.unmount();

    const second = renderHook(() => useQuery(load, [], { cacheKey: 'k', ttlMs: 60_000 }));
    await waitFor(() => expect(second.result.current.status).toBe('success'));
    expect(successData(second.result)).toBe('value');
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('clearQueryCache forces the next mount to refetch instead of serving the cleared entry', async () => {
    const load = vi.fn(async () => 'value');
    const first = renderHook(() => useQuery(load, [], { cacheKey: 'k', ttlMs: 60_000 }));
    await waitFor(() => expect(first.result.current.status).toBe('success'));
    first.unmount();

    act(() => clearQueryCache());

    const second = renderHook(() => useQuery(load, [], { cacheKey: 'k', ttlMs: 60_000 }));
    await waitFor(() => expect(second.result.current.status).toBe('success'));
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('scopes cached results per page provider — a switch never serves the other side', async () => {
    const load = vi.fn(async () => `data-for-${localStorage.getItem('changelog-provider') ?? 'azure'}`);
    const { result, rerender } = renderHook(() => useQuery(load, [], { cacheKey: 'k', ttlMs: 60_000 }));
    await waitFor(() => expect(successData(result)).toBe('data-for-azure'));
    expect(load).toHaveBeenCalledTimes(1);

    // Switch page provider without any change to the caller's own deps.
    act(() => setStoredProvider('github'));
    rerender();

    await waitFor(() => expect(successData(result)).toBe('data-for-github'));
    expect(load).toHaveBeenCalledTimes(2);

    // Switch back — the azure entry is still fresh in its own scoped slot.
    act(() => setStoredProvider('azure'));
    rerender();
    await waitFor(() => expect(successData(result)).toBe('data-for-azure'));
    expect(load).toHaveBeenCalledTimes(2);
  });
});
