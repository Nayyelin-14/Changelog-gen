import { apiClient } from './client';
import type { AiModelOption } from './types';

export async function listAiModels(): Promise<AiModelOption[]> {
  const { data } = await apiClient.get<AiModelOption[]>('/ai/models');
  return data;
}