import { describe, it, expect, beforeEach, vi } from 'vitest';

import {
  ApiError,
  apiClient,
  rootApiClient,
  generateChangelogStream,
  sendChangelogChatMessageStream,
  setUnauthorizedHandler,
  notifyUnauthorized,
} from '@/api/client';
import { setStoredProvider } from '@/lib/provider';

function stubStatus(client: typeof apiClient, status: number, data: unknown = { error: 'expired' }) {
  client.defaults.adapter = (config) =>
    Promise.reject({
      response: {
        status,
        statusText: status === 401 ? 'Unauthorized' : 'Error',
        headers: {},
        config,
        data,
      },
      config,
    });
}

describe('centralized 401 handling', () => {
  beforeEach(() => {
    localStorage.clear();
    setStoredProvider('azure');
    setUnauthorizedHandler(null);
  });

  it('fires the session-expiry handler for a protected-endpoint 401', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    stubStatus(apiClient, 401);

    await expect(apiClient.get('/projects')).rejects.toMatchObject({ status: 401 });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('skips /auth/* 401s — a logged-out /auth/me is not session expiry', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    stubStatus(apiClient, 401);

    await expect(apiClient.get('/auth/me')).rejects.toMatchObject({ status: 401 });
    expect(handler).not.toHaveBeenCalled();
  });

  it('covers rootApiClient (pipeline endpoints) too', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    stubStatus(rootApiClient, 401);

    await expect(rootApiClient.get('/pipeline/runs')).rejects.toMatchObject({ status: 401 });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('non-401 failures never touch the handler', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    stubStatus(apiClient, 500);

    await expect(apiClient.get('/projects')).rejects.toMatchObject({ status: 500 });
    expect(handler).not.toHaveBeenCalled();
  });

  it('never surfaces a raw 5xx server message', async () => {
    stubStatus(apiClient, 500, { error: 'SQLState 53000 quota exceeded' });

    const error = await apiClient.get('/projects').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(500);
    expect((error as ApiError).message).toBe(
      'Something went wrong on our side. Please try again.',
    );
    expect((error as ApiError).message).not.toContain('SQL');
  });

  it('maps 503 to a calm, retryable message', async () => {
    stubStatus(apiClient, 503, { error: 'service_unavailable', message: 'quota exceeded' });

    const error = await apiClient.get('/projects').catch((e: unknown) => e);
    expect((error as ApiError).status).toBe(503);
    expect((error as ApiError).message).toBe(
      'The service is temporarily unavailable. Please try again in a moment.',
    );
  });

  it('notifyUnauthorized is safe with no handler registered', () => {
    expect(() => notifyUnauthorized()).not.toThrow();
  });

  it('generate-stream 401 notifies before surfacing the error', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 401, json: async () => ({ error: 'expired' }) })),
    );

    await expect(
      generateChangelogStream('p', 'r', {
        onAudience: () => {},
        onDone: () => {},
        onError: () => {},
      }),
    ).rejects.toThrow('The service is temporarily unavailable. Please try again.');
    expect(handler).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });

  it('chat stream 401 notifies and reports through onError', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 401, json: async () => ({ error: 'expired' }) })),
    );
    const onError = vi.fn();

    await sendChangelogChatMessageStream(
      'p',
      'r',
      'qa',
      '1.0.0',
      'question',
      [],
      { onDelta: () => {}, onDone: () => {}, onError },
      new AbortController().signal,
    );

    expect(handler).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(
      'The service is temporarily unavailable. Please try again.',
      false,
    );
    vi.unstubAllGlobals();
  });
});
