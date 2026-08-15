import { apiClient } from './client';
import type { HistoryResponse, ChangelogPreview, PreviewAudience, ChangelogMeta, ChangelogAudience } from './types';

// React StrictMode double-invokes effects — keyed in-flight requests share a single call so
// dev-mode doesn't race two requests for the same cache key before either write lands.
const previewInFlight = new Map<string, Promise<ChangelogPreview>>();

export async function listHistory(
  project: string,
  repo: string,
  branch?: string,
  page?: number,
  limit?: number,
): Promise<HistoryResponse> {
  const { data } = await apiClient.get<HistoryResponse>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/history`,
    { params: { branch, page, limit } },
  );
  return data;
}

export function getChangelogPreview(
  project: string,
  repo: string,
  audience: PreviewAudience,
  version?: string,
  branch?: string,
): Promise<ChangelogPreview> {
  const key = `${project} ${repo} ${audience} ${version ?? ''} ${branch ?? ''}`;
  const existing = previewInFlight.get(key);
  if (existing) return existing;

  const request = apiClient
    .get<ChangelogPreview>(`/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-preview`, {
      params: { audience, version, branch },
    })
    .then((res) => res.data)
    .finally(() => previewInFlight.delete(key));

  previewInFlight.set(key, request);
  return request;
}

export async function getChangelogText(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
): Promise<string | null> {
  const { data } = await apiClient.get<{ text: string | null }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-text`,
    { params: { version, audience } },
  );
  return data.text;
}

/** Provenance (AI/edit) of current text for a version+audience. `branch` is developer-only —
 * determines `hasUnpushedChanges` against the actual repo. */
export async function getChangelogMeta(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
  branch?: string,
): Promise<ChangelogMeta> {
  const { data } = await apiClient.get<ChangelogMeta>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-meta`,
    { params: { version, audience, branch } },
  );
  return data;
}

/** The developer entry's body exactly as it exists in the repo's CHANGELOG.md right now — fetched
 * fresh, read-only, for the push confirmation dialog to diff against. Null if that version has no
 * entry there yet (e.g. this would be the first push for it). */
export async function getChangelogRepoText(
  project: string,
  repo: string,
  version: string,
  branch?: string,
): Promise<string | null> {
  const { data } = await apiClient.get<{ text: string | null }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-repo-text`,
    { params: { version, branch } },
  );
  return data.text;
}