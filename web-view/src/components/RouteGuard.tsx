import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

import { getStoredRole, roleHome, type Role } from '@/lib/role';

export function RouteGuard({ role, children }: { role: Role; children: React.ReactNode }) {
  const navigate = useNavigate();
  // Read synchronously on every render (not in state set from an effect) — otherwise every
  // guarded route renders null for a tick on first mount before flipping to children, which
  // shows up as a blank flash on every dev/qa/business navigation.
  const stored = getStoredRole();
  const allowed = stored === role;

  useEffect(() => {
    if (!allowed) {
      navigate(stored ? roleHome(stored) : '/', { replace: true });
    }
  }, [navigate, role, stored, allowed]);

  if (!allowed) return null;
  return <>{children}</>;
}
