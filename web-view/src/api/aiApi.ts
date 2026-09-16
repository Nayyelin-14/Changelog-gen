import { apiClient } from './client';
import { DEFAULT_AI_PROVIDER } from '@/lib/aiProvider';
import type { AiModelOption, AiProviderOption } from './types';

export async function listAiProviders(): Promise<AiProviderOption[]> {
  const { data } = await apiClient.get<AiProviderOption[]>('/ai/providers');
  return data;
}

export async function listAiModels(provider?: string): Promise<AiModelOption[]> {
  const { data } = await apiClient.get<AiModelOption[]>('/ai/models', {
    params: { provider: provider || DEFAULT_AI_PROVIDER },
  });
  return data;
}