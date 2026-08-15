import { apiClient } from './client';
import type { ProjectSummary } from './types';

export async function listProjects(): Promise<ProjectSummary[]> {
  const { data } = await apiClient.get<ProjectSummary[]>('/projects');
  return data;
}