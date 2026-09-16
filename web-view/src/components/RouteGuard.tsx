import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '@/api/AuthContext';
import { getStoredProvider } from '@/lib/provider';
import { getStoredRole, roleHome, type Role } from '@/lib/role';

export function RouteGuard({ role, children }: { role: Role; children: React.ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, loading } = useAuth();
  // Read synchronously on every render (not in state set from an effect) — otherwise every
  // guarded route renders null for a tick on first mount before flipping to children, which
  // shows up as a blank flash on every dev/qa/business navigation.
  const stored = getStoredRole();
  const allowed = stored === role;

  // GitHub provider dev routes act as the signed-in user (repo/commit/push calls carry their
  // token), so they need a session first. Azure rides the shared service account — no sign-in.
  const needsSignIn = role === 'dev' && getStoredProvider() === 'github';

  useEffect(() => {
    if (!allowed) {
      navigate(stored ? roleHome(stored) : '/', { replace: true });
      return;
    }
    if (needsSignIn && !loading && !user) {
      navigate('/login', { replace: true, state: { next: location.pathname } });
    }
  }, [navigate, location.pathname, role, stored, allowed, needsSignIn, loading, user]);

  if (!allowed) return null;
  if (needsSignIn && (loading || !user)) return null;
  return <>{children}</>;
}