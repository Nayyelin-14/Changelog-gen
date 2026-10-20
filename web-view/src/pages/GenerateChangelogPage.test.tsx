import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('@/api/client', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/api/client')>();
  return {
    ...mod,
    listHistory: vi.fn(),
    listRepositories: vi.fn(),
    fetchRepoChanges: vi.fn(),
    hasChangelog: vi.fn(),
    getRecordedRuns: vi.fn(),
    listBranches: vi.fn(),
    getChangelogMeta: vi.fn(),
    getChangelogText: vi.fn(),
    getChangelogRepoText: vi.fn(),
    pushChangelog: vi.fn(),
    resolveReleaseVersion: vi.fn(),
  };
});

/** Mutable so a test can flip the AI catalog state (e.g. a provider whose /models fails)
 *  without rewriting the module mock. Reset in beforeEach. */
const aiState = vi.hoisted(() => ({
  providers: [{ id: 'anthropic', label: 'Anthropic', enabled: true }],
  providerOptions: [{ id: 'anthropic', label: 'Anthropic', enabled: true }],
  provider: 'anthropic',
  setProvider: () => {},
  models: [] as { id: string; label: string }[],
  modelsStatus: 'success' as 'loading' | 'success' | 'error',
  model: undefined as string | undefined,
  setModel: (_?: string) => {},
  isReady: false,
  hasModels: false,
}));

vi.mock('@/hooks/useAiModels', () => ({
  useAiModels: () => aiState,
}));

import {
  fetchRepoChanges,
  getChangelogMeta,
  getChangelogRepoText,
  getChangelogText,
  getRecordedRuns,
  hasChangelog,
  listBranches,
  listHistory,
  listRepositories,
  resolveReleaseVersion,
} from '@/api/client';
import { clearQueryCache } from '@/hooks/useQuery';
import { GenerateChangelogPage } from '@/pages/GenerateChangelogPage';

/** Real prod payloads for Nayyelin-14/Changelog-gen (fetched via the history endpoint). */
const HISTORY = {
  entries: [
    {
      id: 'v-1.0.17',
      project: 'Nayyelin-14',
      repo: 'Changelog-gen',
      version: '1.0.17',
      branch: 'dev',
      source: 'import',
      generated: true,
      timestamp: '2026-10-14T09:54:54.633280Z',
      authors: [] as string[],
      developer: '- fix: per-user data isolation, API access guards, AI provider hardening',
    },
    {
      id: 'v-1.0.15',
      project: 'Nayyelin-14',
      repo: 'Changelog-gen',
      version: '1.0.15',
      branch: 'dev',
      source: 'import',
      generated: true,
      timestamp: '2026-10-14T09:54:53.423718Z',
      authors: [] as string[],
      // Three of the fourteen imported versions have an empty changelog body in prod.
      developer: null as unknown as string,
    },
  ],
  total: 2,
};

const META_WITH_REVISION = {
  source: 'import',
  at: '2026-10-14T09:54:54.633280Z',
  editedBy: null,
  model: null,
  hasPrevious: false,
  hasUnpushedChanges: false,
  previousSource: null,
  previousText: null,
  previousAt: null,
  previousEditedBy: null,
  previousModel: null,
  pushedAt: null,
  pushedText: null,
  pushedPullRequestUrl: null,
  revisions: [
    {
      sequence: 0,
      source: 'import',
      text: '- fix: per-user data isolation, API access guards, AI provider hardening',
      createdAt: '2026-10-14T09:54:54.633280Z',
      model: null,
      editedBy: null,
    },
  ],
};

const META_EMPTY = {
  ...META_WITH_REVISION,
  at: '2026-10-14T09:54:53.423718Z',
  revisions: [
    {
      sequence: 0,
      source: 'import',
      text: '',
      createdAt: '2026-10-14T09:54:53.423718Z',
      model: null,
      editedBy: null,
    },
  ],
};

function stubApis() {
  vi.mocked(listHistory).mockResolvedValue(HISTORY as Awaited<ReturnType<typeof listHistory>>);
  vi.mocked(listRepositories).mockResolvedValue([
    {
      name: 'Changelog-gen',
      visibility: 'public',
      defaultBranch: 'refs/heads/dev',
    },
  ] as Awaited<ReturnType<typeof listRepositories>>);
  vi.mocked(fetchRepoChanges).mockResolvedValue({
    release: { org: null, project: null, repo: null, branch: null, milestone: null, releaseDate: null },
    items: [],
  });
  vi.mocked(hasChangelog).mockResolvedValue(true);
  vi.mocked(getRecordedRuns).mockResolvedValue([]);
  vi.mocked(listBranches).mockResolvedValue([]);
  vi.mocked(getChangelogText).mockResolvedValue(null);
  vi.mocked(getChangelogRepoText).mockResolvedValue(null);
  vi.mocked(resolveReleaseVersion).mockResolvedValue({
    latestVersion: null,
    suggestedNextVersion: null,
    currentBranchSha: null,
    changelogExists: true,
    requiresInitialVersion: false,
  });
  vi.mocked(getChangelogMeta).mockImplementation(async (_p, _r, version) =>
    (version === '1.0.15' ? META_EMPTY : META_WITH_REVISION) as Awaited<
      ReturnType<typeof getChangelogMeta>
    >,
  );
}

function renderHistoryEntry(entryId: string, extraParams = "") {
  const query = extraParams ? `?branch=dev&${extraParams}` : "?branch=dev";
  return render(
    <MemoryRouter
      initialEntries={[
        `/dev/projects/Nayyelin-14/repos/Changelog-gen/history/${encodeURIComponent(entryId)}${query}`,
      ]}
    >
      <Routes>
        <Route
          path="/dev/projects/:project/repos/:repo/history/:entryId"
          element={<GenerateChangelogPage />}
        />
        <Route path="*" element={<p>not-found</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('GenerateChangelogPage — version history detail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearQueryCache();
    localStorage.clear();
    stubApis();
    aiState.modelsStatus = 'success';
    aiState.models = [];
    aiState.model = undefined;
  });

  it('opens the clicked version and renders its Developer text plus its revisions', async () => {
    renderHistoryEntry('v-1.0.17');

    expect(await screen.findByText('v1.0.17')).toBeInTheDocument();
    expect(
      await screen.findByText(/per-user data isolation/),
    ).toBeInTheDocument();
    // Left-hand revision list for this version (one imported snapshot).
    expect(await screen.findByText(/#0 \(current\)/)).toBeInTheDocument();
    expect(screen.queryByText(/couldn't be found/i)).not.toBeInTheDocument();
    expect(screen.queryByText('No content.')).not.toBeInTheDocument();
  });

  it('still opens a version whose imported changelog body is empty', async () => {
    renderHistoryEntry('v-1.0.15');

    expect(await screen.findByText('v1.0.15')).toBeInTheDocument();
    expect(await screen.findByText('No content.')).toBeInTheDocument();
    expect(screen.queryByText(/couldn't be found/i)).not.toBeInTheDocument();
  });

  it('falls back to the ?v= version when the link carries an id this response no longer has', async () => {
    // The dashboard response that produced the link can be minutes stale — the history endpoint
    // used to hand out a different id for the same version depending on which source answered
    // (CHANGELOG.md parse ↔ Postgres), which left the row unresolvable behind
    // "That version couldn't be found."
    renderHistoryEntry('repo-0-2026-10-13-dev (run 53)', 'v=1.0.17');

    expect(await screen.findByText('v1.0.17')).toBeInTheDocument();
    expect(await screen.findByText(/per-user data isolation/)).toBeInTheDocument();
    expect(screen.queryByText(/couldn't be found/i)).not.toBeInTheDocument();
  });

  it('names the provider instead of vanishing when its model catalog fails', async () => {
    // A provider whose /models comes back empty or rejected (prod's Groq) used to hide the
    // whole model+Regen cluster behind `models.status === "success"` — no dropdown, no Regen,
    // and no reason why.
    aiState.modelsStatus = 'error';

    renderHistoryEntry('v-1.0.17');

    expect(await screen.findByText('Models unavailable for Anthropic')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Regen$/ })).toBeDisabled();
  });

  it('shows the two-pane skeleton instead of an empty body while history loads', async () => {
    vi.mocked(listHistory).mockReturnValue(new Promise(() => {}) as ReturnType<typeof listHistory>);

    renderHistoryEntry('v-1.0.17');

    await screen.findByRole('button', { name: /Back to dashboard/i });
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText(/couldn't be found/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Pick a version from the list to see your changelog.')).not.toBeInTheDocument();
  });

  it('disables Push when the imported text already matches the repo', async () => {
    vi.mocked(getChangelogRepoText).mockResolvedValue(HISTORY.entries[0].developer);

    renderHistoryEntry('v-1.0.17');
    fireEvent.click(await screen.findByRole('button', { name: /Push to repo/i }));

    expect(await screen.findByText(/already in dev with this exact text/i)).toBeInTheDocument();
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('button', { name: /^Push$/ })).toBeDisabled();
  });

  it('asks for explicit confirmation before updating an existing repo entry', async () => {
    vi.mocked(getChangelogRepoText).mockResolvedValue('- stale repo text');

    renderHistoryEntry('v-1.0.17');
    fireEvent.click(await screen.findByRole('button', { name: /Push to repo/i }));

    expect(await screen.findByText(/Pushing will update that section/i)).toBeInTheDocument();
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /Update v1.0.17/i }));

    expect(await screen.findByText(/This replaces that section with the text shown below/i)).toBeInTheDocument();
  });
});
