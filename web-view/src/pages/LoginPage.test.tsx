import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('@/api/AuthContext', () => ({
  useAuth: () => ({
    user: null,
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    removeAccount: vi.fn(),
    disconnect: vi.fn(),
    reconnect: vi.fn(),
  }),
}));

import { LoginPage } from '@/pages/LoginPage';

function renderLogin(search: string) {
  return render(
    <MemoryRouter initialEntries={[`/login${search}`]}>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('explains a GitHub account already linked elsewhere', () => {
    renderLogin('?error=github_linked_elsewhere');
    expect(
      screen.getByText(/already belongs to a different account here/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/accounts are never merged/i)).toBeInTheDocument();
  });

  it('explains an expired/forced sign-out', () => {
    renderLogin('?error=not_authenticated');
    expect(screen.getByText(/session ended before this finished/i)).toBeInTheDocument();
  });

  it('rewords access_denied for both OAuth flows', () => {
    renderLogin('?error=access_denied');
    expect(
      screen.getByText(/Authorization was cancelled — no changes were made/i),
    ).toBeInTheDocument();
  });

  it('falls back to a generic message for unknown errors', () => {
    renderLogin('?error=weird_new_code');
    expect(
      screen.getByText(/Something went wrong during GitHub sign-in/i),
    ).toBeInTheDocument();
  });

  it('states the per-account ownership model in the trust section', () => {
    renderLogin('');
    expect(screen.getByText('You stay in control')).toBeInTheDocument();
    expect(
      screen.getByText(/Changelogs are owned by your account/i),
    ).toBeInTheDocument();
    // Old copy claimed a shared storage line that no longer reflects the model.
    expect(screen.queryByText(/Stored in your browser only/)).not.toBeInTheDocument();
  });
});
