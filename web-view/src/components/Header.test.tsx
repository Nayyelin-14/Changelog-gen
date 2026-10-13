import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import type { AuthUser } from '@/api/types';

vi.mock('@/api/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 1,
      githubId: 11,
      login: 'alice',
      avatarUrl: null,
      githubConnected: true,
    } satisfies AuthUser,
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    removeAccount: vi.fn(),
    disconnect: vi.fn(),
    reconnect: vi.fn(),
  }),
}));

vi.mock('@/hooks/useQuery', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/hooks/useQuery')>();
  return { ...mod, clearQueryCache: vi.fn(mod.clearQueryCache) };
});

import { Header } from '@/components/Header';
import { clearQueryCache } from '@/hooks/useQuery';
import { setStoredProvider } from '@/lib/provider';

function renderHeader() {
  return render(
    <MemoryRouter>
      <Header />
    </MemoryRouter>,
  );
}

describe('Header', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    setStoredProvider('azure');
  });

  it('delete dialog copy draws the right line between owned data and other users/repos', async () => {
    renderHeader();

    fireEvent.click(screen.getByRole('button', { name: 'Account menu' }));
    fireEvent.click(screen.getByRole('button', { name: /Delete account/ }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Delete your account?')).toBeInTheDocument();

    // "This will be removed" — includes every owned artifact (revisions included).
    const removedHeading = within(dialog).getByText('This will be removed');
    const removedList = removedHeading.closest('div')!.querySelector('ul')!;
    expect(within(removedList).getByText(/Every changelog, release note, version, and revision/)).toBeInTheDocument();
    // Repo content is NOT deletable from here — it must not appear under "will be removed".
    expect(within(removedList).queryByText(/GitHub repositories/)).not.toBeInTheDocument();

    // "This stays untouched" — other users + the contents of the user's own repos.
    const untouchedHeading = within(dialog).getByText('This stays untouched');
    const untouchedList = untouchedHeading.closest('div')!.querySelector('ul')!;
    expect(
      within(untouchedList).getByText("Other users' accounts, changelogs, and revisions"),
    ).toBeInTheDocument();
    expect(
      within(untouchedList).getByText(/Everything in your GitHub repositories — nothing is pushed or deleted there/),
    ).toBeInTheDocument();
  });

  it('switching the page provider drops cached query results so nothing renders stale', () => {
    renderHeader();

    fireEvent.click(screen.getByRole('button', { name: 'Switch to GitHub' }));

    expect(clearQueryCache).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('changelog-provider')).toBe('github');
  });
});
