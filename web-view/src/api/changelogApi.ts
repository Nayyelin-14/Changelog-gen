import { apiClient } from './client';
import type { GenerateResult, ChangelogAudience } from './types';

export async function saveChangelogEdit(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
  text: string,
  editedBy?: string,
  branch?: string,
): Promise<GenerateResult> {
  const { data } = await apiClient.put<GenerateResult>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-edit`,
    { version, branch, audience, text, editedBy },
  );
  return data;
}

/** Swaps the previous text back to current for one version+audience — Postgres-only, works for
 * all three audiences, never touches the repo. Throws if there's nothing to restore (check
 * `ChangelogMeta.hasPrevious` first to decide whether to show a restore control at all). */
export async function restoreChangelogPrevious(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
): Promise<string> {
  const { data } = await apiClient.put<{ text: string }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-restore`,
    null,
    { params: { version, audience } },
  );
  return data.text;
}

/** Rolls back to whatever was last successfully pushed to the repo — a separate rollback target
 * from {@link restoreChangelogPrevious}, which only ever undoes the last edit/regeneration.
 * Developer-only, same restriction as {@link pushChangelog}. Throws if nothing has been pushed
 * yet (check `ChangelogMeta.pushedAt` first to decide whether to show a restore control at all). */
export async function restoreChangelogToPushed(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
): Promise<string> {
  const { data } = await apiClient.put<{ text: string }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-restore-pushed`,
    null,
    { params: { version, audience } },
  );
  return data.text;
}

/** Rolls back to an arbitrary past revision (not just one step back) — the general form of
 * {@link restoreChangelogPrevious} for picking any row out of the full edit history shown in
 * {@link ChangelogEditHistoryPanel}. `sequence` is that revision's own audience-scoped sequence
 * number (see `ChangelogRevisionDto.sequence`), never a global id. */
export async function restoreChangelogRevision(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
  sequence: number,
): Promise<string> {
  const { data } = await apiClient.put<{ text: string }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-revision-restore`,
    null,
    { params: { version, audience, sequence } },
  );
  return data.text;
}

/** Deletes one shared revision — a snapshot across all three audiences, not just one — so this
 * takes no `audience`; the backend removes whichever of the three has a row at `sequence` and
 * renumbers all three together. */
export async function deleteChangelogRevision(
  project: string,
  repo: string,
  version: string,
  sequence: number,
): Promise<void> {
  await apiClient.delete(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-revision`,
    { params: { version, sequence } },
  );
}

/** Pushes the current Developer text for one version back into the repo's CHANGELOG.md as a
 * direct commit (the service's own bot identity, not a branch + PR) — developer-only for now.
 * `branch` must be the branch this version's entry was actually resolved against (e.g.
 * `HistoryEntry.branch`), not a page-level default. `text`/`model` are only needed when pushing a
 * just-generated preview that was never auto-saved (Generate no longer persists on its own) —
 * omit them when pushing an already-cached entry (e.g. from the Version history list), and the
 * backend falls back to what's already saved. */
export async function pushChangelog(
  project: string,
  repo: string,
  version: string,
  branch: string,
  audience: ChangelogAudience,
  unsaved?: { text: string; model?: string },
): Promise<{ commitUrl: string }> {
  const { data } = await apiClient.post<{ commitUrl: string }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-push`,
    { version, branch, audience, text: unsaved?.text, model: unsaved?.model },
  );
  return data;
}