import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

import {
  buildGithubConnectUrl,
  buildGithubSignInUrl,
  deleteGithubAccount,
  disconnectGithub,
  getAuthMe,
  signOut,
} from '@/api/auth';
import { setUnauthorizedHandler } from '@/api/client';
import type { AuthUser } from '@/api/types';
import { clearAllChatHistory, setChatUserScope } from '@/lib/chatStorage';
import { clearQueryCache } from '@/hooks/useQuery';

interface AuthValue {
  /** The signed-in GitHub user, or null while unauthenticated (every route requires a session). */
  user: AuthUser | null;
  /** True only for the very first /auth/me round-trip, before we know whether we're signed in. */
  loading: boolean;
  /** Full-page navigation into the GitHub OAuth flow; returns to `/#/{next}` afterwards. */
  login: (next?: string) => void;
  logout: () => Promise<void>;
  /** Deletes the account and all data it owns server-side, then clears local state. */
  removeAccount: () => Promise<void>;
  /** Erases the GitHub token but keeps the account, data, and session — for a later reconnect. */
  disconnect: () => Promise<void>;
  /** Full-page navigation into the {@code mode=connect} OAuth flow (for reconnect). */
  reconnect: (next?: string) => void;
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);
  // Mirror of `user` for the unauthorized handler — effects run after render, but a 401 can
  // land any time, and the handler must know whether a session is currently believed live.
  const userRef = useRef<AuthUser | null>(null);
  const prevUserIdRef = useRef<number | null | undefined>(undefined);

  /** Single write-path for the signed-in identity. Whenever it CHANGES account (sign-in,
   * sign-out, account switch, deletion, session expiry) every account-scoped client cache is
   * dropped first, so one account's data can never render in another's session. A disconnect
   * or reconnect keeps the same id and therefore keeps everything — as designed. */
  const applyUser = useCallback((next: AuthUser | null) => {
    const prevId = prevUserIdRef.current;
    const nextId = next?.id ?? null;
    if (prevId !== nextId && prevId !== undefined) {
      clearQueryCache();
      clearAllChatHistory();
    }
    prevUserIdRef.current = nextId;
    setChatUserScope(nextId);
    userRef.current = next;
    setUser(next);
  }, []);

  const refresh = useCallback(async () => {
    try {
      applyUser(await getAuthMe());
    } finally {
      setLoading(false);
    }
  }, [applyUser]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Centralized 401/expired-session handling. The first 401 while a session is believed live
  // clears it once; follow-up 401s (the same failed burst, or requests already in flight)
  // find userRef null and do nothing — no redirect loops, no repeated notifications. RouteGuard
  // then routes the now-signed-out UI to /login with a return path.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      if (userRef.current != null) applyUser(null);
    });
    return () => setUnauthorizedHandler(null);
  }, [applyUser]);

  const login = useCallback((next?: string) => {
    const target = next && next.startsWith('/') ? next : '/dev';
    window.location.assign(buildGithubSignInUrl(target));
  }, []);

  const logout = useCallback(async () => {
    try {
      await signOut();
    } finally {
      // Even if the POST fails (session already dead server-side), the browser must end up
      // signed out locally — a stale "signed in" header over an expired cookie is worse.
      applyUser(null);
    }
  }, [applyUser]);

  const removeAccount = useCallback(async () => {
    await deleteGithubAccount();
    applyUser(null);
  }, [applyUser]);

  const disconnect = useCallback(async () => {
    await disconnectGithub();
    await refresh();
  }, [refresh]);

  const reconnect = useCallback((next?: string) => {
    const target = next && next.startsWith('/') ? next : window.location.pathname || '/dev';
    window.location.assign(buildGithubConnectUrl(target));
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ user, loading, login, logout, removeAccount, disconnect, reconnect }),
    [user, loading, login, logout, removeAccount, disconnect, reconnect],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider');
  return ctx;
}
