import { useMemo } from 'react';
import { Link } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { getStoredRole, roleHome } from '@/lib/role';

export function NotFoundPage() {
  const home = useMemo(() => {
    const role = getStoredRole();
    return role ? roleHome(role) : '/';
  }, []);

  return (
    <div className="py-12 text-center">
      <p className="text-sm font-medium">Page not found.</p>
      <Button asChild variant="link" className="mt-1">
        <Link to={home}>Go home</Link>
      </Button>
    </div>
  );
}
