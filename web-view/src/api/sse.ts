import { providerApiBase, getStoredProvider } from '../lib/provider';
import type { AiUsage, ChatTurn, PreviewAudience } from './types';

/** Shared by every SSE endpoint this client reads (`generate-stream`, `changelog-chat/stream`)
 * — parses the raw `event: X\ndata: Y\n\n` framing into discrete events as they arrive, so each
 * caller only has to handle its own event names, not re-implement the buffering/split logic. */
export async function* readSseEvents(response: Response): AsyncGenerator<{ event: string; data: string }> {
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

export interface GenerateStreamCallbacks {
  onAudience: (audience: string, text: string, usage?: AiUsage | null) => void;
  onDone: (durationMs: number, totalTokens: number) => void;
  onError: (error: Error) => void;
}

export interface ChatStreamCallbacks {
  onDelta: (text: string) => void;
  onDone: (model: string | null) => void;
  onError: (message: string, partial: boolean) => void;
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
  callbacks: ChatStreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  const params = new URLSearchParams({ audience, version });
  const url = `${providerApiBase(getStoredProvider())}/projects/${encodeURIComponent(project)}/repos/${encodeURIComponent(repo)}/changelog-chat/stream?${params}`;

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