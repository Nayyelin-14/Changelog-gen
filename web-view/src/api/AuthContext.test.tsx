import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import type { AuthUser } from '@/api/types';

const mocks = vi.hoisted(() => ({
  handler: null as (() => void) | null,
}));

vi.mock('@/api/auth', () => ({
  getAuthMe: vi.fn(),
  signOut: vi.fn(async () => {}),
  deleteGithubAccount: vi.fn(async () => {}),
  disconnectGithub: vi.fn(async () => {}),
  buildGithubSignInUrl: vi.fn(() => '/api/auth/github'),
  buildGithubConnectUrl: vi.fn(() => '/api/auth/github?mode=connect'),
}));

vi.mock('@/api/client', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/api/client')>();
  return {
    ...mod,
    setUnauthorizedHandler: vi.fn((h: (() => void) | null) => {
      mocks.handler = h;
    }),
  };
});

vi.mock('@/lib/chatStorage', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/lib/chatStorage')>();
  return {
    ...mod,
    clearAllChatHistory: vi.fn(mod.clearAllChatHistory),
    setChatUserScope: vi.fn(mod.setChatUserScope),
  };
});

vi.mock('@/hooks/useQuery', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/hooks/useQuery')>();
  return { ...mod, clearQueryCache: vi.fn(mod.clearQueryCache) };
});

import { getAuthMe } from '@/api/auth';
import { AuthProvider, useAuth } from '@/api/AuthContext';
import { clearQueryCache } from '@/hooks/useQuery';
import { clearAllChatHistory, setChatUserScope } from '@/lib/chatStorage';

const userA: AuthUser = {
  id: 1,
  githubId: 11,
  login: 'alice',
  avatarUrl: null,
  githubConnected: true,
};

function Probe() {
  const { user, loading } = useAuth();
  return (
    <div data-testid="ctx">{loading ? 'loading' : user ? `user:${user.id}` : 'signed-out'}</div>
  );
}

describe('AuthProvider session handling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.handler = null;
    localStorage.clear();
  });

  it('first load of the same identity does not clear account caches', async () => {
    vi.mocked(getAuthMe).mockResolvedValue(userA);

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('ctx')).toHaveTextContent('user:1'));
    expect(clearQueryCache).not.toHaveBeenCalled();
    expect(clearAllChatHistory).not.toHaveBeenCalled();
    expect(setChatUserScope).toHaveBeenCalledWith(1);
  });

  it('a 401 with a live session clears it exactly once — later 401s are no-ops', async () => {
    vi.mocked(getAuthMe).mockResolvedValue(userA);

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('ctx')).toHaveTextContent('user:1'));
    expect(mocks.handler).not.toBeNull();

    act(() => mocks.handler!());
    await waitFor(() => expect(screen.getByTestId('ctx')).toHaveTextContent('signed-out'));
    expect(clearQueryCache).toHaveBeenCalledTimes(1);
    expect(clearAllChatHistory).toHaveBeenCalledTimes(1);
    expect(setChatUserScope).toHaveBeenLastCalledWith(null);

    // The same failed burst (or in-flight requests) must not repeat the work or loop.
    act(() => mocks.handler!());
    expect(clearQueryCache).toHaveBeenCalledTimes(1);
    expect(clearAllChatHistory).toHaveBeenCalledTimes(1);
  });

  it('signing out clears account-scoped caches even if the server call fails', async () => {
    vi.mocked(getAuthMe).mockResolvedValue(userA);
    const { signOut } = await import('@/api/auth');
    vi.mocked(signOut).mockRejectedValueOnce(new Error('network down'));

    let auth!: ReturnType<typeof useAuth>;
    function LogoutProbe() {
      auth = useAuth();
      return <Probe />;
    }

    render(
      <AuthProvider>
        <LogoutProbe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('ctx')).toHaveTextContent('user:1'));

    await act(async () => {
      // The server call may fail — logout still rejects (callers surface it)…
      await expect(auth.logout()).rejects.toThrow('network down');
    });

    await waitFor(() => expect(screen.getByTestId('ctx')).toHaveTextContent('signed-out'));
    expect(clearQueryCache).toHaveBeenCalledTimes(1);
    expect(clearAllChatHistory).toHaveBeenCalledTimes(1);
    expect(setChatUserScope).toHaveBeenLastCalledWith(null);
  });
});
