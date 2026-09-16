import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { buildGithubSignInUrl, deleteGithubAccount, getAuthMe, signOut } from '@/api/auth';
import type { AuthUser } from '@/api/types';

interface AuthValue {
  /** The signed-in GitHub user, or null while acting as the shared service account. */
  user: AuthUser | null;
  /** True only for the very first /auth/me round-trip, before we know whether we're signed in. */
  loading: boolean;
  /** Full-page navigation into the GitHub OAuth flow; returns to `/#/{next}` afterwards. */
  login: (next?: string) => void;
  logout: () => Promise<void>;
  /** Removes only the stored credentials — never any generated content. */
  removeAccount: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      setUser(await getAuthMe());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const login = useCallback((next?: string) => {
    const target = next && next.startsWith('/') ? next : '/dev';
    window.location.assign(buildGithubSignInUrl(target));
  }, []);

  const logout = useCallback(async () => {
    await signOut();
    setUser(null);
  }, []);

  const removeAccount = useCallback(async () => {
    await deleteGithubAccount();
    setUser(null);
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ user, loading, login, logout, removeAccount }),
    [user, loading, login, logout, removeAccount],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider');
  return ctx;
}