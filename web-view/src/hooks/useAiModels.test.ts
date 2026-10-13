import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { listAiModels, listAiProviders } from '@/api/client';
import type { AiModelOption, AiProviderOption } from '@/api/types';
import { useAiModels } from '@/hooks/useAiModels';
import { clearQueryCache } from '@/hooks/useQuery';

vi.mock('@/api/client', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/api/client')>();
  return {
    ...mod,
    listAiProviders: vi.fn(),
    listAiModels: vi.fn(),
  };
});

const PROVIDERS: AiProviderOption[] = [
  { id: 'nvidia', label: 'NVIDIA NIM', enabled: true },
  { id: 'groq', label: 'Groq', enabled: true },
];

const MODELS: Record<string, AiModelOption[]> = {
  nvidia: [{ id: 'nvidia-model', label: 'NVIDIA Model' }],
  groq: [{ id: 'groq-model', label: 'Groq Model' }],
};

describe('useAiModels provider switching', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    clearQueryCache();
    vi.mocked(listAiProviders).mockResolvedValue(PROVIDERS);
    vi.mocked(listAiModels).mockImplementation(async (provider) => MODELS[provider ?? 'nvidia'] ?? []);
  });

  it('resolves the stored provider against the enabled set and auto-selects its model', async () => {
    const { result } = renderHook(() => useAiModels());

    await waitFor(() => expect(result.current.provider).toBe('nvidia'));
    await waitFor(() => expect(result.current.model).toBe('nvidia-model'));
    expect(result.current.isReady).toBe(true);
    expect(vi.mocked(listAiModels).mock.calls.at(-1)?.[0]).toBe('nvidia');
  });

  it('never keeps a stale model from the previous provider after a switch', async () => {
    const { result } = renderHook(() => useAiModels());
    await waitFor(() => expect(result.current.model).toBe('nvidia-model'));

    act(() => result.current.setProvider('groq'));

    // While the new provider's catalog is in flight, the controls are never "ready" —
    // there is no window where the old catalog backs the new provider.
    expect(result.current.isReady).toBe(false);

    // Once groq's catalog lands, only a groq model can be selected.
    await waitFor(() => expect(result.current.model).toBe('groq-model'));
    expect(result.current.provider).toBe('groq');
    expect(result.current.isReady).toBe(true);
    expect(vi.mocked(listAiModels).mock.calls.at(-1)?.[0]).toBe('groq');
    expect(result.current.models.some((m) => m.id === 'nvidia-model')).toBe(false);
  });

  it('rejects a stored provider the server no longer serves', async () => {
    localStorage.setItem('ai-provider', 'retired-provider');

    const { result } = renderHook(() => useAiModels());

    await waitFor(() => expect(result.current.provider).toBe('nvidia'));
    expect(localStorage.getItem('ai-provider')).toBe('nvidia');
  });
});
