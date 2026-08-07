import axios, { AxiosError } from 'axios';

import type {
  AiModelOption,
  AiUsage,
  ChangelogLocation,
  ChangelogMeta,
  ChangelogPreview,
  ChatTurn,
  GenerateResult,
  HistoryResponse,
  PipelineRunSummary,
  PreviewAudience,
  ProjectSummary,
  PullRequestDetails,
  RepoOverview,
  ReleaseData,
  RepositorySummary,
} from './types';

export const apiClient = axios.create({
  baseURL: '/api',
});

export class ApiError extends Error {
  readonly status: number | undefined;

  constructor(message: string, status: number | undefined) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ error?: string; hint?: string }>) => {
    const status = error.response?.status;
    const body = error.response?.data;
    const message = body?.error ?? error.response?.statusText ?? error.message;
    const full = body?.hint ? `${message} — ${body.hint}` : message;
    return Promise.reject(new ApiError(`${status ?? '—'} ${full}`, status));
  },
);

export async function listProjects(): Promise<ProjectSummary[]> {
  const { data } = await apiClient.get<ProjectSummary[]>('/projects');
  return data;
}

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

export async function generateChangelog(
  project: string,
  repo: string,
  model?: string,
  version?: string,
  branch?: string,
  fromVersion?: string,
  manualText?: string,
  audience?: string,
  force?: boolean,
  commit?: boolean,
): Promise<GenerateResult> {
  const params: Record<string, string | boolean | undefined> = {
    model,
    branch,
    version,
    fromVersion,
    manualText,
    audience,
    force,
    commit,
  };
  const { data } = await apiClient.post<GenerateResult>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/generate`,
    null,
    { params },
  );
  return data;
}

/** Persists a candidate AI generation the dashboard already showed for confirmation (see
 * {@link generateChangelog}'s `commit=false` preview mode) — no new AI call, just the write.
 * `text`/`model` must be exactly what the preview returned, so what's saved matches what the
 * user actually reviewed. */
export async function commitChangelog(
  project: string,
  repo: string,
  version: string,
  audience: ChangelogAudience,
  model: string,
  text: string,
  branch?: string,
  // From the preview call's own usage — this endpoint never calls the AI itself, so real
  // tokens/duration have to be carried over from whoever generated the preview.
  tokens?: number,
  durationMs?: number,
): Promise<GenerateResult> {
  const { data } = await apiClient.put<GenerateResult>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/generate-commit`,
    { version, branch, audience, model, text, tokens, durationMs },
  );
  return data;
}

/** Shared by every SSE endpoint this client reads (`generate-stream`, `changelog-chat/stream`)
 * — parses the raw `event: X\ndata: Y\n\n` framing into discrete events as they arrive, so each
 * caller only has to handle its own event names, not re-implement the buffering/split logic. */
async function* readSseEvents(response: Response): AsyncGenerator<{ event: string; data: string }> {
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const parts = buffer.split('\n\n');
    buffer = parts.pop() || '';

    for (const part of parts) {
      const lines = part.split('\n');
      let eventType = 'message';
      let dataStr = '';

      for (const line of lines) {
        if (line.startsWith('event: ')) eventType = line.slice(7);
        else if (line.startsWith('data: ')) dataStr = line.slice(6);
      }

      if (dataStr) yield { event: eventType, data: dataStr };
    }
  }
}

/** Streams a changelog generation via SSE. Accepts an optional {@link AbortSignal} — without it,
 * navigating away mid-generation leaves the fetch reader running and still calling callbacks into
 * an unmounted page until the backend eventually finishes on its own. */
export async function generateChangelogStream(
  project: string,
  repo: string,
  callbacks: {
    onAudience: (audience: string, text: string, usage?: AiUsage | null) => void;
    onDone: (durationMs: number, totalTokens: number) => void;
    onError: (error: Error) => void;
  },
  model?: string,
  version?: string,
  branch?: string,
  fromVersion?: string,
  manualText?: string,
  force?: boolean,
  signal?: AbortSignal,
): Promise<void> {
  const url = `/api/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/generate-stream`;

  // A real JSON body, not query params — manualText (the raw commit/PR/work-item text for a
  // whole build) can run into the tens of thousands of characters, which blows past a URL's
  // length limit (HTTP 414) long before it's an issue for a request body.
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model, version, branch, fromVersion, manualText, force: !!force }),
      signal,
    });
  } catch (e) {
    if (signal?.aborted) return; // the abort itself threw — not a real error to report
    throw e;
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error || `HTTP ${response.status}`);
  }

  try {
    for await (const { event, data: dataStr } of readSseEvents(response)) {
      try {
        const data = JSON.parse(dataStr);
        if (event === 'audience') {
          callbacks.onAudience(data.audience, data.text, data.usage);
        } else if (event === 'done') {
          callbacks.onDone(data.durationMs, data.totalTokens);
        } else if (event === 'error') {
          callbacks.onError(new Error(data.error));
        }
      } catch {
        // ignore malformed events
      }
    }
  } catch (e) {
    // An in-progress abort() makes the reader itself throw — that's navigate-away working as
    // intended, not a failure to surface to the user.
    if (!signal?.aborted) throw e;
  }
}

/** Streams a QA/Business chat answer about one version's changelog via SSE. Accepts
 * {@link AbortSignal} for cancellation (Stop button, close widget, navigate away, network drop). */
export async function sendChangelogChatMessageStream(
  project: string,
  repo: string,
  audience: PreviewAudience,
  version: string,
  question: string,
  history: ChatTurn[],
  callbacks: {
    onDelta: (text: string) => void;
    onDone: (model: string | null) => void;
    onError: (message: string, partial: boolean) => void;
  },
  signal: AbortSignal,
): Promise<void> {
  const params = new URLSearchParams({ audience, version });
  const url = `/api/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-chat/stream?${params}`;

  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, history }),
      signal,
    });
  } catch (e) {
    if (signal.aborted) return; // the abort itself threw — not a real error to report
    callbacks.onError(e instanceof Error ? e.message : 'Failed to reach the server.', false);
    return;
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    callbacks.onError(body?.error || `HTTP ${response.status}`, false);
    return;
  }

  try {
    for await (const { event, data: dataStr } of readSseEvents(response)) {
      try {
        if (event === 'delta') {
          callbacks.onDelta(JSON.parse(dataStr));
        } else if (event === 'done') {
          const data = JSON.parse(dataStr);
          callbacks.onDone(data.model ?? null);
        } else if (event === 'error') {
          const data = JSON.parse(dataStr);
          callbacks.onError(data.message, Boolean(data.partial));
        }
      } catch {
        // ignore malformed events
      }
    }
  } catch (e) {
    // An in-progress abort() makes the reader itself throw — that's the Stop button/widget
    // close/navigate-away path working as intended, not a failure to surface to the user.
    if (!signal.aborted) callbacks.onError(e instanceof Error ? e.message : 'Connection lost.', true);
  }
}

export async function hasChangelog(project: string, repo: string, branch?: string): Promise<boolean> {
  const { data } = await apiClient.get<boolean>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/has-changelog`,
    { params: { branch } },
  );
  return data;
}

// React StrictMode double-invokes effects — keyed in-flight requests share a single call so
// dev-mode doesn't race two requests for the same cache key before either write lands.
const previewInFlight = new Map<string, Promise<ChangelogPreview>>();

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

export async function listAiModels(): Promise<AiModelOption[]> {
  const { data } = await apiClient.get<AiModelOption[]>('/ai/models');
  return data;
}

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

export type ChangelogAudience = 'developer' | 'qa' | 'business';

/** Whatever's already saved for this version+audience (an edit or a prior generation) — no AI
 * call, no Azure DevOps round-trip. Null if nothing's been generated for it yet. */
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
