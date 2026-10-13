import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import type { AuthUser } from '@/api/types';

const mocks = vi.hoisted(() => ({
  reconnect: vi.fn(),
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
    logout: vi.fn(),
    removeAccount: vi.fn(),
    disconnect: vi.fn(),
    reconnect: mocks.reconnect,
  }),
}));

vi.mock('@/hooks/useAiProvider', () => ({
  useResolvedAiProvider: () => ({ provider: 'nvidia', options: [], enabled: [], stored: 'nvidia' }),
}));

import { Layout } from '@/components/Layout';
import { setStoredProvider } from '@/lib/provider';

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

describe('Layout disconnected-GitHub banner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mocks.user.githubConnected = false;
  });

  it('shows a status banner with a reconnect action in GitHub mode', () => {
    setStoredProvider('github');

    renderLayout('/dev/projects/p');

    const banner = screen.getByRole('status');
    expect(banner).toHaveTextContent(/GitHub is disconnected/i);
    expect(banner).toHaveTextContent(/Your saved changelogs stay in your account/i);
    expect(withinText(banner, 'Reconnect GitHub')).toBeTruthy();
  });

  it('calls reconnect with the current location so the user returns where they were', () => {
    setStoredProvider('github');

    renderLayout('/qa/project-x');

    fireEvent.click(screen.getByRole('button', { name: /Reconnect GitHub/ }));
    expect(mocks.reconnect).toHaveBeenCalledWith('/qa/project-x');
  });

  it('stays quiet in Azure mode — the shared PAT is unaffected', () => {
    setStoredProvider('azure');

    renderLayout();

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('stays quiet for a still-connected GitHub session', () => {
    setStoredProvider('github');
    mocks.user.githubConnected = true;

    renderLayout();

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

function withinText(root: HTMLElement, text: string): HTMLElement | null {
  return Array.from(root.querySelectorAll('button')).find((el) => el.textContent?.includes(text)) ?? null;
}
