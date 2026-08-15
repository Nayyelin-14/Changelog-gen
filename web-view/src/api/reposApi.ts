import { apiClient } from './client';
import type { RepositorySummary, RepoOverview, ReleaseData } from './types';

export async function listRepositories(project: string): Promise<RepositorySummary[]> {
  const { data } = await apiClient.get<RepositorySummary[]>(`/projects/${encodeURIComponent(project)}/repos`);
  return data;
}

export async function getReposOverview(project: string): Promise<RepoOverview[]> {
  const { data } = await apiClient.get<RepoOverview[]>(`/projects/${encodeURIComponent(project)}/repos-overview`);
  return data;
}

export async function listRepositoriesWithChangelog(project: string): Promise<RepositorySummary[]> {
  const { data } = await apiClient.get<RepositorySummary[]>(
    `/projects/${encodeURIComponent(project)}/repos-with-changelog`,
  );
  return data;
}

export async function listBranches(project: string, repo: string): Promise<string[]> {
  const { data } = await apiClient.get<string[]>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/branches`,
  );
  return data;
}

export async function fetchRepoChanges(
  project: string,
  repo: string,
  toVersion?: string,
  fromVersion?: string,
  branch?: string,
): Promise<ReleaseData> {
  const params: Record<string, string | undefined> = { fromVersion, branch, toVersion };
  const { data } = await apiClient.get<ReleaseData>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changes`,
    { params },
  );
  return data;
}

export async function hasChangelog(project: string, repo: string, branch?: string): Promise<boolean> {
  const { data } = await apiClient.get<boolean>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/has-changelog`,
    { params: { branch } },
  );
  return data;
}