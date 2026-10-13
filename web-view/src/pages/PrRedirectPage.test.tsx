import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('@/api/client', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/api/client')>();
  return {
    ...mod,
    getChangelogLocation: vi.fn(),
    listHistory: vi.fn(),
  };
});

import { ApiError, getChangelogLocation, listHistory } from '@/api/client';
import { PrRedirectPage } from '@/pages/PrRedirectPage';

function LoginProbe() {
  const location = useLocation();
  const state = location.state as { next?: string } | null;
  return <div data-testid="login">next={state?.next ?? '(none)'}</div>;
}

function renderPr(initial: string) {
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route path="/pr/:project/:repo/:prId" element={<PrRedirectPage />} />
        <Route path="/login" element={<LoginProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('PrRedirectPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('sends an unauthenticated visitor through /login and back to the exact deep link', async () => {
    vi.mocked(getChangelogLocation).mockRejectedValue(new ApiError('401 not_authenticated', 401));

    renderPr('/pr/my-proj/my-repo/1149?tab=dev');

    await waitFor(() => expect(screen.getByTestId('login')).toBeInTheDocument());
    expect(screen.getByTestId('login')).toHaveTextContent('next=/pr/my-proj/my-repo/1149?tab=dev');
    expect(screen.queryByText(/Looking up/)).not.toBeInTheDocument();
  });

  it('shows an inline error for non-auth failures instead of bouncing to login', async () => {
    vi.mocked(getChangelogLocation).mockRejectedValue(new ApiError('500 boom', 500));

    renderPr('/pr/my-proj/my-repo/1149');

    expect(await screen.findByText("Couldn't open this changelog")).toBeInTheDocument();
    expect(screen.queryByTestId('login')).not.toBeInTheDocument();
  });

  it('jumps straight to the history entry once the PR resolves', async () => {
    vi.mocked(getChangelogLocation).mockResolvedValue({
      status: 'released',
      version: '2.1.0',
    } as Awaited<ReturnType<typeof getChangelogLocation>>);
    vi.mocked(listHistory).mockResolvedValue({
      entries: [{ id: 42, version: '2.1.0' }],
      total: 1,
    } as unknown as Awaited<ReturnType<typeof listHistory>>);

    render(
      <MemoryRouter initialEntries={['/pr/my-proj/my-repo/1149']}>
        <Routes>
          <Route path="/pr/:project/:repo/:prId" element={<PrRedirectPage />} />
          <Route
            path="/dev/projects/:project/repos/:repo/history/:entryId"
            element={<div data-testid="history-entry" />}
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByTestId('history-entry')).toBeInTheDocument();
  });
});
