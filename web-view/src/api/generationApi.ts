import { apiClient } from './client';
import { providerApiBase, getStoredProvider } from '../lib/provider';
import { readSseEvents, type GenerateStreamCallbacks } from './sse';
import type { GenerateResult, ChangelogAudience } from './types';

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
  buildId?: number,
): Promise<GenerateResult> {
  const params: Record<string, string | boolean | number | undefined> = {
    model,
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

/** Streams a changelog generation via SSE. Accepts an optional {@link AbortSignal} — without it,
 * navigating away mid-generation leaves the fetch reader running and still calling callbacks into
 * an unmounted page until the backend eventually finishes on its own. */
export async function generateChangelogStream(
  project: string,
  repo: string,
  callbacks: GenerateStreamCallbacks,
  model?: string,
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
      body: JSON.stringify({ model, version, branch, fromVersion, manualText, force: !!force, buildId }),
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