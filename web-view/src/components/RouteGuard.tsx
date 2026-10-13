import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '@/api/AuthContext';
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
  // Where to return after signing in — path plus query so deep links (e.g. ?version=…)
  // survive the round-trip. HashRouter paths are same-site relative, and the server's
  // sanitizeNext re-checks the value at the OAuth boundary.
  const next = location.pathname + location.search;

  // EVERY guarded route needs a session: the backend is default-deny (AuthenticatedFilter
  // answers 401 to anything outside /auth/* without a cookie), regardless of provider or role.
  useEffect(() => {
    if (!allowed) {
      navigate(stored ? roleHome(stored) : '/', { replace: true });
      return;
    }
    if (!loading && !user) {
      navigate('/login', { replace: true, state: { next } });
    }
  }, [navigate, next, allowed, stored, loading, user]);

  if (!allowed) return null;
  if (loading || !user) return null;
  return <>{children}</>;
}
