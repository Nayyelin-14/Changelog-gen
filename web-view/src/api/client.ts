import axios, { AxiosError, type AxiosInstance } from 'axios';

import { providerApiBase, getStoredProvider } from '../lib/provider';
import { getStoredAiProvider } from '../lib/aiProvider';
import type {
  AiModelOption,
  AiProviderOption,
  AiUsage,
  ChangelogLocation,
  ChangelogMeta,
  ChangelogPreview,
  ChangelogPushResult,
  ChatTurn,
  GenerateResult,
  HistoryResponse,
  PipelineRunSummary,
  PreviewAudience,
  ProjectSummary,
  PullRequestDetails,
  RepoOverview,
  ReleaseData,
  ReleaseVersionResolution,
  RepositorySummary,
  RunChangeContext,
} from './types';

// A timeout, unlike axios's default of "never": every spinner in the app is driven by one of
// these calls, and a hung backend otherwise parks it forever. SSE endpoints don't ride axios
// (they use raw fetch below) — streaming responses have their own watchdog in the chat widget.
export const API_TIMEOUT_MS = 60_000;

export const apiClient = axios.create({
  baseURL: providerApiBase(getStoredProvider()),
  timeout: API_TIMEOUT_MS,
});

// Centralized session-expiry handling: AuthProvider registers a single handler that clears the
// stale user (which routes the UI back to /login via RouteGuard) exactly once per live session.
// Every 401 from a protected endpoint funnels through here — axios calls via the interceptors
// below, raw-fetch SSE calls via notifyUnauthorized() — so no surface keeps rendering as if the
// user were still signed in.
type UnauthorizedHandler = () => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

/** Fires the registered handler if there currently is one. Safe to call repeatedly — the
 * handler itself decides whether the session is already cleared (no loops, no repeat toasts). */
export function notifyUnauthorized(): void {
  unauthorizedHandler?.();
}

/** A tiny store the app shell subscribes to so infrastructure failures surface as one calm,
 * persistent banner instead of per-request raw errors. 4xx responses still mean the service is
 * reachable; only transport failures and 5xx mark it offline. */
export type ServiceStatus = 'online' | 'offline';

const serviceStatusListeners = new Set<(status: ServiceStatus) => void>();
let serviceStatus: ServiceStatus = 'online';

export function subscribeServiceStatus(listener: (status: ServiceStatus) => void): () => void {
  serviceStatusListeners.add(listener);
  listener(serviceStatus);
  return () => {
    serviceStatusListeners.delete(listener);
  };
}

function setServiceStatus(next: ServiceStatus): void {
  if (serviceStatus === next) return;
  serviceStatus = next;
  serviceStatusListeners.forEach((listener) => listener(next));
}

type ApiErrorBody = {
  error?: string;
  message?: string;
  hint?: string;
  requestId?: string;
};

const FRIENDLY: Record<string, string> = {
  not_authenticated: 'Your session has expired. Please sign in again.',
  github_not_connected: 'GitHub is disconnected — reconnect it to fetch repository data.',
  forbidden: "You don't have permission to do that.",
  not_found: "We couldn't find what you were looking for.",
  rate_limited: "You're doing that too quickly. Please wait a moment and try again.",
  service_unavailable: 'The service is temporarily unavailable. Please try again in a moment.',
  upstream_unavailable: 'An external service is temporarily unavailable. Please try again in a moment.',
  timeout: 'That took too long. Please try again.',
  internal_error: 'Something went wrong on our side. Please try again.',
  ai_request_failed: "The AI request couldn't be completed. Please try again or choose a different model.",
  bad_request: 'Please check the details and try again.',
  network_error: "We can't reach the service right now. Check your connection and try again.",
};

function friendlyMessage(
  status: number | undefined,
  body: ApiErrorBody | undefined,
  transportMessage: string,
): string {
  if (status === undefined) {
    return /timeout|timed out/i.test(transportMessage) ? FRIENDLY.timeout : FRIENDLY.network_error;
  }
  if (status === 401) return FRIENDLY.not_authenticated;
  if (status === 403) {
    return body?.error === 'github_not_connected'
      ? FRIENDLY.github_not_connected
      : FRIENDLY.forbidden;
  }
  if (status === 404) return FRIENDLY.not_found;
  if (status === 429) return FRIENDLY.rate_limited;
  if (status >= 500) {
    if (status === 502) return FRIENDLY.upstream_unavailable;
    if (status === 503) return FRIENDLY.service_unavailable;
    if (status === 504) return FRIENDLY.timeout;
    return FRIENDLY.internal_error;
  }
  // A 4xx message is actionable (validation, stale base commit, ownership, …) and is already
  // sanitized server-side. Prefer it, but still map bare codes to friendly copy.
  if (body?.message) return body.message;
  const code = body?.error;
  if (code && /\s/.test(code)) return code;
  if (code && FRIENDLY[code]) return FRIENDLY[code];
  return FRIENDLY.bad_request;
}

export class ApiError extends Error {
  readonly status: number | undefined;
  readonly code: string | undefined;
  readonly requestId: string | undefined;

  constructor(message: string, status?: number, code?: string, requestId?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

function onResponseError(error: AxiosError<ApiErrorBody>) {
  if (axios.isCancel(error)) return Promise.reject(error);

  const rawStatus = error.response?.status;
  const status = rawStatus && rawStatus > 0 ? rawStatus : undefined;
  if (status === undefined || status >= 500) {
    setServiceStatus('offline');
  } else {
    setServiceStatus('online');
  }
  if (status === 401 && !isAuthUrl(error.config?.url)) notifyUnauthorized();

  const body = error.response?.data;
  const headerId = error.response?.headers?.['x-request-id'];
  const requestId = body?.requestId ?? (typeof headerId === 'string' ? headerId : undefined);
  return Promise.reject(
    new ApiError(
      friendlyMessage(status, body, error.message),
      status,
      body?.error,
      requestId,
    ),
  );
}

/** `/auth/*` answers 401 by design (`/me` for a logged-out browser) — those are not session
 * expiry and must not trigger the global handler. */
function isAuthUrl(url: string | undefined): boolean {
  return (url ?? '').startsWith('/auth');
}

function installInterceptors(client: AxiosInstance): void {
  client.interceptors.response.use(
    (response) => {
      setServiceStatus('online');
      return response;
    },
    onResponseError,
  );
}

// Provider can change at runtime (localStorage) without a reload — re-resolve the base URL on
// every request so Azure and GitHub share the same axios instance but hit different route trees.
apiClient.interceptors.request.use((config) => {
  config.baseURL = providerApiBase(getStoredProvider());
  return config;
});

export const rootApiClient = axios.create({
  baseURL: '/api',
  timeout: API_TIMEOUT_MS,
});

installInterceptors(apiClient);
installInterceptors(rootApiClient);

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
  provider?: string,
  version?: string,
  branch?: string,
  fromVersion?: string,
  manualText?: string,
  audience?: string,
  force?: boolean,
  commit?: boolean,
  buildId?: number,
): Promise<GenerateResult> {
  const params: Record<string, string | boolean | number | undefined> = {
    model,
    provider: provider || getStoredAiProvider(),
    branch,
    version,
    fromVersion,
    manualText,
    audience,
    force,
    commit,
    buildId,
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
 * user actually reviewed. A manual dashboard generation has no version yet: pass `buildId` (the
 * pipeline run the text came from) instead, and version is filled in at push time. */
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
  buildId?: number,
): Promise<GenerateResult> {
  const { data } = await apiClient.put<GenerateResult>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/generate-commit`,
    { version, branch, audience, model, text, tokens, durationMs, buildId },
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
  provider?: string,
  version?: string,
  branch?: string,
  fromVersion?: string,
  manualText?: string,
  force?: boolean,
  signal?: AbortSignal,
  buildId?: number,
): Promise<void> {
  const url = `${providerApiBase(getStoredProvider())}/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/generate-stream`;

  // A real JSON body, not query params — manualText (the raw commit/PR/work-item text for a
  // whole build) can run into the tens of thousands of characters, which blows past a URL's
  // length limit (HTTP 414) long before it's an issue for a request body.
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model,
        provider: provider || getStoredAiProvider(),
        version,
        branch,
        fromVersion,
        manualText,
        force: !!force,
        buildId,
      }),
      signal,
    });
  } catch {
    if (signal?.aborted) return; // the abort itself threw — not a real error to report
    throw new Error("We couldn't reach the service. Check your connection and try again.");
  }
  if (!response.ok) {
    if (response.status === 401) notifyUnauthorized();
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || 'The service is temporarily unavailable. Please try again.');
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
          callbacks.onError(new Error(data.message || "We couldn't finish that generation. Please try again."));
        }
      } catch {
        // ignore malformed events
      }
    }
  } catch {
    // An in-progress abort() makes the reader itself throw — that's navigate-away working as
    // intended, not a failure to surface to the user.
    if (!signal?.aborted) {
      throw new Error("We couldn't reach the service. Check your connection and try again.");
    }
  }
}

/** Streams a QA/Business chat answer about one version's changelog via SSE. Accepts
 * {@link AbortSignal} for cancellation (Stop button, close widget, navigate away, network drop).
 * `model` is the user's picker choice — the same catalog the generate page offers — and the
 * backend passes it to the provider as its override (null = the provider's configured default). */
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
  provider?: string,
  model?: string,
): Promise<void> {
  const params = new URLSearchParams({ audience, version, provider: provider || getStoredAiProvider() });
  if (model) params.set('model', model);
  const url = `${providerApiBase(getStoredProvider())}/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-chat/stream?${params}`;

  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, history }),
      signal,
    });
  } catch {
    if (signal.aborted) return; // the abort itself threw — not a real error to report
    callbacks.onError("We couldn't reach the service. Check your connection and try again.", false);
    return;
  }
  if (!response.ok) {
    if (response.status === 401) notifyUnauthorized();
    const body = await response.json().catch(() => null);
    callbacks.onError(body?.message || 'The service is temporarily unavailable. Please try again.', false);
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
  } catch {
    // An in-progress abort() makes the reader itself throw — that's the Stop button/widget
    // close/navigate-away path working as intended, not a failure to surface to the user.
    if (!signal.aborted) callbacks.onError('The connection was lost. Please try again.', true);
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

export async function listAiProviders(): Promise<AiProviderOption[]> {
  const { data } = await apiClient.get<AiProviderOption[]>('/ai/providers');
  return data;
}

export async function listAiModels(provider?: string): Promise<AiModelOption[]> {
  const { data } = await apiClient.get<AiModelOption[]>('/ai/models', {
    params: { provider: provider || getStoredAiProvider() },
  });
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
  buildId?: number,
): Promise<string | null> {
  const { data } = await apiClient.get<{ text: string | null }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-text`,
    { params: { version, audience, buildId } },
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
  buildId?: number,
): Promise<ChangelogMeta> {
  const { data } = await apiClient.get<ChangelogMeta>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-meta`,
    { params: { version, audience, branch, buildId } },
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
  buildId?: number,
): Promise<GenerateResult> {
  const { data } = await apiClient.put<GenerateResult>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-edit`,
    { version, branch, audience, text, editedBy, buildId },
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
  buildId?: number,
): Promise<string> {
  const { data } = await apiClient.put<{ text: string }>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-revision-restore`,
    null,
    { params: { version, audience, sequence, buildId } },
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
  buildId?: number,
): Promise<ChangelogPushResult> {
  const { data } = await apiClient.post<ChangelogPushResult>(
    `/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-push`,
    { version, branch, audience, text: unsaved?.text, model: unsaved?.model, buildId },
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

export async function getRecordedRuns(
  project: string,
  repo: string,
  provider?: 'azure' | 'github',
): Promise<PipelineRunSummary[]> {
  const { data } = await rootApiClient.get<PipelineRunSummary[]>(
    `/pipeline/runs`,
    { params: { project, repo, provider } },
  );
  return data;
}

export async function getRecordedRunChanges(
  project: string,
  repo: string,
  runId: number,
  provider?: 'azure' | 'github',
): Promise<ReleaseData> {
  const { data } = await rootApiClient.get<ReleaseData>(
    `/pipeline/runs/${runId}/changes`,
    { params: { project, repo, provider } },
  );
  return data;
}

export interface AiDraft {
  audience: string | null;
  text: string | null;
  model: string | null;
  tokens: number | null;
  durationMs: number | null;
}

export async function getAiDraft(
  project: string,
  repo: string,
  runId: number,
  provider?: 'azure' | 'github',
): Promise<AiDraft> {
  const { data } = await rootApiClient.get<AiDraft>(
    `/pipeline/runs/${runId}/draft`,
    { params: { project, repo, provider } },
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

/** Provider-normalized run context — run + PR + commits + files + work items — for one run. */
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
