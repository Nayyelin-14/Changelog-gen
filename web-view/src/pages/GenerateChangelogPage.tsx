import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import {
  ArrowLeft,
  ExternalLink,
  FileText,
  FileWarning,
  FolderGit2,
  FolderKanban,
  GitBranch,
  History,
  Loader2,
  Lock,
  Pencil,
  Rocket,
  RotateCcw,
  Search,
  Sparkles,
  Undo2,
  Upload,
} from "lucide-react";

import {
  fetchRepoChanges,
  getChangelogRepoText,
  getRecordedRuns,
  hasChangelog,
  listHistory,
  listRepositories,
  pushChangelog,
} from "@/api/client";
import type { GenerationRecord } from "@/api/types";
import type { HistoryRow } from "@/components/ChangelogEditHistoryPanel";
import { AudienceTabs } from "@/components/AudienceTabs";
import { ChangelogBody } from "@/components/ChangelogBody";
import { ChangelogEditHistoryPanel } from "@/components/ChangelogEditHistoryPanel";
import { ChangelogMetaSpans } from "@/components/ChangelogMetaSpans";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { PipelineRunsTable } from "@/components/PipelineRunsTable";
import { RepoHeaderBar } from "@/components/RepoHeaderBar";
import { RestoreConfirmDialog } from "@/components/RestoreConfirmDialog";
import { ErrorView } from "@/components/StatusView";
import { VersionTable } from "@/components/VersionTable";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useChangelogEditor, type EditableTab } from "@/hooks/useChangelogEditor";
import { useQuery } from "@/hooks/useQuery";
import { isBotAuthor } from "@/lib/bots";
import { DEVELOPER_TAB, formatTimestamp, GENERATED_TABS, sourceLabel } from "@/lib/historyTabs";
import { getStoredProvider } from "@/lib/provider";
import { getStoredRole, roleHome } from "@/lib/role";
import { cn } from "@/lib/utils";

const TABS = [DEVELOPER_TAB, ...GENERATED_TABS];

function shortBranchName(ref: string | null): string | undefined {
  if (!ref) return undefined;
  return ref.startsWith("refs/heads/") ? ref.slice("refs/heads/".length) : ref;
}

export function GenerateChangelogPage() {
  const { project, repo, entryId } = useParams<{
    project: string;
    repo?: string;
    entryId?: string;
  }>();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [buildPage, setBuildPage] = useState(0);
  const isHistoryView = location.pathname.includes("/history");
  const [dashboardSelectedId] = useState<string | undefined>(undefined);
  const effectiveEntryId = isHistoryView ? entryId : dashboardSelectedId;
  const [selectedHistoryRow, setSelectedHistoryRow] = useState<HistoryRow | null>(null);

  const [repoQuery, setRepoQuery] = useState('');
  const [highlightedRepo, setHighlightedRepo] = useState(0);
  const repoInputRef = useRef<HTMLInputElement>(null);

  const repos = useQuery(
    useCallback(() => listRepositories(project!), [project]),
    [project],
    { cacheKey: 'repos' },
  );
  const filteredRepos = useMemo(() => {
    if (repos.status !== 'success') return [];
    return repos.data.filter(r => r.name.toLowerCase().includes(repoQuery.toLowerCase()));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repos.status, repos, repoQuery]);

  useEffect(() => {
    setHighlightedRepo(0);
  }, [repoQuery]);

  // "/" jumps into the repo search from anywhere on this empty state, like GitHub/Linear.
  useEffect(() => {
    if (repo) return;
    function handleKeydown(e: globalThis.KeyboardEvent) {
      if (e.key === '/' && document.activeElement !== repoInputRef.current) {
        e.preventDefault();
        repoInputRef.current?.focus();
      }
    }
    document.addEventListener('keydown', handleKeydown);
    return () => document.removeEventListener('keydown', handleKeydown);
  }, [repo]);

  function handleRepoSearchKeydown(e: KeyboardEvent<HTMLInputElement>) {
    if (filteredRepos.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightedRepo((i) => Math.min(i + 1, filteredRepos.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightedRepo((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const target = filteredRepos[highlightedRepo];
      if (target) navigate(`${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(target.name)}`);
    }
  }

  function highlightMatch(name: string, query: string) {
    if (!query) return name;
    const idx = name.toLowerCase().indexOf(query.toLowerCase());
    if (idx === -1) return name;
    return (
      <>
        {name.slice(0, idx)}
        <span className="text-primary">{name.slice(idx, idx + query.length)}</span>
        {name.slice(idx + query.length)}
      </>
    );
  }

  // No branch selector on this page anymore — falls back to the repo's own default branch (not
  // every repo has one literally named "dev" — e.g. hubsabai-sdk defaults to "test") so every
  // Azure DevOps call below doesn't 404 for those repos. Push is still the one place a real
  // branch gets chosen.
  const currentRepoInfo = repos.status === "success" ? repos.data.find((r) => r.name === repo) : undefined;
  const branchParam = searchParams.get("branch") ?? undefined;
  const selectedBranch = branchParam ?? shortBranchName(currentRepoInfo?.defaultBranch ?? null) ?? "dev";

  const loadSummary = useCallback(async () => {
    if (!project || !repo) throw new Error('repo-not-ready');
    const changes = await fetchRepoChanges(
      project,
      repo,
      undefined,
      undefined,
      selectedBranch,
    );
    const contributors = new Set(
      changes.items
        .map((item) => item.author)
        .filter((author): author is string => Boolean(author))
        .filter((author) => !isBotAuthor(author)),
    );
    return {
      commits: changes.items.filter((item) => item.type === "COMMIT").length,
      pullRequests: changes.items.filter((item) => item.type === "PULL_REQUEST")
        .length,
      contributors: contributors.size,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project, repo, selectedBranch]);
  const summary = useQuery(loadSummary, [project, repo, selectedBranch], { cacheKey: 'summary' });

  const loadHasChangelog = useCallback(async () => {
    if (!project || !repo) throw new Error('repo-not-ready');
    return hasChangelog(project, repo, selectedBranch);
  }, [project, repo, selectedBranch]);
  const changelogStatus = useQuery(
    loadHasChangelog,
    [project, repo, selectedBranch],
    { cacheKey: 'changelog-status' },
  );

  const loadHistory = useCallback(async () => {
    if (!project || !repo) throw new Error('repo-not-ready');
    if (isHistoryView) {
      return listHistory(project, repo, selectedBranch, page, 20);
    }
    return listHistory(project, repo, selectedBranch, 0, 9999);
  }, [project, repo, selectedBranch, page, isHistoryView]);
  const history = useQuery(
    loadHistory,
    // isHistoryView must be its own dep, not just fold into the page number — the dashboard
    // (isHistoryView false) fetches all entries in one unpaginated page while the history-detail
    // view (isHistoryView true) fetches a real page of 20; both landing on page 0 would otherwise
    // collide on the same cache entry despite being two differently-shaped queries.
    [project, repo, selectedBranch, isHistoryView, isHistoryView ? page : 0],
    { cacheKey: 'history' },
  );
  const data = history.status === "success" ? history.data.entries : [];
  const selectedEntry = effectiveEntryId ? data.find((e) => e.id === effectiveEntryId) : undefined;

  // Recorded pipeline runs for the dashboard's "Pipeline runs" table — uses stored run data
  // as primary source, with live provider fetch as fallback. This eliminates duplicate
  // provider API calls when the pipeline has already reported the run.
  const builds = useQuery(
    useCallback(() => (project && repo ? getRecordedRuns(project, repo, getStoredProvider()) : Promise.resolve([])), [project, repo]),
    [project, repo],
    { cacheKey: 'builds' },
  );

  // Keep the last-known entry for the selected id so the detail pane doesn't go blank when
  // paging the sidebar to a page that doesn't contain it — same fallback QA/Business rely on.
  const entryCacheRef = useRef<GenerationRecord | undefined>(undefined);
  if (selectedEntry) {
    entryCacheRef.current = selectedEntry;
  }
  const displayEntry = effectiveEntryId ? (selectedEntry ?? entryCacheRef.current) : undefined;

  const base = roleHome(getStoredRole() ?? "dev");

  function entryHref(entry: GenerationRecord) {
    const params = new URLSearchParams();
    if (selectedBranch) params.set("branch", selectedBranch);
    // Not generated yet (a merged PR with no changelog) — nothing to view, so go straight to the
    // generate flow instead of a history entry that doesn't exist. entry.id is "pr-<id>" for
    // these (see AzureDevOpsResource#buildUngeneratedEntries) — pass the PR id through so the
    // generate page can pre-fill that PR's real title/commits instead of starting blank.
    if (entry.generated === false) {
      const prId = entry.id.startsWith("pr-") ? entry.id.slice("pr-".length) : undefined;
      if (prId) params.set("prId", prId);
      const query = params.toString();
      return `${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo!)}/generate${query ? `?${query}` : ""}`;
    }
    // A saved-but-unversioned draft (the Save action's artifact) is keyed on its pipeline run
    // ("run-<buildId>") — no history detail exists for it yet, so go to the generate page for
    // that run to review/push instead of a version-detail page.
    if (entry.id.startsWith("run-")) {
      const buildId = entry.id.slice("run-".length);
      if (buildId) params.set("buildId", buildId);
      const query = params.toString();
      return `${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo!)}/generate${query ? `?${query}` : ""}`;
    }
    const query = params.toString();
    return `${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo!)}/history/${encodeURIComponent(entry.id)}${query ? `?${query}` : ""}`;
  }

  function buildHref(buildId: number, runNumber?: string | null) {
    const params = new URLSearchParams();
    if (selectedBranch) params.set("branch", selectedBranch);
    params.set("buildId", String(buildId));
    // The pipeline run number (e.g. GitHub run #9, Azure build #9) — passed as a separate param
    // so the generate page can display it as "Pipeline run number: #9" rather than confusing it
    // with the semantic release version (e.g. "1.4.30").
    if (runNumber) params.set("runNumber", runNumber);
    return `${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo!)}/generate?${params.toString()}`;
  }

  useEffect(() => {
    setPage(0);
    setBuildPage(0);
    entryCacheRef.current = undefined;
  }, [project, repo, selectedBranch]);

  // Pipeline-runs table paginates client-side too — the backend endpoint only supports "give me
  // the N most recent runs", no server-side page/skip.
  const BUILD_PAGE_SIZE = 10;
  const buildRuns = builds.status === "success" ? builds.data : [];
  const buildTotalPages = Math.max(1, Math.ceil(buildRuns.length / BUILD_PAGE_SIZE));
  const buildCurrentPage = Math.min(buildPage, buildTotalPages - 1);
  const pagedBuildRuns = buildRuns.slice(
    buildCurrentPage * BUILD_PAGE_SIZE,
    buildCurrentPage * BUILD_PAGE_SIZE + BUILD_PAGE_SIZE,
  );

  // Auto-select first real entry on the history page when no entryId is picked yet.
  // Deliberately scoped to isHistoryView so the dashboard never redirects away.
  useEffect(() => {
    if (isHistoryView && !entryId && history.status === "success" && data.length > 0 && repo) {
      const first = data.find((e) => e.generated !== false && e.version) ?? data[0];
      navigate(entryHref(first), { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isHistoryView, history.status, repo]);

  const editor = useChangelogEditor(project, repo, displayEntry);
  const {
    activeTab,
    setActiveTab,
    model,
    setModel,
    models,
    developerOverride,
    generated,
    checked,
    meta,
    generating,
    genError,
    setGenError,
    generateConfirm,
    confirmingGenerate,
    editingTab,
    editText,
    setEditText,
    editSaving,
    editError,
    saveConfirmingTab,
    restoringPushed,
    restorePushedError,
    restorePushedConfirmingTab,
    mutationCount,
    restoringRevision,
    restoreRevisionError,
    restoreRevisionConfirmingTab,
    requestRestoreRevision,
    cancelRestoreRevision,
    handleRestoreRevision,
    requestGenerate,
    cancelGenerateConfirm,
    handleGenerate,
    startEdit,
    cancelEdit,
    requestSaveEdit,
    cancelSaveConfirm,
    handleSaveEdit,
    clearMeta,
    requestRestorePushed,
    cancelRestorePushed,
    handleRestorePushed,
  } = editor;

  // Push-to-repo is Dev-only — separate from Save (opens a real repo PR, not a Postgres write).
  // Keyed by entryId so switching versions doesn't lose a just-opened PR link.
  const [pushConfirmingEntry, setPushConfirmingEntry] = useState<string | null>(null);
  const [pushRepoText, setPushRepoText] = useState("");
  const [pushRepoTextLoading, setPushRepoTextLoading] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [pushError, setPushError] = useState<string | null>(null);
  const [pushResultByEntry, setPushResultByEntry] = useState<Record<string, string>>({});
  const pushResult = effectiveEntryId ? pushResultByEntry[effectiveEntryId] : undefined;

  useEffect(() => {
    setPushConfirmingEntry(null);
    setPushError(null);
    setSelectedHistoryRow(null);
  }, [effectiveEntryId]);

  useEffect(() => {
    if (mutationCount > 0) setSelectedHistoryRow(null);
  }, [mutationCount]);

  /** Fetches the repo's actual current CHANGELOG.md entry for this version before opening the
   * confirm dialog, so it can diff against the real file instead of asking "are you sure" with
   * nothing to compare — same reasoning as Generate/Regenerate calling the AI before their own
   * confirm dialog opens. */
  async function requestPush(entry: GenerationRecord) {
    if (!project || !repo || !entry.branch) return;
    setPushError(null);
    setPushRepoTextLoading(true);
    try {
      const repoText = await getChangelogRepoText(project, repo, entry.version ?? "", entry.branch);
      setPushRepoText(repoText ?? "");
      setPushConfirmingEntry(entry.id);
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to load the repo's current changelog.";
      toast.error("Failed to check the repo's current changelog", { description: message });
    } finally {
      setPushRepoTextLoading(false);
    }
  }

  async function handlePush(entry: GenerationRecord) {
    if (!project || !repo || !entry.version || !entry.branch) return;
    setPushing(true);
    setPushError(null);
    try {
      const res = await pushChangelog(project, repo, entry.version, entry.branch, "developer");
      setPushResultByEntry((prev) => ({ ...prev, [entry.id]: res.commitUrl }));
      setPushConfirmingEntry(null);
      clearMeta("developer");
      toast.success(`Pushed v${entry.version} to ${entry.branch}`, {
        description: `Committed directly — no PR needed.`,
        action: { label: "View commit", onClick: () => window.open(res.commitUrl, "_blank", "noreferrer") },
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to push to repo.";
      setPushError(message);
      toast.error(`Failed to push v${entry.version} to the repo`, { description: message });
    } finally {
      setPushing(false);
    }
  }

  const activeTabConfig =
    TABS.find((tab) => tab.key === activeTab) ?? DEVELOPER_TAB;

  /** The active tab's own snapshot within the selected revision, if one is selected — a revision
   * is a snapshot of all three audiences at once, so this is just a lookup, never a mismatch: every
   * tab finds its own state within the SAME selected row (or has nothing there if that audience
   * hadn't been generated yet as of that revision). */
  const selectedSnapshot = selectedHistoryRow?.[activeTab];
  /** When a historical revision is selected, show its text instead of the current version's. */
  const displayedText: string | undefined = selectedSnapshot?.text;
  /** A revision is selected, but this audience simply didn't exist yet as of that point — nothing
   * to view or restore for this tab from here. */
  const noSnapshotYet = !!selectedHistoryRow && !selectedSnapshot;

  /** What's currently shown for a tab, independent of which tab is active — used to diff against
   * a restore/save target, since the restoring/saving tab isn't always the one the confirm
   * dialogs are rendered for by the time this runs. */
  function currentTextForTab(tab: string | null): string | undefined {
    if (!tab || !displayEntry) return undefined;
    const snap = selectedHistoryRow?.[tab as EditableTab];
    if (snap) return snap.text;
    if (tab === "developer") return developerOverride ?? displayEntry.developer;
    return generated[tab as keyof typeof generated]?.text;
  }

  if (!project) return null;

  return (
    <div className="flex h-full flex-1 min-h-0 flex-col">
      {/* -- No repo selected: centered repo picker -- */}
      {!repo ? (
        <div className="flex flex-col items-center justify-center gap-6 py-10 lg:min-h-[calc(100vh-14rem)] lg:py-0">
          {repos.status === 'loading' && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" />
              Loading repositories…
            </div>
          )}

          {repos.status === 'error' && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
                <FileWarning className="size-6 text-muted-foreground" />
                <p className="text-sm font-medium">Failed to load repositories</p>
                <p className="text-xs text-muted-foreground">{repos.error.message}</p>
              </CardContent>
            </Card>
          )}

          {repos.status === 'success' && repos.data.length === 0 && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 py-8 text-center">
                <FolderGit2 className="size-6 text-muted-foreground" />
                <p className="text-sm font-medium">No repositories</p>
                <p className="text-xs text-muted-foreground">
                  This project doesn't have any repositories yet.
                </p>
              </CardContent>
            </Card>
          )}

          {repos.status === 'success' && repos.data.length > 0 && (
            <div className="w-full max-w-xl space-y-5">
              <div className="flex flex-col items-center gap-3 text-center">
                <div className="flex size-12 items-center justify-center rounded-2xl bg-gradient-to-br from-primary/20 to-primary/5 shadow-inner">
                  <FolderKanban className="size-5 text-primary/70" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold tracking-tight">{project}</h2>
                  <p className="text-xs text-muted-foreground">
                    Jump to a repository — {repos.data.length} available
                  </p>
                </div>
              </div>

              <div className="relative">
                <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  ref={repoInputRef}
                  type="text"
                  value={repoQuery}
                  onChange={(e) => setRepoQuery(e.target.value)}
                  onKeyDown={handleRepoSearchKeydown}
                  placeholder="Type a repo name…"
                  autoFocus
                  className="w-full h-12 rounded-2xl border border-border/60 bg-card pl-11 pr-10 text-sm outline-none transition-colors placeholder:text-muted-foreground/50 focus:border-primary/40 focus:ring-1 focus:ring-ring"
                />
                {repoQuery.length === 0 && (
                  <kbd className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 rounded-md border border-border/60 bg-muted/60 px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
                    /
                  </kbd>
                )}
              </div>

              <div className="overflow-hidden rounded-2xl border border-border/60 bg-card">
                {filteredRepos.length === 0 ? (
                  <p className="px-4 py-8 text-center text-xs text-muted-foreground">
                    No repositories matching "{repoQuery}"
                  </p>
                ) : (
                  <ul className="max-h-80 divide-y divide-border/40 overflow-y-auto">
                    {filteredRepos.map((r, i) => (
                      <li key={r.id}>
                        <button
                          type="button"
                          onMouseEnter={() => setHighlightedRepo(i)}
                          onClick={() =>
                            navigate(`${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(r.name)}`)
                          }
                          className={cn(
                            "flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm transition-colors",
                            i === highlightedRepo ? "bg-muted/60" : "hover:bg-muted/40",
                          )}
                        >
                          <FolderGit2 className="size-4 shrink-0 text-muted-foreground" />
                          <span className="min-w-0 flex-1 truncate">{highlightMatch(r.name, repoQuery)}</span>
                          {r.visibility && (
                            <span
                              className={cn(
                                "flex shrink-0 items-center gap-1 rounded-full border px-1.5 py-0.5 text-[10px] font-medium",
                                r.visibility === "private"
                                  ? "border-border/60 text-muted-foreground"
                                  : "border-primary/30 text-primary",
                              )}
                            >
                              <Lock className="size-2.5" />
                              {r.visibility === "private" ? "Private" : "Public"}
                            </span>
                          )}
                          {r.defaultBranch && (
                            <span className="flex shrink-0 items-center gap-1 text-[11px] text-muted-foreground/70">
                              <GitBranch className="size-3" />
                              {shortBranchName(r.defaultBranch)}
                            </span>
                          )}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}
        </div>
      ) : isHistoryView ? (
        /* -- History view: version table + detail pane (at /history) -- */
        <div className="flex flex-1 min-h-0 flex-col gap-3">
          {repo && (
            <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 rounded-lg border border-border/50 bg-card px-3 py-2 shadow-xs">
              <RepoHeaderBar
                repo={repo}
                repos={repos}
                onRepoChange={(value) =>
                  navigate(`${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(value)}`)
                }
                changelogStatus={changelogStatus}
                selectedBranch={selectedBranch}
                summary={summary}
              />
              <Button
                size="sm"
                variant="outline"
                className="shrink-0 gap-1.5"
                onClick={() => navigate(`${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo)}`)}
              >
                <ArrowLeft className="size-3.5" />
                Back to dashboard
              </Button>
            </div>
          )}

          {(history.status === "loading" || (history.status === "error" && history.error.message === 'repo-not-ready')) && (
            <div className="grid flex-1 min-h-0 items-stretch gap-4 lg:grid-cols-[17rem_1fr]">
              <Skeleton className="h-48 w-full rounded-xl lg:h-full" />
              <Skeleton className="h-48 w-full rounded-xl lg:h-full" />
            </div>
          )}
          {history.status === "error" && history.error.message !== 'repo-not-ready' && (
            <Card>
              <CardContent className="p-6">
                <ErrorView message={history.error.message} />
              </CardContent>
            </Card>
          )}
          {history.status === "success" && data.filter((e) => e.generated !== false).length === 0 && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 py-16 text-center text-sm text-muted-foreground">
                <FileText className="size-6 text-muted-foreground/50" />
                No version history for this repo.
              </CardContent>
            </Card>
          )}
          {history.status === "success" && data.filter((e) => e.generated !== false).length > 0 && (
            <div className="grid flex-1 min-h-0 items-stretch gap-4 lg:grid-cols-[26rem_1fr]">
              <div className="flex min-h-0 flex-col gap-3">
                {displayEntry && repo && (
                  <ChangelogEditHistoryPanel
                    project={project}
                    repo={repo}
                    version={displayEntry.version ?? ""}
                    branch={displayEntry.branch ?? undefined}
                    selectedKey={selectedHistoryRow?.key ?? null}
                    // Selecting the current revision itself just clears back to the live,
                    // fully-editable view — it's not a "snapshot" to browse read-only, it's what
                    // Edit/Generate/Regen already act on.
                    onSelect={(row) => setSelectedHistoryRow(row.isLatest ? null : row)}
                    onDelete={(row) => {
                      if (selectedHistoryRow?.key === row.key) setSelectedHistoryRow(null);
                    }}
                    refreshToken={mutationCount}
                  />
                )}
              </div>

              <div className="flex min-h-0 min-w-0 flex-col overflow-hidden rounded-lg bg-card shadow-xs">
                {!displayEntry && (
                  <div className="flex flex-1 min-h-48 flex-col items-center justify-center gap-2 p-5 text-center md:p-6">
                    <div className="flex size-8 items-center justify-center rounded-lg bg-muted/50">
                      <FileText className="size-4 text-muted-foreground/40" />
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {effectiveEntryId
                        ? "That version couldn't be found."
                        : "Pick a version from the list to see its changelog."}
                    </p>
                  </div>
                )}

                {displayEntry && (
                  <div className="flex flex-1 flex-col overflow-hidden">
                    <div className="shrink-0 space-y-3 px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        {displayEntry.generated === false ? (
                          <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-500/15 px-2.5 py-1 text-xs font-semibold text-amber-600 dark:text-amber-400">
                            <span className="size-1.5 rounded-full bg-amber-500 animate-pulse" />
                            Not generated
                          </span>
                        ) : sourceLabel(displayEntry.source) ? (
                          <span className={cn("inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold", sourceLabel(displayEntry.source)!.className)}>
                            {sourceLabel(displayEntry.source)!.label}
                          </span>
                        ) : null}
                        {displayEntry.branch && (
                          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                            <GitBranch className="size-3" />
                            {displayEntry.branch}
                          </span>
                        )}
                        {displayEntry.id.startsWith("pr-") && (
                          <span className="inline-flex items-center gap-1 rounded-md bg-primary/5 px-2 py-0.5 text-xs font-medium text-primary">
                            PR #{displayEntry.id.replace("pr-", "")}
                          </span>
                        )}
                        <span className="text-xs text-muted-foreground/70">{formatTimestamp(displayEntry.timestamp)}</span>
                      </div>

                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3 min-w-0">
                          <h3 className="font-mono text-base font-bold text-foreground shrink-0">
                            v{displayEntry.version ?? "?"}
                          </h3>
                          {displayEntry.authors && displayEntry.authors.length > 0 && (
                            <span className="truncate text-xs text-muted-foreground">
                              by {displayEntry.authors.join(", ")}
                            </span>
                          )}
                        </div>
                      </div>

                      <div>
                        <AudienceTabs
                          tabs={TABS}
                          activeTab={activeTab}
                          onChange={(key) => {
                            setActiveTab(key as typeof activeTab);
                            setGenError(null);
                          }}
                          generated={generated}
                        />
                      </div>

                    </div>

                    <div className="mx-4 h-px bg-border/10" />

                    <div className="flex-1 overflow-y-auto px-4 py-4">
                      {editingTab === activeTab && (
                        <div className="space-y-3">
                          <Textarea
                            value={editText}
                            onChange={(e) => setEditText(e.target.value)}
                            disabled={editSaving}
                            className="h-72 w-full resize-y font-mono text-xs"
                            autoFocus
                          />
                          {editError && (
                            <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                              {editError}
                            </div>
                          )}
                        </div>
                      )}

                      {editingTab !== activeTab && noSnapshotYet && (
                        <div className="flex min-h-32 flex-col items-center justify-center gap-2 text-center text-xs text-muted-foreground">
                          <History className="size-5 text-muted-foreground/30" />
                          {activeTabConfig.label} hadn't been generated yet as of revision #{selectedHistoryRow!.sequence}.
                        </div>
                      )}

                      {editingTab !== activeTab && !noSnapshotYet && activeTab === "developer" && (
                        <div className="space-y-4">
                          {pushResult && pushConfirmingEntry !== displayEntry.id && (
                            <a
                              href={pushResult}
                              target="_blank"
                              rel="noreferrer"
                              className="inline-flex items-center gap-1.5 rounded-lg border border-success/30 bg-success/5 px-4 py-3 text-sm text-success hover:underline"
                            >
                              <ExternalLink className="size-3.5 shrink-0" />
                              Pushed — view the commit on Azure DevOps
                            </a>
                          )}
                          <ChangelogBody text={displayedText ?? developerOverride ?? displayEntry.developer} />
                        </div>
                      )}

                      {editingTab !== activeTab && !noSnapshotYet && activeTab !== "developer" && !generated[activeTab] && !checked[activeTab] && (
                        <div className="flex min-h-32 items-center justify-center gap-2 text-xs text-muted-foreground">
                          <Loader2 className="size-3.5 animate-spin" />
                          Checking for an existing {activeTabConfig.label.toLowerCase()} summary…
                        </div>
                      )}

                      {editingTab !== activeTab && !noSnapshotYet && activeTab !== "developer" && !generated[activeTab] && checked[activeTab] && (
                        <div className="space-y-4">
                          {genError && (
                            <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                              {genError}
                            </div>
                          )}
                          <div className="rounded-lg border border-dashed border-border/20 px-5 py-8">
                            <div className="flex flex-col items-center gap-3 text-center">
                              <Sparkles className="size-5 text-primary/60" />
                              <div>
                                <p className="text-sm font-medium text-foreground">
                                  No {activeTabConfig.label} summary yet
                                </p>
                                <p className="mt-1 text-xs text-muted-foreground">
                                  Generate an AI summary for v{displayEntry.version ?? "?"} — it only takes a few seconds.
                                </p>
                              </div>
                              <div className="flex items-center gap-2">
                                <Select value={model} onValueChange={setModel} disabled={generating !== null || !!selectedHistoryRow}>
                                  <SelectTrigger className="h-8 w-fit text-xs">
                                    <SelectValue placeholder="Select a model…">
                                      {models.status === "success"
                                        ? models.data.find((m) => m.id === model)?.label
                                        : undefined}
                                    </SelectValue>
                                  </SelectTrigger>
                                  <SelectContent className="min-w-60" side="bottom" align="start">
                                    {models.status === "success" &&
                                      models.data.map((m) => (
                                        <SelectItem key={m.id} value={m.id} className="pr-8">
                                          <span className="flex min-w-0 items-center gap-2">
                                            <span className="min-w-0 truncate">{m.label}</span>
                                            {m.recommended && (
                                              <Badge variant="outline" className="shrink-0 text-[10px] leading-none px-1.5 py-0 text-amber-500 border-amber-500/40">
                                                Recommended
                                              </Badge>
                                            )}
                                          </span>
                                        </SelectItem>
                                      ))}
                                  </SelectContent>
                                </Select>
                                {/* Generating mutates the CURRENT text — blocked while browsing any
                                    past revision (whichever audience it belongs to), since that's a
                                    "look, don't touch" mode until you go back to current. */}
                                <Button size="sm" className="gap-1.5" onClick={() => requestGenerate(activeTab)} disabled={!model || generating !== null || !!selectedHistoryRow}
                                  title={selectedHistoryRow ? "Go back to current to generate" : undefined}>
                                  {generating === activeTab ? (
                                    <><Loader2 className="size-3.5 animate-spin" /> Generating…</>
                                  ) : (
                                    "Generate"
                                  )}
                                </Button>
                              </div>
                            </div>
                          </div>
                        </div>
                      )}

                      {editingTab !== activeTab && !noSnapshotYet && activeTab !== "developer" && generated[activeTab] && (
                        <div className="space-y-4">
                          {genError && (
                            <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                              {genError}
                            </div>
                          )}
                          <ChangelogBody text={displayedText ?? generated[activeTab]!.text} />
                        </div>
                      )}
                    </div>

                    {editingTab === activeTab ? (
                      <div className="flex shrink-0 justify-end gap-2 px-4 py-2.5">
                        <Button size="sm" variant="ghost" onClick={cancelEdit} disabled={editSaving}>Cancel</Button>
                        <Button size="sm" className="gap-1.5" onClick={() => requestSaveEdit(activeTab)} disabled={editSaving || !editText.trim()}>
                          {editSaving ? <><Loader2 className="size-3.5 animate-spin" /> Saving…</> : "Save"}
                        </Button>
                      </div>
                    ) : noSnapshotYet ? (
                      // A revision is selected, but this audience has no snapshot at that point
                      // (it hadn't been generated yet as of #{sequence}) — nothing to view/restore
                      // for this tab from here. Checked as its own branch (not ANDed into the
                      // developer check below) so TypeScript can still narrow activeTab !==
                      // "developer" for the generated[] lookup further down.
                      <div className="flex shrink-0 items-center gap-2 border-t border-border/20 px-4 py-3 text-xs text-muted-foreground">
                        Nothing to restore here — {activeTabConfig.label} hadn't been generated yet as of revision #{selectedHistoryRow!.sequence}.
                      </div>
                    ) : selectedSnapshot ? (
                      // Viewing an older revision (not the current text) — only offer to restore
                      // it, with attribution for THIS revision specifically, not whatever's
                      // current. Edit/Regen/Push all act on the live current text, which isn't
                      // what's on screen right now, so they don't belong here. A revision is a
                      // full snapshot across all three audiences, so this same branch covers
                      // Developer/QA/Business alike — no per-audience duplication needed.
                      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border/20 px-4 py-3">
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          {selectedSnapshot.source === "edit" ? (
                            <span>Edited by {selectedSnapshot.editedBy ?? "someone"} · {formatTimestamp(selectedHistoryRow!.at)}</span>
                          ) : selectedSnapshot.model ? (
                            <span>Generated with {selectedSnapshot.model} · {formatTimestamp(selectedHistoryRow!.at)}</span>
                          ) : selectedSnapshot.source === "raw" ? (
                            <span>Pipeline import · {formatTimestamp(selectedHistoryRow!.at)}</span>
                          ) : (
                            <span>{formatTimestamp(selectedHistoryRow!.at)}</span>
                          )}
                        </div>
                        <Button size="sm" variant="ghost" className="gap-1.5 text-muted-foreground"
                          onClick={() => requestRestoreRevision(activeTab, selectedHistoryRow!.sequence)}>
                          <RotateCcw className="size-3" /> Restore this revision
                        </Button>
                      </div>
                    ) : activeTab === "developer" ? (
                      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border/20 px-4 py-3">
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          {meta.developer && <ChangelogMetaSpans meta={meta.developer} />}
                          {!meta.developer?.hasUnpushedChanges && meta.developer?.pushedAt && (
                            meta.developer.pushedPullRequestUrl ? (
                              <a href={meta.developer.pushedPullRequestUrl} target="_blank" rel="noreferrer" className="flex items-center gap-1 hover:underline">
                                Pushed {formatTimestamp(meta.developer.pushedAt)}
                              </a>
                            ) : (
                              <span>Pushed {formatTimestamp(meta.developer.pushedAt)}</span>
                            )
                          )}
                        </div>
                        <div className="flex flex-wrap items-center gap-2">
                          <div className="flex items-center gap-1">
                            {meta.developer?.hasUnpushedChanges && meta.developer?.pushedAt && (
                              <Button size="sm" variant="ghost" className="gap-1.5 text-muted-foreground" onClick={() => requestRestorePushed("developer")}>
                                <Undo2 className="size-3" /> Undo
                              </Button>
                            )}
                            <Button size="sm" variant="outline" className="gap-1.5 bg-sky-50 text-sky-700 border-sky-200 hover:bg-sky-100 dark:bg-sky-950 dark:text-sky-400 dark:border-sky-800 dark:hover:bg-sky-900"
                              onClick={() => startEdit("developer", displayedText ?? developerOverride ?? displayEntry.developer)}>
                              <Pencil className="size-3" /> Edit
                            </Button>
                          </div>
                          {models.status === "success" && (
                            <div className="flex items-center gap-1">
                              <Select value={model} onValueChange={setModel} disabled={generating !== null || !!selectedHistoryRow}>
                                <SelectTrigger className="h-7 w-fit gap-1.5 text-xs font-medium px-2.5">
                                  <SelectValue placeholder="Model">
                                    {models.data.find((m) => m.id === model)?.label}
                                  </SelectValue>
                                </SelectTrigger>
                                <SelectContent side="bottom" align="end">
                                  {models.data.map((m) => (
                                    <SelectItem key={m.id} value={m.id} className="pr-8 text-xs">
                                      <span className="flex min-w-0 items-center gap-2">
                                        <span className="min-w-0 truncate">{m.label}</span>
                                        {m.recommended && (
                                          <Badge variant="outline" className="shrink-0 text-[9px] leading-none px-1 py-0 text-amber-500 border-amber-500/40">Recommended</Badge>
                                        )}
                                      </span>
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                              {/* Blocked while browsing any past revision, even one belonging to
                                  a DIFFERENT audience than this tab — selectedHistoryRow doesn't
                                  clear on a tab switch, so without this check Regen would still
                                  fire here while, say, an old QA snapshot is still selected. */}
                              <Button size="sm" variant="outline" className="gap-1.5 bg-amber-50 text-amber-700 border-amber-200 hover:bg-amber-100 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-800 dark:hover:bg-amber-900"
                                onClick={() => requestGenerate("developer", true)} disabled={generating !== null || !model || !!selectedHistoryRow}
                                title={selectedHistoryRow ? "Go back to current to regenerate" : undefined}>
                                {generating === "developer" ? <><Loader2 className="size-3 animate-spin" /> Regen…</> : "Regen"}
                              </Button>
                            </div>
                          )}
                          {displayEntry.branch && meta.developer?.hasUnpushedChanges && (
                            <Button size="sm" variant="default" className="gap-1.5" onClick={() => requestPush(displayEntry)} disabled={pushRepoTextLoading}>
                              {pushRepoTextLoading ? <Loader2 className="size-3 animate-spin" /> : <Upload className="size-3" />}
                              Push
                            </Button>
                          )}
                        </div>
                      </div>
                    ) : (
                      generated[activeTab] && (
                        <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border/20 px-4 py-3">
                          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                            <ChangelogMetaSpans meta={meta[activeTab]} />
                          </div>
                          <div className="flex flex-wrap items-center gap-2">
                            <div className="flex items-center gap-1">
                              <Button size="sm" variant="outline" className="gap-1.5 bg-sky-50 text-sky-700 border-sky-200 hover:bg-sky-100 dark:bg-sky-950 dark:text-sky-400 dark:border-sky-800 dark:hover:bg-sky-900"
                                onClick={() => startEdit(activeTab, displayedText ?? generated[activeTab]!.text)}>
                                <Pencil className="size-3" /> Edit
                              </Button>
                            </div>
                            {models.status === "success" && (
                              <div className="flex items-center gap-1">
                                <Select value={model} onValueChange={setModel} disabled={generating !== null || !!selectedHistoryRow}>
                                  <SelectTrigger className="h-7 w-fit gap-1.5 text-xs font-medium px-2.5">
                                    <SelectValue placeholder="Model">
                                      {models.data.find((m) => m.id === model)?.label}
                                    </SelectValue>
                                  </SelectTrigger>
                                  <SelectContent side="bottom" align="end">
                                    {models.data.map((m) => (
                                      <SelectItem key={m.id} value={m.id} className="pr-8 text-xs">
                                        <span className="flex min-w-0 items-center gap-2">
                                          <span className="min-w-0 truncate">{m.label}</span>
                                          {m.recommended && (
                                            <Badge variant="outline" className="shrink-0 text-[9px] leading-none px-1 py-0 text-amber-500 border-amber-500/40">Recommended</Badge>
                                          )}
                                        </span>
                                      </SelectItem>
                                    ))}
                                  </SelectContent>
                                </Select>
                                {/* Same cross-tab guard as Developer's Regen above — blocked while
                                    ANY audience's past revision is selected, not just this tab's. */}
                                <Button size="sm" variant="outline" className="gap-1.5 bg-amber-50 text-amber-700 border-amber-200 hover:bg-amber-100 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-800 dark:hover:bg-amber-900"
                                  onClick={() => requestGenerate(activeTab, true)} disabled={generating !== null || !model || !!selectedHistoryRow}
                                  title={selectedHistoryRow ? "Go back to current to regenerate" : undefined}>
                                  {generating === activeTab ? <><Loader2 className="size-3 animate-spin" /> Regen…</> : "Regen"}
                                </Button>
                              </div>
                            )}
                          </div>
                        </div>
                      )
                    )}

                    <ConfirmDialog open={generateConfirm !== null}
                      title={`Save this ${TABS.find((t) => t.key === generateConfirm?.tab)?.label ?? ""} changelog?`}
                      description={generateConfirm?.force
                        ? `This is the new AI generation, reviewed below. Confirming replaces the current v${displayEntry.version ?? "?"} text — what's there now becomes the new "previous," so you can restore it afterward. Nothing has been saved yet.`
                        : `This is the AI-generated summary for v${displayEntry.version ?? "?"}, reviewed below. Nothing has been saved yet — confirming writes it to the database.`}
                      diff={generateConfirm ? { before: currentTextForTab(generateConfirm.tab) ?? "", after: generateConfirm.text } : undefined}
                      confirmLabel="Confirm & save" pendingLabel="Saving…" loading={confirmingGenerate} error={genError}
                      onConfirm={handleGenerate} onCancel={cancelGenerateConfirm} />

                    <ConfirmDialog open={saveConfirmingTab !== null}
                      title={`Save ${saveConfirmingTab ? TABS.find((t) => t.key === saveConfirmingTab)?.label : ""} edit?`}
                      description={`This saves your edit as the current v${displayEntry.version ?? "?"} text. What's there now becomes the new "previous" — you can restore it afterward.`}
                      diff={{ before: currentTextForTab(saveConfirmingTab) ?? "", after: editText }}
                      confirmLabel="Save" pendingLabel="Saving…" loading={editSaving} error={editError}
                      onConfirm={() => saveConfirmingTab && handleSaveEdit(saveConfirmingTab)} onCancel={cancelSaveConfirm} />

                    <ConfirmDialog open={pushConfirmingEntry === displayEntry.id}
                      title={`Push v${displayEntry.version ?? "?"} to the repo?`}
                      description={`This commits directly to ${displayEntry.branch ?? "?"} — no PR — replacing v${displayEntry.version ?? "?"}'s Developer entry in CHANGELOG.md with the text shown below.`}
                      diff={{ before: pushRepoText, after: developerOverride ?? displayEntry.developer }}
                      confirmLabel="Push" pendingLabel="Pushing…" loading={pushing} error={pushError}
                      onConfirm={() => handlePush(displayEntry)} onCancel={() => { setPushConfirmingEntry(null); setPushError(null); }} />

                    <RestoreConfirmDialog open={restorePushedConfirmingTab !== null}
                      tabLabel={restorePushedConfirmingTab ? (TABS.find((t) => t.key === restorePushedConfirmingTab)?.label ?? "") : ""}
                      version={displayEntry.version} currentText={currentTextForTab(restorePushedConfirmingTab)}
                      previousText={meta.developer?.pushedText} restoring={restoringPushed} restoreError={restorePushedError}
                      onConfirm={() => restorePushedConfirmingTab && handleRestorePushed(restorePushedConfirmingTab)} onCancel={cancelRestorePushed}
                      title="Restore last pushed version?"
                      description={`This replaces the current v${displayEntry.version ?? "?"} Developer text with whatever was last pushed to the repo.`} />

                    <RestoreConfirmDialog open={restoreRevisionConfirmingTab !== null}
                      tabLabel={restoreRevisionConfirmingTab ? (TABS.find((t) => t.key === restoreRevisionConfirmingTab)?.label ?? "") : ""}
                      version={displayEntry.version} currentText={currentTextForTab(restoreRevisionConfirmingTab)}
                      previousText={restoreRevisionConfirmingTab ? selectedHistoryRow?.[restoreRevisionConfirmingTab]?.text : undefined}
                      restoring={restoringRevision} restoreError={restoreRevisionError}
                      onConfirm={handleRestoreRevision} onCancel={cancelRestoreRevision}
                      title="Restore this revision?"
                      description={`This replaces the current v${displayEntry.version ?? "?"} ${restoreRevisionConfirmingTab ? (TABS.find((t) => t.key === restoreRevisionConfirmingTab)?.label ?? "") : ""} text with the selected revision's text. A new revision entry is created — no history is lost.`} />
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      ) : (
        /* -- Repo dashboard (at /projects/:project/repos/:repo): two tables side by side -- */
        <div className="flex flex-1 min-h-0 flex-col gap-3">
          {repo && (
            <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 rounded-lg border border-border/50 bg-card px-3 py-2 shadow-xs">
              <RepoHeaderBar
                repo={repo}
                repos={repos}
                onRepoChange={(value) =>
                  navigate(`${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(value)}`)
                }
                changelogStatus={changelogStatus}
                selectedBranch={selectedBranch}
                summary={summary}
              />
              <div className="flex items-center gap-1.5">
                <Button
                  size="sm"
                  variant="outline"
                  className="shrink-0 gap-1.5"
                  onClick={() => navigate(`${base}/projects/${encodeURIComponent(project!)}`)}
                >
                  <ArrowLeft className="size-3.5" />
                  Back to repos
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  className="gap-1.5 text-muted-foreground"
                  onClick={() => {
                    summary.refresh();
                    changelogStatus.refresh();
                    history.refresh();
                    builds.refresh();
                  }}
                  title="Refresh — pipeline runs and versions can change on their own from real CI activity"
                >
                  <RotateCcw className="size-3.5" />
                  Refresh
                </Button>
              </div>
            </div>
          )}

          {/* Quick summary cards */}
          <div className="grid shrink-0 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-lg border border-border/50 bg-card px-3 py-2.5">
              <span className="text-[10px] font-medium text-muted-foreground">Latest version</span>
              <p className="mt-0.5 font-mono text-sm font-semibold text-foreground">
                {history.status === "success"
                  ? data.find((e) => e.generated !== false && e.version)?.version
                    ? `v${data.find((e) => e.generated !== false && e.version)!.version}`
                    : "—"
                  : history.status === "loading"
                    ? "…"
                    : "—"}
              </p>
            </div>
            <div className="rounded-lg border border-border/50 bg-card px-3 py-2.5">
              <span className="text-[10px] font-medium text-muted-foreground">Total versions</span>
              <p className="mt-0.5 font-mono text-sm font-semibold text-foreground">
                {history.status === "success" ? history.data.total : history.status === "loading" ? "…" : "—"}
              </p>
            </div>
            <div className="rounded-lg border border-border/50 bg-card px-3 py-2.5">
              <span className="text-[10px] font-medium text-muted-foreground">Needs review</span>
              <p className="mt-0.5 font-mono text-sm font-semibold">
                {history.status === "success" ? (
                  data.filter((e) => e.generated === false).length > 0 ? (
                    <span className="text-amber-600 dark:text-amber-400">
                      {data.filter((e) => e.generated === false).length}
                    </span>
                  ) : (
                    <span className="text-emerald-600 dark:text-emerald-400">0</span>
                  )
                ) : history.status === "loading" ? (
                  "…"
                ) : (
                  "—"
                )}
              </p>
            </div>
            <div className="rounded-lg border border-border/50 bg-card px-3 py-2.5">
              <span className="text-[10px] font-medium text-muted-foreground">Activity</span>
              <p className="mt-0.5 font-mono text-sm font-semibold text-foreground">
                {summary.status === "success"
                  ? `${summary.data.commits + summary.data.pullRequests} changes`
                  : summary.status === "loading" ? "…" : "—"}
              </p>
            </div>
          </div>

          {/* Two tables side by side: Pipeline runs (every run, including PR-triggered ones) +
              Versions (generated) — all fill whatever screen height is left below the
              header/summary cards, and stretch to match each other's height regardless of how
              many rows any one has. Pipeline runs come from Azure directly and are independent
              of whether any changelog has been generated yet, so this doesn't gate on `data`. */}
          {history.status === "success" && (
            <div className="grid flex-1 min-h-0 items-stretch gap-4 lg:grid-cols-2">
              <div className="flex min-h-0 min-w-0 flex-col">
                <h3 className="mb-2 flex shrink-0 items-center gap-1.5 text-xs font-semibold text-foreground">
                  <Rocket className="size-3.5" />
                  Pipeline runs
                </h3>
                {builds.status === 'error' ? (
                  <div className="flex flex-1 items-center justify-center rounded-lg border border-border/60 bg-card px-3 py-8 text-center text-xs text-muted-foreground">
                    Failed to load pipeline runs
                  </div>
                ) : (
                  <PipelineRunsTable
                    className="min-h-0 min-w-0 flex-1"
                    items={pagedBuildRuns}
                    onSelect={(run) => navigate(buildHref(run.buildId, run.runNumber))}
                    page={buildCurrentPage}
                    onPageChange={setBuildPage}
                    pageSize={BUILD_PAGE_SIZE}
                    total={buildRuns.length}
                    emptyMessage={builds.status === 'loading' ? 'Loading…' : 'No pipeline runs found'}
                  />
                )}
              </div>
              <div className="flex min-h-0 min-w-0 flex-col">
                <h3 className="mb-2 flex shrink-0 items-center gap-1.5 text-xs font-semibold text-foreground">
                  <History className="size-3.5" />
                  Version history
                </h3>
                {(() => {
                  const generatedEntries = data.filter((e) => e.generated !== false);
                  const versionTotalPages = Math.max(1, Math.ceil(generatedEntries.length / 20));
                  const versionCurrentPage = Math.min(page, versionTotalPages - 1);
                  const pagedGenerated = generatedEntries.slice(
                    versionCurrentPage * 20,
                    versionCurrentPage * 20 + 20,
                  );
                  return (
                    <VersionTable
                      className="min-h-0 min-w-0 flex-1"
                      items={pagedGenerated}
                      selectedId={undefined}
                      onSelect={(entry) => navigate(entryHref(entry))}
                      page={versionCurrentPage}
                      onPageChange={setPage}
                      total={generatedEntries.length}
                      emptyMessage="No versions yet"
                    />
                  );
                })()}
              </div>
            </div>
          )}

          {history.status === "error" && history.error.message !== 'repo-not-ready' && (
            <Card>
              <CardContent className="p-6">
                <ErrorView message={history.error.message} />
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
