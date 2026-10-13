import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import type { AuthUser } from '@/api/types';

const authState = vi.hoisted(() => ({
  user: null as AuthUser | null,
  loading: false,
}));

vi.mock('@/api/AuthContext', () => ({
  useAuth: () => ({
    user: authState.user,
    loading: authState.loading,
    login: vi.fn(),
    logout: vi.fn(),
    removeAccount: vi.fn(),
    disconnect: vi.fn(),
    reconnect: vi.fn(),
  }),
}));

import { RouteGuard } from '@/components/RouteGuard';
import { setStoredRole } from '@/lib/role';

function LoginProbe() {
  const location = useLocation();
  const state = location.state as { next?: string } | null;
  return (
    <div data-testid="login">
      next={state?.next ?? '(none)'}
    </div>
  );
}

function HomeProbe() {
  return <div data-testid="home" />;
}

function renderGuard(initial: string) {
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route
          path="/dev/*"
          element={
            <RouteGuard role="dev">
              <div data-testid="guarded">guarded content</div>
            </RouteGuard>
          }
        />
        <Route
          path="/qa/*"
          element={
            <RouteGuard role="qa">
              <div data-testid="guarded-qa">qa content</div>
            </RouteGuard>
          }
        />
        <Route path="/login" element={<LoginProbe />} />
        <Route path="/" element={<HomeProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RouteGuard', () => {
  beforeEach(() => {
    localStorage.clear();
    authState.user = null;
    authState.loading = false;
  });

  it('redirects a signed-out visitor to /login preserving path + query as next', () => {
    setStoredRole('dev');
    renderGuard('/dev/projects/p/repos/r?version=1.2.3');

    expect(screen.getByTestId('login')).toHaveTextContent(
      'next=/dev/projects/p/repos/r?version=1.2.3',
    );
    expect(screen.queryByTestId('guarded')).not.toBeInTheDocument();
  });

  it('renders children for a signed-in user with the matching role', () => {
    setStoredRole('dev');
    authState.user = {
      id: 1,
      githubId: 1,
      login: 'alice',
      avatarUrl: null,
      githubConnected: true,
    };

    renderGuard('/dev');

    expect(screen.getByTestId('guarded')).toBeInTheDocument();
    expect(screen.queryByTestId('login')).not.toBeInTheDocument();
  });

  it('sends a role mismatch to that stored role’s home, not to the guarded page', () => {
    setStoredRole('qa');
    authState.user = {
      id: 1,
      githubId: 1,
      login: 'alice',
      avatarUrl: null,
      githubConnected: true,
    };

    renderGuard('/dev/projects/p/repos/r');

    expect(screen.getByTestId('guarded-qa')).toBeInTheDocument();
    expect(screen.queryByTestId('guarded')).not.toBeInTheDocument();
  });

  it('renders nothing (no flash, no redirect) while the session check is in flight', () => {
    setStoredRole('dev');
    authState.loading = true;

    renderGuard('/dev');

    expect(screen.queryByTestId('guarded')).not.toBeInTheDocument();
    expect(screen.queryByTestId('login')).not.toBeInTheDocument();
  });
});
