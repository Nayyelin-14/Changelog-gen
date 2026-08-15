import { apiClient } from './client';
import type { ChangelogLocation, PullRequestDetails } from './types';

/** Resolves which release (if any) a PR shipped in — backs the PR-number deep link from an
 * external dashboard straight into this app's changelog view. */
export async function getChangelogLocation(
  project: string,
  repo: string,
  prId: string,
): Promise<ChangelogLocation> {
  const { data } = await apiClient.get<ChangelogLocation>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/pull-requests/${encodeURIComponent(prId)}/changelog-location`,
  );
  return data;
}

export async function getPullRequestDetails(
  project: string,
  repo: string,
  prId: string,
): Promise<PullRequestDetails> {
  const { data } = await apiClient.get<PullRequestDetails>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/pull-requests/${encodeURIComponent(prId)}/details`,
  );
  return data;
}