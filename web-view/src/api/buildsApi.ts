import { apiClient } from './client';
import type { PipelineRunSummary, ReleaseData, ReleaseVersionResolution, RunChangeContext } from './types';

export async function getRepoBuilds(
  project: string,
  repo: string,
  top = 20,
): Promise<PipelineRunSummary[]> {
  const { data } = await apiClient.get<PipelineRunSummary[]>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/builds`,
    { params: { top } },
  );
  return data;
}

/** Resolves the latest semantic version from CHANGELOG.md, the suggested next version,
 * and the current branch HEAD commit SHA — used by the generate-new-changelog page. */
export async function resolveReleaseVersion(
  project: string,
  repo: string,
  branch?: string,
): Promise<ReleaseVersionResolution> {
  const { data } = await apiClient.get<ReleaseVersionResolution>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/release-version`,
    { params: branch ? { branch } : undefined },
  );
  return data;
}

export async function getBuildChanges(
  project: string,
  repo: string,
  buildId: number,
): Promise<ReleaseData> {
  const { data } = await apiClient.get<ReleaseData>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/builds/${buildId}/changes`,
  );
  return data;
}

/** The provider-normalized run context — run + PR + commits + files + work items — for a single
 * pipeline run. This is the "inspect the run" source that also feeds run-based generation. */
export async function getRunContext(
  project: string,
  repo: string,
  buildId: number,
): Promise<RunChangeContext> {
  const { data } = await apiClient.get<RunChangeContext>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/builds/${buildId}/run-context`,
  );
  return data;
}