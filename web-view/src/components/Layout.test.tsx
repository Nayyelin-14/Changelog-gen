import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import type { AuthUser } from '@/api/types';

const mocks = vi.hoisted(() => ({
  reconnect: vi.fn(),
  logout: vi.fn().mockResolvedValue(undefined),
  user: {
    id: 1,
    githubId: 11,
    login: 'alice',
    avatarUrl: null,
    githubConnected: false,
  } as AuthUser,
}));

vi.mock('@/api/AuthContext', () => ({
  useAuth: () => ({
    user: mocks.user,
    loading: false,
    login: vi.fn(),
    logout: mocks.logout,
    removeAccount: vi.fn(),
    disconnect: vi.fn(),
    reconnect: mocks.reconnect,
  }),
}));

vi.mock('@/hooks/useAiProvider', () => ({
  useResolvedAiProvider: () => ({ provider: 'nvidia', options: [], enabled: [], stored: 'nvidia' }),
}));

import { Layout } from '@/components/Layout';
import { getStoredProvider, setStoredProvider } from '@/lib/provider';

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderLayout(path = '/qa') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="*" element={<LocationProbe />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('Layout when GitHub is disconnected', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mocks.user.githubConnected = false;
    mocks.logout.mockResolvedValue(undefined);
  });

  it('blocks the page instead of warning inside it, and keeps saved changelogs mentioned', () => {
    setStoredProvider('github');

    renderLayout('/dev/projects/p');

    expect(screen.getByText(/GitHub is disconnected/i)).toBeTruthy();
    expect(
      screen.getByText(/saved changelogs stay exactly where they are/i),
    ).toBeTruthy();
    // The page behind the gate must not render — a warning over half a dashboard is the bug.
    expect(screen.queryByTestId('location')).not.toBeInTheDocument();
  });

  it('offers a reconnect that returns to where the user was', () => {
    setStoredProvider('github');

    renderLayout('/qa/project-x');

    fireEvent.click(screen.getByRole('button', { name: /Reconnect GitHub/ }));
    expect(mocks.reconnect).toHaveBeenCalledWith('/qa/project-x');
  });

  it('can switch to Azure DevOps and land on the home route with a clean query cache', () => {
    setStoredProvider('github');

    renderLayout('/dev/projects/p');

    fireEvent.click(screen.getByRole('button', { name: /Continue with Azure DevOps/ }));

    expect(getStoredProvider()).toBe('azure');
    expect(screen.getByTestId('location')).toHaveTextContent('/');
  });

  it('stays quiet in Azure mode — the shared PAT is unaffected', () => {
    setStoredProvider('azure');

    renderLayout();

    expect(screen.queryByText(/GitHub is disconnected/i)).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toBeInTheDocument();
  });

  it('stays quiet for a still-connected GitHub session', () => {
    setStoredProvider('github');
    mocks.user.githubConnected = true;

    renderLayout();

    expect(screen.queryByText(/GitHub is disconnected/i)).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toBeInTheDocument();
  });

  it('never blocks /login, so the reconnect flow cannot trap itself', () => {
    setStoredProvider('github');

    renderLayout('/login');

    expect(screen.queryByText(/GitHub is disconnected/i)).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
  });
});
