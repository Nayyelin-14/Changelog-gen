import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import {
  AlertTriangle,
  ArrowLeft,
  BookOpen,
  Briefcase,
  Bug,
  Check,
  ChevronRight,
  ExternalLink,
  FileCode,
  GitCommit,
  GitMerge,
  GitPullRequest,
  Hash,
  Layers,
  type LucideIcon,
  Loader2,
  Pencil,
  RefreshCw,
  Sparkles,
  Terminal,
  Upload,
  User,
  Wand2,
  PictureInPicture,
} from "lucide-react";

import {
  commitChangelog,
  fetchRepoChanges,
  generateChangelogStream,
  getAiDraft,
  getChangelogRepoText,
  getPullRequestDetails,
  getRecordedRunChanges,
  listAiModels,
  listBranches,
  listHistory,
  pushChangelog,
  saveChangelogEdit,
} from "@/api/client";
import type {
  ChangeItem,
  PullRequestDetails as PRDetails,
  PullRequestWorkItemSummary,
} from "@/api/types";
import { Badge } from "@/components/ui/badge";
import { ChangelogBody } from "@/components/ChangelogBody";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { useQuery } from "@/hooks/useQuery";
import { isBotAuthor } from "@/lib/bots";
import { getStoredProvider } from "@/lib/provider";
import { getStoredRole, roleHome } from "@/lib/role";
import { cn } from "@/lib/utils";

const AUDIENCE_TABS = [
  { key: "developer", label: "Developer", icon: Terminal },
  { key: "qa", label: "QA", icon: Bug },
  { key: "business", label: "Business", icon: Briefcase },
] as const;
type Audience = (typeof AUDIENCE_TABS)[number]["key"];
// This page (reached from a pipeline run or "+ Generate new") only ever generates the Developer
// entry — QA/Business generation lives in the history panel's own per-audience flow instead. Kept
// as a filter (not a hardcoded single-item array) so it stays in sync with AUDIENCE_TABS above.
const GENERATED_AUDIENCES = AUDIENCE_TABS.filter(
  (tab) => tab.key === "developer",
);

function stripHtml(html: string | null): string {
  if (!html) return "";
  return html
    .replace(/<[^>]*>/g, "")
    .replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Builds ChangeItem[] AND the {@code ===} transport text for a PR's data, so both the
 * columns (source data) and the "Raw data sent to AI" popup see the same items. */
function formatPullRequestMessages(pr: PRDetails): {
  items: ChangeItem[];
  text: string;
} {
  const items: ChangeItem[] = [];
  const blocks: string[] = [];
  const prDesc = stripHtml(pr.description);
  items.push({
    type: "PULL_REQUEST",
    id: String(pr.prId),
    title: pr.title ?? "(no title)",
    author: pr.author ?? null,
    category: null,
    description: prDesc || null,
    filePaths: [],
    links: [],
    project: null,
    repo: null,
    date: null,
  });
  blocks.push(
    prDesc
      ? `=== [PULL_REQUEST|${pr.prId}|${pr.author ?? ""}|] ${pr.title ?? "(no title)"}\n${prDesc}`
      : `=== [PULL_REQUEST|${pr.prId}|${pr.author ?? ""}|] ${pr.title ?? "(no title)"}`,
  );
  for (const m of pr.commitMessages) {
    items.push({
      type: "COMMIT",
      id: null,
      title: m,
      author: null,
      category: null,
      description: null,
      filePaths: [],
      links: [],
      project: null,
      repo: null,
      date: null,
    });
    blocks.push(`=== [COMMIT||||] ${m}`);
  }
  for (const w of pr.workItems) {
    const wiDesc = stripHtml(w.description);
    items.push({
      type: "WORK_ITEM",
      id: String(w.id),
      title: `${w.type ?? "Work item"} #${w.id}: ${w.title ?? ""}`,
      author: w.assignedTo ?? null,
      category: null,
      description: wiDesc || null,
      filePaths: [],
      links: w.url ? [w.url] : [],
      project: null,
      repo: null,
      date: null,
    });
    const h = `=== [WORK_ITEM|${w.id}|${w.assignedTo ?? ""}|] ${w.type ?? "Work item"} #${w.id}: ${w.title ?? ""}`;
    blocks.push(wiDesc ? `${h}\n${wiDesc}` : h);
  }
  return { items, text: blocks.join("\n\n") };
}

/** Both the exact text sent to the AI AND the exact items that survived into it — same filter
 * pass, so "Source data" (which renders {@code items}) and "Raw data sent to AI" (which renders
 * {@code text}) can never disagree about what's actually going out. Previously these were two
 * separate derivations (this function's dedup vs. the page's own unfiltered display lists), which
 * could show e.g. "Commits 1" in the source-data panel while the raw-data breakdown said "0
 * commits" for the exact same load — a real discrepancy, not a rendering glitch. */
function computeRawChanges(items: ChangeItem[]): {
  items: ChangeItem[];
  text: string;
} {
  // No title-based exclusion: a commit is never hidden just because it happens to share its
  // title with a PR — every commit always shows and is always sent, even if its content ends up
  // duplicating a PR block word-for-word. Only an exact rendered-block duplicate (see seenBlocks
  // below) is ever dropped, since that's a real data-hygiene case, not a guess based on titles.
  const seenBlocks = new Set<string>();
  const keptItems: ChangeItem[] = [];
  const blocks: string[] = [];
  for (const i of items) {
    if (isBotAuthor(i.author)) continue;
    const meta = `${i.type}|${i.id ?? ""}|${i.author ?? ""}|${i.category ?? ""}`;
    const header = i.title ?? "(no message)";
    const lines = [`=== [${meta}] ${header}`];
    const desc = stripHtml(i.description);
    if (desc && desc !== header) lines.push(desc);
    if (i.filePaths.length > 0) lines.push(...i.filePaths);
    const block = lines.join("\n");
    if (seenBlocks.has(block)) continue;
    seenBlocks.add(block);
    keptItems.push(i);
    blocks.push(block);
  }
  return { items: keptItems, text: blocks.join("\n\n") };
}

/** Mirrors the backend's {@code NimAiProvider.buildUserText()} so "Raw data sent to AI" shows
 * exactly what the model receives — numbered items with project context, type refs, authors,
 * descriptions, and file paths — not the internal {@code ===} transport format. */
function formatAsAiPrompt(items: ChangeItem[], project: string): string {
  const lines = [
    `Project: "${project}"`,
    `Release date: ${new Date().toISOString().slice(0, 10)}`,
    "",
    "Items:",
  ];
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    let ref = "";
    if (item.type === "PULL_REQUEST" && item.id) ref = `(PR !${item.id}) `;
    else if (item.type === "WORK_ITEM" && item.id) ref = `#${item.id} `;
    const cats = item.category ? `[${item.category}] ` : "";
    const author = item.author ? ` (${item.author})` : "";
    lines.push(
      `${i + 1}. ${cats}${ref}${item.title ?? "(no message)"}${author}`,
    );
    const desc = stripHtml(item.description);
    if (desc && desc !== item.title) lines.push(`   ${desc}`);
    if (item.filePaths.length > 0)
      lines.push(...item.filePaths.map((p) => `   ${p}`));
  }
  return lines.join("\n");
}

type Status = "idle" | "loading" | "success" | "error";

/* ──────────────────────────────────────────────── */
/*  File path pill                                   */
/* ──────────────────────────────────────────────── */
function FilePill({ path }: { path: string }) {
  return (
    <span className="inline-flex max-w-full items-center gap-1 truncate rounded-md bg-muted/70 px-1.5 py-0.5 text-[10px] font-mono text-muted-foreground/80 transition-colors hover:bg-muted">
      <FileCode className="size-2.5 shrink-0" />
      <span className="truncate">{path}</span>
    </span>
  );
}

/* ──────────────────────────────────────────────── */
/*  Work item card                                   */
/* ──────────────────────────────────────────────── */
function WorkItemCard({ wi }: { wi: PullRequestWorkItemSummary }) {
  return (
    <div className="rounded-lg border border-border/30 bg-muted/20 p-3 text-xs transition-all hover:border-border/60 hover:bg-muted/30">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex items-center gap-2">
            <Badge
              variant="secondary"
              className="text-[10px] font-medium px-1.5 py-0"
            >
              {wi.type ?? "Work item"}
            </Badge>
            <span className="font-semibold text-foreground/80">#{wi.id}</span>
            {wi.state && (
              <span className="text-muted-foreground/50">· {wi.state}</span>
            )}
          </div>
          <p className="break-words text-sm font-medium text-foreground/90">
            {wi.title ?? ""}
          </p>
          {wi.assignedTo && (
            <div className="flex items-center gap-1 text-muted-foreground">
              <User className="size-3" />
              <span>{wi.assignedTo}</span>
            </div>
          )}
          {wi.description && (
            <p className="mt-1 break-words leading-relaxed text-muted-foreground/70">
              {stripHtml(wi.description)}
            </p>
          )}
        </div>
        {wi.url && (
          <a
            href={wi.url}
            target="_blank"
            rel="noopener noreferrer"
            className="shrink-0 text-muted-foreground/30 transition-colors hover:text-foreground"
          >
            <ExternalLink className="size-3.5" />
          </a>
        )}
      </div>
    </div>
  );
}

/* ──────────────────────────────────────────────── */
/*  Source data section — a card that opens a popup  */
/*  with the full detail list, instead of expanding   */
/*  in place.                                        */
/* ──────────────────────────────────────────────── */
function SourceDataSection({
  title,
  icon: Icon,
  iconBgClass,
  iconColorClass,
  count,
  children,
}: {
  title: string;
  icon: LucideIcon;
  iconBgClass: string;
  iconColorClass: string;
  count: number;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <button
          type="button"
          className="flex w-full items-center gap-2.5 rounded-xl border border-border/40 bg-card px-4 py-3 text-left transition-colors hover:border-border/60 hover:bg-muted/30"
        >
          <div
            className={cn(
              "flex size-7 shrink-0 items-center justify-center rounded-lg",
              iconBgClass,
            )}
          >
            <Icon className={cn("size-3.5", iconColorClass)} />
          </div>
          <span className="text-sm font-semibold text-foreground/85">
            {title}
          </span>
          <span className="text-xs text-muted-foreground/50">{count}</span>
          <PictureInPicture className="ml-auto size-3.5 shrink-0 text-muted-foreground cursor-pointer" />
        </button>
      </DialogTrigger>
      <DialogContent className="flex max-h-[80vh] max-w-2xl flex-col gap-0 overflow-hidden p-0">
        <DialogHeader className="shrink-0 border-b border-border/30 px-5 py-4">
          <DialogTitle className="flex items-center gap-2">
            <div
              className={cn(
                "flex size-6 shrink-0 items-center justify-center rounded-md",
                iconBgClass,
              )}
            >
              <Icon className={cn("size-3.5", iconColorClass)} />
            </div>
            {title}
            <span className="text-xs font-normal text-muted-foreground/50">
              {count}
            </span>
          </DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-5">
          {children}
        </div>
      </DialogContent>
    </Dialog>
  );
}

const typeColors: Record<string, string> = {
  COMMIT:
    "bg-sky-100 text-sky-700 border-sky-200 dark:bg-sky-950 dark:text-sky-400 dark:border-sky-800",
  PULL_REQUEST:
    "bg-violet-100 text-violet-700 border-violet-200 dark:bg-violet-950 dark:text-violet-400 dark:border-violet-800",
  WORK_ITEM:
    "bg-amber-100 text-amber-700 border-amber-200 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-800",
};
const catColors: Record<string, string> = {
  fix: "bg-red-100 text-red-700 border-red-200 dark:bg-red-950 dark:text-red-400 dark:border-red-800",
  feat: "bg-emerald-100 text-emerald-700 border-emerald-200 dark:bg-emerald-950 dark:text-emerald-400 dark:border-emerald-800",
  chore:
    "bg-slate-100 text-slate-700 border-slate-200 dark:bg-slate-950 dark:text-slate-400 dark:border-slate-800",
};

export function GenerateNewChangelogPage() {
  const { project, repo } = useParams<{ project: string; repo: string }>();
  const [searchParams] = useSearchParams();
  const branchParam = searchParams.get("branch") ?? undefined;
  const prIdParam = searchParams.get("prId") ?? undefined;
  const buildIdParam = searchParams.get("buildId") ?? undefined;
  // The pipeline run number (e.g. GitHub run #9, Azure build #9) — separate from the release version.
  const runNumberParam = searchParams.get("runNumber") ?? undefined;
  // The semantic version (e.g. "1.4.30") — set explicitly by the user or derived from CHANGELOG history.
  const versionParam = searchParams.get("version") ?? undefined;
  const navigate = useNavigate();

  const [version, setVersion] = useState("");
  const [runNumber, setRunNumber] = useState<string | undefined>(undefined);
  const [model, setModel] = useState<string | undefined>(undefined);
  const [status, setStatus] = useState<Status>("idle");
  const [audienceTexts, setAudienceTexts] = useState<
    Partial<Record<Audience, string>>
  >({});
  const [audienceLoading, setAudienceLoading] = useState<Set<Audience>>(
    new Set(),
  );
  const [streamDuration, setStreamDuration] = useState(0);
  const [streamTokens, setStreamTokens] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [activeAudience, setActiveAudience] = useState<Audience>("developer");
  const [commitText, setCommitText] = useState("");
  const [keptItems, setKeptItems] = useState<ChangeItem[]>([]);
  const [loadingCommits, setLoadingCommits] = useState(false);
  const [prDetails, setPrDetails] = useState<PRDetails | null>(null);
  const [changeItems, setChangeItems] = useState<ChangeItem[]>([]);
  const [editingTab, setEditingTab] = useState<Audience | null>(null);
  const [editText, setEditText] = useState("");
  const [editSaving, setEditSaving] = useState(false);
  const [editSaveError, setEditSaveError] = useState<string | null>(null);
  const [editSaveConfirmOpen, setEditSaveConfirmOpen] = useState(false);
  // Whether the developer text currently on screen has been persisted to Postgres yet — a fresh
  // AI generation (or a Regen) starts unsaved, same DB-only lifecycle as the history view: Generate
  // is preview-only, Save writes to Postgres, Push writes to the repo and requires a save first.
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveConfirmOpen, setSaveConfirmOpen] = useState(false);
  const [pushLoading, setPushLoading] = useState(false);
  const [pushResult, setPushResult] = useState<string | null>(null);
  // Separate from `error`/`status` — those belong to the generate flow, and setting status to
  // "error" on a failed push would wrongly blow away the already-generated preview on screen.
  const [pushError, setPushError] = useState<string | null>(null);
  const [pushConfirmOpen, setPushConfirmOpen] = useState(false);
  const [pushRepoText, setPushRepoText] = useState("");
  const [pushRepoTextLoading, setPushRepoTextLoading] = useState(false);
  // Defaults to whatever branch the page loaded with, but is independently changeable in the
  // push dialog — a version can need pushing to a branch other than the one its source data came
  // from (e.g. the page's branch has no CHANGELOG.md entry for this version at all).
  const [pushBranch, setPushBranch] = useState<string | undefined>(undefined);
  // The version is chosen by the human here in the push modal — never auto-resolved by the
  // dashboard. Pre-seeded from a pipeline-supplied version (versionParam) when one exists.
  const [pushVersion, setPushVersion] = useState("");
  // The model that actually produced the text currently on screen — captured at the moment a
  // generate call fires, not read live off `model`, so switching the dropdown after a result is
  // shown (without regenerating) can't make the banner claim a model that never actually ran.
  const [resultModel, setResultModel] = useState<string | undefined>(undefined);
  const resultRef = useRef<HTMLDivElement>(null);
  // Aborts the in-flight generate-stream fetch on unmount (navigate away mid-generation) or when
  // a new generate/Regen call starts — otherwise the old stream keeps reading and firing callbacks
  // into a page that's no longer showing it.
  const generateAbortRef = useRef<AbortController | null>(null);
  useEffect(() => () => generateAbortRef.current?.abort(), []);

  /* ── Accordion states ── */
  /* Per-item expanded detail */
  const [expandedItems, setExpandedItems] = useState<Set<number>>(new Set());

  const toggleItem = (idx: number) => {
    setExpandedItems((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  };

  const history = useQuery(
    useCallback(
      () =>
        project && repo
          ? listHistory(project, repo, branchParam, 0, 999)
          : Promise.resolve({ entries: [], total: 0 }),
      [project, repo, branchParam],
    ),
    [project, repo, branchParam],
    { cacheKey: "generate-page-history" },
  );

  /** Detect the push mode based on whether the version already has an entry in CHANGELOG.md.
   * Returns "UPDATE_EXISTING", "CREATE_NEW", or "CREATE_INITIAL". */
  const detectPushMode = useCallback(async () => {
    if (!project || !repo || !pushVersion) return "CREATE_INITIAL";
    // Check if any history entry has this version and is generated
    const entriesWithVersion =
      history.status === "success"
        ? history.data.entries.filter(
            (e) => e.version === pushVersion && e.generated !== false,
          )
        : [];
    if (entriesWithVersion.length > 0) {
      return "UPDATE_EXISTING"; // version already has an entry
    }
    // Check if CHANGELOG.md exists (has at least some content)
    try {
      await listHistory(project, repo, branchParam, 0, 1);
      // If we got here without throwing, CHANGELOG.md exists but version isn't in it
      return "CREATE_NEW";
    } catch {
      return "CREATE_INITIAL"; // no CHANGELOG.md at all
    }
  }, [project, repo, pushVersion, history, branchParam]);

  async function handlePush() {
    if (!project || !repo || !pushVersion || !pushBranch) return;
    setPushLoading(true);
    setPushError(null);
    // Detect push mode based on whether version already has a CHANGELOG.md entry
    const mode = await detectPushMode();
    try {
      // Generate no longer auto-saves (see /generate-stream) — the on-screen preview text is
      // sent along directly so the backend can cache it itself, but only after the repo write
      // actually succeeds, never before.
      const previewText = audienceTexts[activeAudience];
      const result = await pushChangelog(
        project,
        repo,
        pushVersion,
        pushBranch,
        activeAudience as any,
        previewText ? { text: previewText, model: resultModel } : undefined,
        buildIdParam ? Number(buildIdParam) : undefined,
      );
      setPushResult(result.commitUrl);
      setPushConfirmOpen(false);
      toast.success(`Pushed v${pushVersion} to ${pushBranch}`, {
        description: `Committed directly — no PR needed.`,
        action: {
          label: "View commit",
          onClick: () => window.open(result.commitUrl, "_blank", "noreferrer"),
        },
      });
    } catch (e) {
      const message =
        e instanceof Error ? e.message : "Failed to push changelog.";
      // Check for SHA conflict (stale base commit) — if the branch moved since we fetched
      // the CHANGELOG.md, we need to handle this differently based on push mode.
      if (
        message.includes("stale") ||
        message.includes("base commit") ||
        message.includes("conflict")
      ) {
        if (mode === "UPDATE_EXISTING") {
          // For UPDATE_EXISTING: do NOT auto-retry. Stop and show conflict message,
          // requiring the user to manually refresh and confirm before retrying.
          setPushRepoTextLoading(true);
          try {
            const freshText = await getChangelogRepoText(
              project,
              repo,
              pushVersion,
              pushBranch,
            );
            setPushRepoText(freshText ?? "");
            setPushError(
              `SHA conflict detected — the branch "${pushBranch}" has moved since the CHANGELOG.md was fetched. ` +
                `The current CHANGELOG.md content has been loaded. Please review the diff below, ` +
                `adjust the on-screen text if needed, and click "Push" again to retry.`,
            );
          } catch (fetchError) {
            setPushError(
              `SHA conflict detected, but could not refresh CHANGELOG.md: ${fetchError instanceof Error ? fetchError.message : "unknown error"}`,
            );
          } finally {
            setPushRepoTextLoading(false);
          }
          // Do NOT attempt auto-retry — leave it to the user to click Push again
          return;
        }

        // For CREATE_NEW/CREATE_INITIAL: auto-refetch and retry
        setPushRepoTextLoading(true);
        try {
          const freshText = await getChangelogRepoText(
            project,
            repo,
            pushVersion,
            pushBranch,
          );
          setPushRepoText(freshText ?? "");
          // Update the preview text to match what's currently in the repo
          if (freshText && activeAudience) {
            setAudienceTexts((prev) => ({
              ...prev,
              [activeAudience]: freshText,
            }));
          }
          // Re-attempt the push with fresh content
          const result = await pushChangelog(
            project,
            repo,
            pushVersion,
            pushBranch,
            activeAudience as any,
            freshText ? { text: freshText, model: resultModel } : undefined,
            buildIdParam ? Number(buildIdParam) : undefined,
          );
          setPushResult(result.commitUrl);
          setPushConfirmOpen(false);
          toast.success(
            `Pushed v${pushVersion} to ${pushBranch} (retry after refresh)`,
            {
              description: `Committed directly — no PR needed.`,
              action: {
                label: "View commit",
                onClick: () =>
                  window.open(result.commitUrl, "_blank", "noreferrer"),
              },
            },
          );
        } catch (retryError) {
          const retryMessage =
            retryError instanceof Error
              ? retryError.message
              : "Failed to push changelog on retry";
          setPushError(retryMessage);
        } finally {
          setPushRepoTextLoading(false);
        }
      } else {
        // Non-SHA-conflict error — show inline in the dialog
        setPushError(message);
      }
    } finally {
      setPushLoading(false);
    }
  }
  // When a pipeline run is selected (buildIdParam), version is deliberately NOT resolved
  // automatically — the design keeps the dashboard version-free: the version is only ever chosen
  // by a human in the push modal, or supplied by the pipeline itself (versionParam).
  const models = useQuery(
    useCallback(() => listAiModels(), []),
    [],
    { cacheKey: "ai-models", ttlMs: 5 * 60_000 },
  );
  const branches = useQuery(
    useCallback(
      () =>
        project && repo ? listBranches(project, repo) : Promise.resolve([]),
      [project, repo],
    ),
    [project, repo],
    { cacheKey: "generate-page-branches", ttlMs: 5 * 60_000 },
  );

  useEffect(() => {
    if (versionParam && !version) setVersion(versionParam);
  }, [versionParam, version]);

  // Set the runNumber from the URL param when present — this is the CI pipeline run number
  // (e.g. GitHub run #9, Azure build #9), displayed separately from the release version.
  useEffect(() => {
    if (runNumberParam) setRunNumber(runNumberParam);
    else setRunNumber(undefined);
  }, [runNumberParam]);

  useEffect(() => {
    if (!project || !repo || !prIdParam) return;
    let cancelled = false;
    setLoadingCommits(true);
    setChangeItems([]);
    getPullRequestDetails(project, repo, prIdParam)
      .then((pr) => {
        if (cancelled) return;
        setPrDetails(pr);
        const result = formatPullRequestMessages(pr);
        setKeptItems(result.items);
        setCommitText(result.text);
      })
      .catch(() => {
        if (!cancelled) setPrDetails(null);
      })
      .finally(() => {
        if (!cancelled) setLoadingCommits(false);
      });
    return () => {
      cancelled = true;
    };
  }, [project, repo, prIdParam]);

  useEffect(() => {
    if (!prIdParam) {
      setPrDetails(null);
      setChangeItems([]);
    }
  }, [prIdParam]);

  useEffect(() => {
    if (!project || !repo || !buildIdParam) return;
    const buildId = Number(buildIdParam);
    if (!Number.isFinite(buildId)) return;
    let cancelled = false;
    setLoadingCommits(true);
    setPrDetails(null);
    getRecordedRunChanges(project, repo, buildId, getStoredProvider())
      .then((release) => {
        if (cancelled) return;
        const filtered = release.items.filter(
          (i) =>
            i.type === "COMMIT" ||
            i.type === "PULL_REQUEST" ||
            i.type === "WORK_ITEM",
        );
        setChangeItems(filtered);
        const result = computeRawChanges(filtered);
        setKeptItems(result.items);
        setCommitText(result.text);
      })
      .catch(() => {
        if (!cancelled) setChangeItems([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingCommits(false);
      });
    return () => {
      cancelled = true;
    };
  }, [project, repo, buildIdParam]);

  // Restore a saved (version-free) AI draft when reopening a run that was saved earlier — shows
  // the exact text the user reviewed so they can push it (or regenerate) rather than starting blank.
  useEffect(() => {
    if (!project || !repo || !buildIdParam) return;
    const buildId = Number(buildIdParam);
    if (!Number.isFinite(buildId)) return;
    let cancelled = false;
    getAiDraft(project, repo, buildId, getStoredProvider())
      .then((draft) => {
        if (cancelled || !draft.text) return;
        setAudienceTexts({ [draft.audience ?? "developer"]: draft.text });
        if (draft.model) setResultModel(draft.model);
        if (draft.tokens != null) setStreamTokens(draft.tokens);
        if (draft.durationMs != null) setStreamDuration(draft.durationMs);
        setStatus("success");
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [project, repo, buildIdParam]);

  useEffect(() => {
    if (!buildIdParam) setChangeItems((prev) => (prIdParam ? prev : []));
  }, [buildIdParam, prIdParam]);

  useEffect(() => {
    if (models.status !== "success") return;
    const list = models.data;
    if (list.length > 0 && !model) setModel(list[0].id);
  }, [models.status, models, model]);

  useEffect(() => {
    setAudienceTexts({});
    setAudienceLoading(new Set());
  }, [version, project, repo]);

  useEffect(() => {
    if (status === "loading" || status === "success") {
      setTimeout(
        () =>
          resultRef.current?.scrollIntoView({
            behavior: "smooth",
            block: "start",
          }),
        150,
      );
    }
  }, [status]);

  const changes = useQuery(
    useCallback(() => {
      if (!project || !repo || !version.trim() || prIdParam || buildIdParam)
        return Promise.resolve(null);
      return fetchRepoChanges(
        project,
        repo,
        version.trim(),
        undefined,
        branchParam,
      );
    }, [project, repo, version, branchParam, prIdParam, buildIdParam]),
    [project, repo, version, branchParam, prIdParam, buildIdParam],
  );

  useEffect(() => {
    if (prIdParam || buildIdParam) return;
    if (changes.status !== "success" || !changes.data) return;
    const filtered = changes.data.items.filter(
      (i) =>
        i.type === "COMMIT" ||
        i.type === "PULL_REQUEST" ||
        i.type === "WORK_ITEM",
    );
    setChangeItems(filtered);
    const result = computeRawChanges(filtered);
    setKeptItems(result.items);
    setCommitText(result.text);
  }, [changes, prIdParam, buildIdParam]);

  const base = roleHome(getStoredRole() ?? "dev");
  const backHref = `${base}/projects/${encodeURIComponent(project!)}/repos/${encodeURIComponent(repo!)}${branchParam ? `?branch=${encodeURIComponent(branchParam)}` : ""}`;
  const canSubmit =
    status !== "loading" &&
    commitText.trim().length > 0;
  const hasPrData = !!(prDetails && prDetails.commitMessages.length > 0);
  const hasSourceData = hasPrData || changeItems.length > 0;
  const commitCount = prDetails?.commitMessages.length ?? 0;
  const workItemCount = prDetails?.workItems.length ?? 0;

  // The exact same dedup computeRawChanges applies to commitText (see the two effects above) —
  // so "Source data" below can never show a count that disagrees with "Raw data sent to AI".
  const dedupedChanges = useMemo(() => {
    const source =
      changes.status === "success" && changes.data
        ? changes.data.items.filter(
            (i) =>
              i.type === "COMMIT" ||
              i.type === "PULL_REQUEST" ||
              i.type === "WORK_ITEM",
          )
        : changeItems;
    return computeRawChanges(source).items;
  }, [changes, changeItems]);

  const commitsForDisplay = useMemo(
    () => dedupedChanges.filter((i) => i.type === "COMMIT"),
    [dedupedChanges],
  );
  const prsForDisplay = useMemo(
    () => dedupedChanges.filter((i) => i.type === "PULL_REQUEST"),
    [dedupedChanges],
  );
  const buildWorkItems = useMemo(
    () =>
      prDetails ? [] : dedupedChanges.filter((i) => i.type === "WORK_ITEM"),
    [prDetails, dedupedChanges],
  );

  const rawBreakdown = useMemo(() => {
    const c = keptItems.filter((i) => i.type === "COMMIT").length;
    const p = keptItems.filter((i) => i.type === "PULL_REQUEST").length;
    const w = keptItems.filter((i) => i.type === "WORK_ITEM").length;
    return { commits: c, prs: p, workItems: w, total: c + p + w };
  }, [keptItems]);

  // True once at least one audience has text — lets a Regen keep the result panel mounted
  // (with its own inline loading state) instead of swapping to the full "generating" card, which
  // only makes sense the first time there's nothing to show yet.
  const hasResult = Object.keys(audienceTexts).length > 0;
  const showResultPanel =
    status === "success" || (status === "loading" && hasResult);

  if (!project || !repo) return null;

  /* ── Handlers ──
   * force=false: the first generation for this version — serves a cached result if one already
   * exists for the same underlying data. force=true ("Regen"): always calls the AI again, even if
   * a cached result matches, so switching models and clicking Regen actually uses the new model. */
  async function handleGenerate(force = false) {
    // Abort any still-running generation (Regen clicked again, or the component unmounting) —
    // otherwise the previous stream keeps reading and calling these callbacks after they're stale.
    generateAbortRef.current?.abort();
    const controller = new AbortController();
    generateAbortRef.current = controller;
    setStatus("loading");
    setError(null);
    setSaved(false);
    setSaveError(null);
    setResultModel(model);
    // Previous audienceTexts are deliberately left in place (not cleared here) — a Regen keeps
    // showing the prior result until each audience's fresh text streams in and overwrites it
    // (see onAudience below), instead of blanking the whole panel for the duration of the call.
    setAudienceLoading(new Set(GENERATED_AUDIENCES.map((tab) => tab.key)));
    setStreamDuration(0);
    setStreamTokens(0);
    try {
      await generateChangelogStream(
        project!,
        repo!,
        {
          onAudience: (audience, text) => {
            setAudienceTexts((prev) => ({ ...prev, [audience]: text }));
            setAudienceLoading((prev) => {
              const next = new Set(prev);
              next.delete(audience as Audience);
              return next;
            });
            if (audience === "developer") setActiveAudience("developer");
          },
          onDone: (durationMs, totalTokens) => {
            setStreamDuration(durationMs);
            setStreamTokens(totalTokens);
            setStatus("success");
          },
          onError: (err) => {
            setError(err.message);
            setStatus("error");
          },
        },
        model,
        version || undefined,
        branchParam,
        undefined,
        commitText || undefined,
        force,
        controller.signal,
        buildIdParam ? Number(buildIdParam) : undefined,
      );
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(
        e instanceof Error ? e.message : "Failed to generate changelog.",
      );
      setStatus("error");
    }
  }

  function startEdit(audience: Audience) {
    setEditingTab(audience);
    setEditText(audienceTexts[audience] ?? "");
  }

  function cancelEdit() {
    setEditingTab(null);
    setEditText("");
  }

  /** Opens the "are you sure" confirmation — doesn't call the API yet, that only happens from
   * {@link saveEdit} once confirmed. */
  function requestSaveEdit() {
    setEditSaveError(null);
    setEditSaveConfirmOpen(true);
  }

  function cancelSaveEditConfirm() {
    setEditSaveConfirmOpen(false);
    setEditSaveError(null);
  }

  async function saveEdit() {
    if (!editingTab || !editText.trim() || !project || !repo) return;
    setEditSaving(true);
    setEditSaveError(null);
    try {
      if (version) {
        await saveChangelogEdit(
          project,
          repo,
          version,
          editingTab,
          editText,
          getStoredRole() ?? undefined,
          branchParam,
        );
      } else {
        // Version-free manual flow: the edit persists as the draft keyed on the pipeline run,
        // version gets chosen later in the push modal — same lifecycle as the Save button.
        const buildId = buildIdParam ? Number(buildIdParam) : undefined;
        await commitChangelog(
          project,
          repo,
          "",
          editingTab,
          resultModel ?? model ?? "",
          editText,
          branchParam,
          0,
          0,
          buildId,
        );
      }
      setAudienceTexts((prev) => ({ ...prev, [editingTab]: editText }));
      setSaved(true);
      setEditingTab(null);
      setEditText("");
      setEditSaveConfirmOpen(false);
      toast.success("Developer changelog edit saved", {
        description: "Saved to the database.",
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to save edit.";
      setEditSaveError(message);
      toast.error("Failed to save edit", { description: message });
    } finally {
      setEditSaving(false);
    }
  }

  /** Opens the "are you sure" confirmation for persisting the on-screen AI generation — doesn't
   * call the API yet, that only happens from {@link handleSaveGenerated} once confirmed. */
  function requestSaveGenerated() {
    setSaveError(null);
    setSaveConfirmOpen(true);
  }

  function cancelSaveGeneratedConfirm() {
    setSaveConfirmOpen(false);
    setSaveError(null);
  }

  /** Persists the on-screen AI generation exactly as previewed — no new AI call. Required before
   * Push becomes available, same DB-only lifecycle as the history view's Generate/commit flow.
   * A manual dashboard generation is version-free: `version` is only sent when a pipeline supplied
   * one (versionParam); otherwise the save is keyed on the pipeline run (buildId) instead and the
   * version gets chosen later in the push modal. */
  async function handleSaveGenerated() {
    const text = audienceTexts.developer;
    if (!project || !repo || !text) return;
    setSaving(true);
    setSaveError(null);
    const buildId = buildIdParam ? Number(buildIdParam) : undefined;
    try {
      await commitChangelog(
        project,
        repo,
        version || "",
        "developer",
        resultModel ?? model ?? "",
        text,
        branchParam,
        streamTokens,
        streamDuration,
        buildId,
      );
      setSaved(true);
      setSaveConfirmOpen(false);
      toast.success("Developer changelog saved", {
        description: "Saved to the database.",
      });
    } catch (e) {
      const message =
        e instanceof Error
          ? e.message
          : "Failed to save the generated changelog.";
      setSaveError(message);
      toast.error("Failed to save changelog", { description: message });
    } finally {
      setSaving(false);
    }
  }

  // Opens the confirm modal — fetches the repo's CURRENT CHANGELOG.md text first so the dialog
  // can show a real before/after diff, not just a description of what's about to happen. The
  // version is chosen here (seeded from a pipeline-supplied versionParam when one exists), not
  // auto-resolved by the dashboard.
  async function requestPush() {
    if (!project || !repo || !branchParam) return;
    setPushError(null);
    setPushRepoTextLoading(true);
    setPushBranch(branchParam);
    setPushVersion(version || "");
    const pushVer = version || "";
    try {
      const repoText = pushVer
        ? await getChangelogRepoText(project, repo, pushVer, branchParam)
        : "";
      setPushRepoText(repoText ?? "");
      setPushConfirmOpen(true);
    } catch (e) {
      const message =
        e instanceof Error
          ? e.message
          : "Failed to load the repo's current changelog.";
      toast.error("Failed to check the repo's current changelog", {
        description: message,
      });
    } finally {
      setPushRepoTextLoading(false);
    }
  }

  // Re-checks the diff's "Current" side against whichever branch is now selected — a version can
  // have a real CHANGELOG.md entry on one branch and none at all on another (see the "main has no
  // CHANGELOG.md" case), so the diff shown must always match the branch about to be pushed to.
  async function handlePushBranchChange(newBranch: string) {
    setPushBranch(newBranch);
    if (!project || !repo) return;
    setPushError(null);
    setPushRepoTextLoading(true);
    try {
      const repoText = pushVersion
        ? await getChangelogRepoText(project, repo, pushVersion, newBranch)
        : "";
      setPushRepoText(repoText ?? "");
    } catch (e) {
      const message =
        e instanceof Error
          ? e.message
          : "Failed to load the repo's current changelog.";
      toast.error("Failed to check the repo's current changelog", {
        description: message,
      });
    } finally {
      setPushRepoTextLoading(false);
    }
  }

  // Re-checks the diff's "Current" side when the user edits the version in the push modal —
  // the repo's CHANGELOG.md content differs per version, so the before-side must track it.
  async function handlePushVersionChange(newVersion: string) {
    setPushVersion(newVersion);
    if (!project || !repo) return;
    setPushError(null);
    setPushRepoTextLoading(true);
    try {
      const repoText = newVersion.trim()
        ? await getChangelogRepoText(project, repo, newVersion.trim(), pushBranch)
        : "";
      setPushRepoText(repoText ?? "");
    } catch {
      // A version with no CHANGELOG.md entry yet is the normal "first push" case — not an error.
      setPushRepoText("");
    } finally {
      setPushRepoTextLoading(false);
    }
  }

  /* ──────────────────────────────────────────────── */
  /*  Render                                          */
  /* ──────────────────────────────────────────────── */
  return (
    <div className="flex flex-col gap-6 pb-10">
      {/* ═══════════ HEADER ═══════════ */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <button
            type="button"
            onClick={() => navigate(backHref)}
            className="group inline-flex items-center gap-1 text-xs text-muted-foreground/60 transition-colors hover:text-foreground"
          >
            <ArrowLeft className="size-3 transition-transform group-hover:-translate-x-0.5" />
            {project} / {repo}
          </button>
          <h1 className="mt-0.5 text-xl font-bold tracking-tight sm:text-2xl">
            <span className="text-gradient-animate">Generate changelog</span>
          </h1>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge
            variant="secondary"
            className="gap-1.5 text-[10px] font-normal whitespace-nowrap"
          >
            <BookOpen className="size-2.5" />
            {history.status === "success"
              ? `${history.data.entries.length} saved`
              : "—"}
          </Badge>
          {branchParam && (
            <Badge
              variant="outline"
              className="text-[10px] font-mono font-normal"
            >
              {branchParam}
            </Badge>
          )}
          {buildIdParam && (
            <Badge
              variant="outline"
              className="gap-1 text-[10px] font-mono font-normal"
              title="Pipeline run that produced this source data"
            >
              <Hash className="size-2.5" />
              Run {buildIdParam}
            </Badge>
          )}
          {runNumber && (
            <Badge
              variant="secondary"
              className="gap-1 text-[10px] font-mono font-normal"
            >
              Pipeline run number: #{runNumber}
            </Badge>
          )}
          {/* <label className="flex items-center gap-1.5">
            <span className="flex items-center rounded-md border border-input bg-background pl-2 shadow-xs focus-within:ring-1 focus-within:ring-ring">
              <Input
                value={version}
                onChange={(e) => setVersion(e.target.value)}
                disabled={status === "loading"}
                placeholder="e.g. 1.0.5"
                title="Semantic release version to generate the changelog for — auto-filled from history or CHANGELOG.md"
                size={Math.max(8, version.length + 1)}
                className="h-7 border-0 bg-transparent px-1 py-0 text-xs font-mono font-medium shadow-none focus-visible:ring-0"
              />
            </span>
          </label> */}
        </div>
      </div>

      {/* ═══════════ ERROR ═══════════ */}
      {status === "error" && error && (
        <div className="animate-in fade-in slide-in-from-top-1 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3">
          <div className="flex items-start gap-2.5">
            <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive" />
            <div className="min-w-0 text-sm">
              <p className="font-medium text-destructive">Generation failed</p>
              <p className="mt-0.5 text-xs text-muted-foreground">{error}</p>
              {!commitText && (
                <p className="mt-1.5 text-xs text-muted-foreground/70">
                  No source data for this version — open this page from a
                  pipeline run in the dashboard's Pipeline runs table.
                </p>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ═══════════ PR CARD ═══════════ */}
      {prDetails && (
        <div className="animate-in fade-in slide-in-from-top-2 overflow-hidden rounded-xl border border-border/60 bg-gradient-to-br from-card to-muted/20">
          <div className="flex items-start gap-3.5 p-4">
            <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-violet-100 dark:bg-violet-950">
              <GitPullRequest className="size-4.5 text-violet-600 dark:text-violet-400" />
            </div>
            <div className="min-w-0 flex-1 space-y-1.5">
              <div className="flex items-center gap-2 text-sm">
                <Badge
                  variant="secondary"
                  className="text-[10px] font-medium px-1.5 py-0"
                >
                  PR #{prDetails.prId}
                </Badge>
                <span className="truncate font-semibold text-foreground/90">
                  {prDetails.title ?? ""}
                </span>
              </div>
              {prDetails.description &&
                prDetails.description !== prDetails.title && (
                  <p className="line-clamp-2 text-xs leading-relaxed text-muted-foreground">
                    {stripHtml(prDetails.description)}
                  </p>
                )}
              <div className="flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
                {prDetails.author && (
                  <span className="inline-flex items-center gap-1">
                    <User className="size-3" />
                    {prDetails.author}
                  </span>
                )}
                <span className="inline-flex items-center gap-1">
                  <GitCommit className="size-3" />
                  {commitCount} commit{commitCount !== 1 ? "s" : ""}
                </span>
                {workItemCount > 0 && (
                  <span className="inline-flex items-center gap-1">
                    <Layers className="size-3" />
                    {workItemCount} work item{workItemCount !== 1 ? "s" : ""}
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════ MAIN CONTENT ═══════════ */}
      <div className="flex flex-col gap-6">
        {/* ── Source data ── */}
        {(hasSourceData || loadingCommits) && (
          <div className="animate-in fade-in slide-in-from-bottom-2 flex flex-col gap-3">
            <div className="flex items-center gap-2">
              <div className="flex size-6 items-center justify-center rounded-md bg-primary/10">
                <Layers className="size-3.5 text-primary" />
              </div>
              <span className="text-xs font-semibold text-foreground/80">
                Source data
              </span>
              {hasSourceData && (
                <span className="text-[10px] text-muted-foreground/60">
                  —{" "}
                  {prDetails
                    ? `PR #${prDetails.prId} — ${commitCount} commit${commitCount !== 1 ? "s" : ""}${workItemCount > 0 ? ` + ${workItemCount} work item${workItemCount !== 1 ? "s" : ""}` : ""}`
                    : `${rawBreakdown.commits} commit${rawBreakdown.commits !== 1 ? "s" : ""}, ${rawBreakdown.prs} PR${rawBreakdown.prs !== 1 ? "s" : ""}${rawBreakdown.workItems > 0 ? `, ${rawBreakdown.workItems} work item${rawBreakdown.workItems !== 1 ? "s" : ""}` : ""}`}
                </span>
              )}
              {loadingCommits && (
                <Loader2 className="size-3 animate-spin text-muted-foreground/40" />
              )}
            </div>

            {loadingCommits ? (
              <div className="flex items-center justify-center gap-3 rounded-xl border border-dashed border-border/40 py-12">
                <Loader2 className="size-5 animate-spin text-muted-foreground/30" />
                <span className="text-xs text-muted-foreground/50">
                  {buildIdParam
                    ? "Loading pipeline run details…"
                    : "Loading PR details…"}
                </span>
              </div>
            ) : (
              <div className="flex flex-col gap-3">
                {/* Commits, PRs, work items — each a card that opens its full detail list in a
                    popup, one row of three on wide screens, stacking on narrow ones. */}
                <div className="grid grid-cols-1 items-start gap-3 md:grid-cols-2 xl:grid-cols-3">
                  {/* Commits */}
                  {commitsForDisplay.length > 0 && (
                    <SourceDataSection
                      title="Commits"
                      icon={GitCommit}
                      iconBgClass="bg-sky-100 dark:bg-sky-900/40"
                      iconColorClass="text-sky-600 dark:text-sky-400"
                      count={commitsForDisplay.length}
                    >
                      {commitsForDisplay.map((item, i) => {
                        const idx = i;
                        const isExpanded = expandedItems.has(idx);
                        return (
                          <div
                            key={idx}
                            className={cn(
                              "rounded-lg border border-border/30 transition-all duration-200",
                              isExpanded
                                ? "bg-muted/40"
                                : "bg-card hover:bg-muted/20",
                            )}
                          >
                            <button
                              type="button"
                              onClick={() => toggleItem(idx)}
                              className="flex w-full items-start gap-2.5 px-3 py-2 text-left"
                            >
                              <span className="mt-1.5 size-2 shrink-0 rounded-full bg-sky-400/50" />
                              <div className="min-w-0 flex-1">
                                <div className="flex items-center gap-2">
                                  {item.category && (
                                    <Badge
                                      variant="outline"
                                      className={cn(
                                        "text-[9px] font-medium px-1 py-0 leading-none",
                                        catColors[item.category] ??
                                          "bg-muted text-muted-foreground",
                                      )}
                                    >
                                      {item.category}
                                    </Badge>
                                  )}
                                  {item.author && (
                                    <span className="truncate text-[10px] text-muted-foreground/50">
                                      {item.author}
                                    </span>
                                  )}
                                </div>
                                <p className="mt-0.5 break-words text-sm font-medium text-foreground/80">
                                  {item.title ?? "(no message)"}
                                </p>
                              </div>
                              <div className="shrink-0 text-muted-foreground/30 transition-transform duration-200">
                                <ChevronRight
                                  className={cn(
                                    "size-3.5 transition-transform duration-200",
                                    isExpanded && "rotate-90",
                                  )}
                                />
                              </div>
                            </button>
                            <div
                              className={cn(
                                "grid transition-[grid-template-rows] duration-200 ease-in-out",
                                isExpanded
                                  ? "grid-rows-[1fr]"
                                  : "grid-rows-[0fr]",
                              )}
                            >
                              <div className="overflow-hidden">
                                <div className="space-y-2 border-t border-border/20 px-3 py-2.5">
                                  {item.description &&
                                    item.description !== item.title && (
                                      <p className="text-xs leading-relaxed text-muted-foreground/70">
                                        {item.description}
                                      </p>
                                    )}
                                  {item.filePaths &&
                                    item.filePaths.length > 0 && (
                                      <div className="flex flex-wrap gap-1">
                                        {item.filePaths.map((fp, j) => (
                                          <FilePill key={j} path={fp} />
                                        ))}
                                      </div>
                                    )}
                                  {item.links?.[0] && (
                                    <a
                                      href={item.links[0]}
                                      target="_blank"
                                      rel="noopener noreferrer"
                                      className="inline-flex items-center gap-1 text-[10px] text-muted-foreground/50 transition-colors hover:text-foreground"
                                    >
                                      <ExternalLink className="size-2.5" />
                                      View commit
                                    </a>
                                  )}
                                </div>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </SourceDataSection>
                  )}

                  {/* PRs */}
                  {prsForDisplay.length > 0 && (
                    <SourceDataSection
                      title="Pull requests"
                      icon={GitMerge}
                      iconBgClass="bg-violet-100 dark:bg-violet-900/40"
                      iconColorClass="text-violet-600 dark:text-violet-400"
                      count={prsForDisplay.length}
                    >
                      {prsForDisplay.map((item, i) => (
                        <div
                          key={item.id ?? i}
                          className="rounded-lg border border-border/30 bg-card p-3 transition-all hover:border-border/60 hover:bg-muted/20"
                        >
                          <div className="flex items-center gap-2">
                            <Badge
                              variant="outline"
                              className={cn(
                                "text-[10px] font-medium px-1.5 py-0",
                                typeColors.PULL_REQUEST,
                              )}
                            >
                              PR
                            </Badge>
                            <span className="font-semibold text-foreground/80">
                              #{item.id}
                            </span>
                            {item.author && (
                              <span className="truncate text-[11px] text-muted-foreground/60">
                                {item.author}
                              </span>
                            )}
                            {item.links?.[0] && (
                              <a
                                href={item.links[0]}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="shrink-0 text-muted-foreground/30 transition-colors hover:text-foreground"
                              >
                                <ExternalLink className="size-3" />
                              </a>
                            )}
                          </div>
                          <p className="mt-1.5 break-words text-sm font-medium text-foreground/90">
                            {item.title ?? "(no title)"}
                          </p>
                          {item.description &&
                            item.description !== item.title && (
                              <p className="mt-1 break-words text-xs leading-relaxed text-muted-foreground/70">
                                {stripHtml(item.description)}
                              </p>
                            )}
                        </div>
                      ))}
                    </SourceDataSection>
                  )}

                  {/* Work items */}
                  {(prDetails?.workItems.length ?? 0) > 0 ||
                  buildWorkItems.length > 0 ? (
                    <SourceDataSection
                      title="Work items"
                      icon={Bug}
                      iconBgClass="bg-amber-100 dark:bg-amber-900/40"
                      iconColorClass="text-amber-600 dark:text-amber-400"
                      count={
                        prDetails
                          ? prDetails.workItems.length
                          : buildWorkItems.length
                      }
                    >
                      {prDetails
                        ? prDetails.workItems.map((wi) => (
                            <WorkItemCard key={wi.id} wi={wi} />
                          ))
                        : buildWorkItems.map((wi, i) => (
                            <div
                              key={wi.id ?? i}
                              className="rounded-lg border border-border/30 bg-card p-3 text-xs transition-all hover:border-border/60 hover:bg-muted/20"
                            >
                              <div className="flex items-center gap-2">
                                <Badge
                                  variant="outline"
                                  className={cn(
                                    "text-[10px] font-medium px-1.5 py-0",
                                    typeColors.WORK_ITEM,
                                  )}
                                >
                                  Work item
                                </Badge>
                                <span className="font-semibold text-foreground/80">
                                  #{wi.id}
                                </span>
                                {wi.links?.[0] && (
                                  <a
                                    href={wi.links[0]}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="shrink-0 text-muted-foreground/30 transition-colors hover:text-foreground"
                                  >
                                    <ExternalLink className="size-3" />
                                  </a>
                                )}
                              </div>
                              <p className="mt-1.5 break-words text-sm font-medium text-foreground/90">
                                {wi.title ?? ""}
                              </p>
                              {wi.description && (
                                <p className="mt-1 break-words text-xs leading-relaxed text-muted-foreground/70">
                                  {stripHtml(wi.description)}
                                </p>
                              )}
                            </div>
                          ))}
                    </SourceDataSection>
                  ) : prDetails && prDetails.workItems.length === 0 ? (
                    <div className="flex items-center gap-2 rounded-lg border border-dashed border-border/30 px-4 py-3 text-[11px] text-muted-foreground/50">
                      <Layers className="size-3" />
                      No work items linked
                    </div>
                  ) : null}
                </div>
              </div>
            )}
          </div>
        )}

        {/* ── Action bar ── */}
        <div className="flex flex-col gap-3 rounded-xl border border-border/40 bg-card/50 p-3 sm:flex-row sm:items-center sm:justify-between">
          {commitText ? (
            <Dialog>
              <DialogTrigger asChild>
                <button
                  type="button"
                  className="flex min-w-0 flex-1 items-center gap-2 rounded-lg py-1 text-left text-[11px] text-muted-foreground/60 transition-colors hover:text-foreground/80"
                >
                  <FileCode className="size-3.5 shrink-0" />
                  <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="font-medium text-foreground/70">
                      Raw data sent to AI
                    </span>
                    {rawBreakdown.total > 0 && (
                      <span className="inline-flex flex-wrap items-center gap-1 text-[10px]">
                        <span className="rounded bg-sky-100/60 px-1 py-0.5 text-sky-700 dark:bg-sky-900/40 dark:text-sky-400">
                          {rawBreakdown.commits} commit
                          {rawBreakdown.commits !== 1 ? "s" : ""}
                        </span>
                        <span className="rounded bg-violet-100/60 px-1 py-0.5 text-violet-700 dark:bg-violet-900/40 dark:text-violet-400">
                          {rawBreakdown.prs} PR
                          {rawBreakdown.prs !== 1 ? "s" : ""}
                        </span>
                        <span className="rounded bg-amber-100/60 px-1 py-0.5 text-amber-700 dark:bg-amber-900/40 dark:text-amber-400">
                          {rawBreakdown.workItems} WI
                          {rawBreakdown.workItems !== 1 ? "s" : ""}
                        </span>
                        <span className="text-muted-foreground/40">
                          · {commitText.length} chars
                        </span>
                        <PictureInPicture className="ml-auto size-3.5 shrink-0 text-muted-foreground cursor-pointer" />
                      </span>
                    )}
                  </span>
                </button>
              </DialogTrigger>
              <DialogContent className="flex max-h-[80vh] max-w-3xl flex-col gap-0 overflow-hidden p-0">
                <DialogHeader className="shrink-0 border-b border-border/30 px-5 py-4">
                  <DialogTitle className="flex items-center gap-2">
                    <FileCode className="size-4 shrink-0 text-muted-foreground/60" />
                    Raw data sent to AI
                    <span className="text-xs font-normal text-muted-foreground/50">
                      {formatAsAiPrompt(keptItems, project ?? "").length} chars
                    </span>
                  </DialogTitle>
                </DialogHeader>
                <div className="min-h-0 flex-1 overflow-y-auto p-5">
                  <pre className="overflow-x-auto text-[11px] leading-relaxed text-foreground/70 font-mono whitespace-pre-wrap">
                    {formatAsAiPrompt(keptItems, project ?? "")}
                  </pre>
                </div>
              </DialogContent>
            </Dialog>
          ) : (
            <span className="text-[11px] text-muted-foreground/50">
              No source data yet — open this page from a pipeline run in the
              dashboard's Pipeline runs table.
            </span>
          )}

          <div className="flex w-full shrink-0 items-center gap-2 sm:w-auto">
            {models.status === "loading" ? (
              <Skeleton className="h-8 w-full sm:w-32" />
            ) : (
              models.status === "success" && (
                // Disabled (not hidden) once a result exists — this is the first-generation
                // control; once there's something on screen, Regen (with its own model picker,
                // below) is the only way forward. Kept visible as a record of what produced the
                // preview, same reasoning as the Generate button beside it.
                <Select
                  value={model}
                  onValueChange={setModel}
                  disabled={status === "loading" || hasResult}
                >
                  <SelectTrigger className="h-8 w-full gap-1.5 text-xs sm:w-auto">
                    <SelectValue placeholder="Select model…" />
                  </SelectTrigger>
                  <SelectContent
                    className="min-w-[180px]"
                    side="bottom"
                    align="end"
                  >
                    {models.data.map((m) => (
                      <SelectItem key={m.id} value={m.id} className="pr-8">
                        <span className="flex items-center gap-2">
                          <span className="truncate">{m.label}</span>
                          {m.recommended && (
                            <Badge
                              variant="outline"
                              className="shrink-0 text-[9px] leading-none px-1.5 py-0 text-amber-500 border-amber-500/40"
                            >
                              Recommended
                            </Badge>
                          )}
                        </span>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )
            )}
            <Button
              onClick={() => handleGenerate(false)}
              disabled={!canSubmit || hasResult}
              className={cn(
                "gap-1.5 px-4 text-xs h-8 transition-all whitespace-nowrap",
                status === "loading" && !hasResult && "animate-pulse",
              )}
            >
              {/* Only ever this button's OWN action (the first generation) shows as active —
                  once hasResult is true, a loading `status` means Regen (below) is the one
                  running, so this stays a plain disabled "Generate" instead of spinning in sync
                  with a request it didn't start. */}
              {status === "loading" && !hasResult ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" /> Generating…
                </>
              ) : (
                <>
                  <Wand2 className="size-3.5" /> Generate
                </>
              )}
            </Button>
          </div>
        </div>

        {/* ── Generating progress (first generation only — a Regen keeps the result panel below
             mounted instead of swapping to this) ── */}
        {status === "loading" && !hasResult && (
          <div
            ref={resultRef}
            className="animate-in fade-in slide-in-from-bottom-4 overflow-hidden rounded-xl border border-border/50 bg-card shadow-sm"
          >
            {/* Animated gradient bar */}
            <div className="h-1 w-full overflow-hidden bg-muted">
              <div
                className="h-full w-full animate-gradient-pan"
                style={{
                  background:
                    "linear-gradient(90deg, oklch(0.58 0.18 255), oklch(0.68 0.14 220), oklch(0.52 0.18 275), oklch(0.58 0.18 255))",
                  backgroundSize: "300% 100%",
                }}
              />
            </div>

            <div className="p-5">
              <div className="flex items-center gap-3">
                <div className="flex size-10 items-center justify-center rounded-xl bg-primary/10">
                  <Sparkles className="size-5 text-primary animate-glow-pulse" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold text-foreground/90">
                    Generating changelog
                  </p>
                </div>
                <Loader2 className="size-5 animate-spin text-primary/50" />
              </div>

              {/* Audience generation steps */}
              <div className="mt-5 space-y-2">
                {GENERATED_AUDIENCES.map((tab, i) => {
                  const isDone = !audienceLoading.has(tab.key);
                  const isLoading = audienceLoading.has(tab.key);
                  return (
                    <div
                      key={tab.key}
                      style={{ animationDelay: `${i * 80}ms` }}
                      className={cn(
                        "animate-in fade-in slide-in-from-left-1 flex items-center gap-3 rounded-lg border px-3.5 py-2.5 text-xs transition-all duration-300",
                        isDone
                          ? "border-emerald-200/50 bg-emerald-50/50 dark:border-emerald-900/30 dark:bg-emerald-950/20"
                          : isLoading
                            ? "border-primary/30 bg-primary/3"
                            : "border-border/30 bg-muted/20",
                      )}
                    >
                      <div
                        className={cn(
                          "flex size-6 shrink-0 items-center justify-center rounded-md",
                          isDone
                            ? "bg-emerald-100 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-400"
                            : isLoading
                              ? "bg-primary/10 text-primary"
                              : "bg-muted text-muted-foreground/40",
                        )}
                      >
                        {isDone ? (
                          <Check className="size-3.5" strokeWidth={3} />
                        ) : isLoading ? (
                          <Loader2 className="size-3.5 animate-spin" />
                        ) : (
                          <tab.icon className="size-3.5" />
                        )}
                      </div>
                      <span
                        className={cn(
                          "font-medium",
                          isDone && "text-emerald-700 dark:text-emerald-400",
                          isLoading && "text-foreground/90",
                          !isDone && !isLoading && "text-muted-foreground/40",
                        )}
                      >
                        {tab.label}
                      </span>
                      <span className="ml-auto text-[10px] text-muted-foreground/40">
                        {isDone
                          ? "Done"
                          : isLoading
                            ? "Generating…"
                            : "Pending"}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {/* ── Result panel (stays mounted through a Regen — see showResultPanel) ── */}
        {showResultPanel && (
          <div
            ref={resultRef}
            className="animate-in fade-in slide-in-from-bottom-4 flex min-w-0 flex-col overflow-hidden rounded-xl border border-border/50 bg-card shadow-sm"
          >
            {/* Status banner */}
            <div
              className={cn(
                "flex flex-col gap-2 border-b border-border/30 px-4 py-3 transition-colors duration-300 sm:flex-row sm:items-center sm:justify-between",
                status === "loading"
                  ? "bg-linear-to-r from-primary/6 to-primary/2"
                  : "bg-linear-to-r from-emerald-500/6 to-emerald-500/2",
              )}
            >
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                {status === "loading" ? (
                  <span className="inline-flex items-center gap-1.5 text-primary">
                    <Loader2 className="size-3 shrink-0 animate-spin" />
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400">
                    <span className="size-2 shrink-0 rounded-full bg-emerald-500" />
                    <span className="font-medium">
                      Changelog generated for Run - {runNumber}
                    </span>
                  </span>
                )}
                {status === "success" && (
                  <>
                    <span className="hidden text-muted-foreground/40 sm:inline">
                      ·
                    </span>
                    <span className="text-muted-foreground/60">
                      {(streamDuration / 1000).toFixed(1)}s
                    </span>
                    <span className="hidden text-muted-foreground/40 sm:inline">
                      ·
                    </span>
                    <span className="text-muted-foreground/60">
                      {streamTokens} tokens
                    </span>
                    {resultModel && (
                      <>
                        <span className="hidden text-muted-foreground/40 sm:inline">
                          ·
                        </span>
                        <span className="text-muted-foreground/60">
                          {(models.status === "success" &&
                            models.data.find((m) => m.id === resultModel)
                              ?.label) ||
                            resultModel}
                        </span>
                      </>
                    )}
                  </>
                )}
              </div>
            </div>

            {/* Tabs — hidden when there's only one generated audience (Developer); nothing to
                switch between until QA/Business are generated from the history panel instead. */}
            {GENERATED_AUDIENCES.length > 1 && (
              <div
                role="tablist"
                className="flex gap-0 overflow-x-auto border-b border-border/40 bg-muted/15"
              >
                {GENERATED_AUDIENCES.map((tab) => {
                  const tabLoading = audienceLoading.has(tab.key);
                  return (
                    <button
                      key={tab.key}
                      type="button"
                      role="tab"
                      aria-selected={activeAudience === tab.key}
                      onClick={() => setActiveAudience(tab.key)}
                      className={cn(
                        "relative flex shrink-0 items-center gap-1.5 px-3 py-2.5 text-xs transition-colors sm:px-4",
                        activeAudience === tab.key
                          ? "font-semibold text-foreground"
                          : "text-muted-foreground/60 hover:text-foreground/80",
                      )}
                    >
                      {tabLoading ? (
                        <Loader2 className="size-3.5 animate-spin" />
                      ) : (
                        <tab.icon className="size-3.5" />
                      )}
                      {tab.label}
                      {activeAudience === tab.key && (
                        <span className="absolute inset-x-0 bottom-0 h-0.5 bg-foreground transition-all duration-300" />
                      )}
                    </button>
                  );
                })}
              </div>
            )}

            {/* Content */}
            <div className={cn("p-5", editingTab !== null && "pb-0")}>
              {editingTab === activeAudience ? (
                <textarea
                  value={editText}
                  onChange={(e) => setEditText(e.target.value)}
                  className="w-full min-h-75 rounded-lg border border-border/50 bg-background p-3.5 text-sm font-mono leading-relaxed resize-y focus:outline-none focus:ring-1 focus:ring-ring transition-shadow"
                />
              ) : (
                <div
                  key={activeAudience}
                  className="animate-in fade-in duration-200"
                >
                  <ChangelogBody text={audienceTexts[activeAudience] ?? ""} />
                </div>
              )}
            </div>

            {/* Actions */}
            {editingTab === activeAudience ? (
              <div className="flex flex-col gap-2 border-t border-border/30 px-4 py-3">
                <div className="flex justify-end gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={cancelEdit}
                    disabled={editSaving}
                  >
                    Cancel
                  </Button>
                  <Button
                    size="sm"
                    className="gap-1.5"
                    onClick={requestSaveEdit}
                    disabled={editSaving || !editText.trim()}
                  >
                    {editSaving ? (
                      <>
                        <Loader2 className="size-3 animate-spin" /> Saving…
                      </>
                    ) : (
                      "Save"
                    )}
                  </Button>
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-3 border-t border-border/30 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  {activeAudience === "developer" && pushResult && (
                    <a
                      href={pushResult}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400 hover:underline"
                    >
                      <ExternalLink className="size-3" />
                      Pushed to Azure DevOps
                    </a>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    className="gap-1.5"
                    onClick={() => startEdit(activeAudience)}
                    disabled={status === "loading"}
                  >
                    <Pencil className="size-3" /> Edit
                  </Button>
                  {models.status === "success" && (
                    <div className="flex items-center gap-1">
                      {/* Locked while a (re)generation is in flight — otherwise switching models
                          mid-request leaves it ambiguous which model actually produced whatever
                          streams back in. */}
                      <Select
                        value={model}
                        onValueChange={setModel}
                        disabled={status === "loading"}
                      >
                        <SelectTrigger className="h-7 w-fit gap-1.5 px-2.5 text-xs font-medium">
                          <SelectValue placeholder="Model">
                            {models.data.find((m) => m.id === model)?.label}
                          </SelectValue>
                        </SelectTrigger>
                        <SelectContent side="bottom" align="end">
                          {models.data.map((m) => (
                            <SelectItem
                              key={m.id}
                              value={m.id}
                              className="pr-8 text-xs"
                            >
                              <span className="flex min-w-0 items-center gap-2">
                                <span className="min-w-0 truncate">
                                  {m.label}
                                </span>
                                {m.recommended && (
                                  <Badge
                                    variant="outline"
                                    className="shrink-0 text-[9px] leading-none px-1 py-0 text-amber-500 border-amber-500/40"
                                  >
                                    Recommended
                                  </Badge>
                                )}
                              </span>
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <Button
                        size="sm"
                        variant="outline"
                        className="gap-1.5 bg-amber-50 text-amber-700 border-amber-200 hover:bg-amber-100 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-800 dark:hover:bg-amber-900"
                        onClick={() => handleGenerate(true)}
                        disabled={status === "loading" || !model}
                      >
                        {status === "loading" ? (
                          <>
                            <Loader2 className="size-3 animate-spin" /> Regen…
                          </>
                        ) : (
                          <>
                            <RefreshCw className="size-3" /> Regen
                          </>
                        )}
                      </Button>
                    </div>
                  )}
                  {activeAudience === "developer" && !saved && (
                    <Button
                      size="sm"
                      variant="default"
                      className="gap-1.5"
                      onClick={requestSaveGenerated}
                      disabled={
                        saving ||
                        status === "loading" ||
                        !audienceTexts.developer
                      }
                    >
                      {saving ? (
                        <Loader2 className="size-3 animate-spin" />
                      ) : (
                        <Check className="size-3" />
                      )}
                      Save
                    </Button>
                  )}
                  {activeAudience === "developer" && saved && (
                    <Button
                      size="sm"
                      variant="default"
                      className="gap-1.5"
                      onClick={requestPush}
                      disabled={
                        pushRepoTextLoading ||
                        !branchParam ||
                        status === "loading"
                      }
                    >
                      {pushRepoTextLoading ? (
                        <Loader2 className="size-3 animate-spin" />
                      ) : (
                        <Upload className="size-3" />
                      )}
                      Push
                    </Button>
                  )}
                </div>
              </div>
            )}
          </div>
        )}

        {/* ── Empty hint ── */}
        {status === "idle" && !hasSourceData && !loadingCommits && (
          <div className="flex items-center gap-3 rounded-xl border border-dashed border-border/30 bg-muted/10 px-4 py-3 text-xs text-muted-foreground/60">
            <Layers className="size-4 shrink-0" />
            <span>
              No source data loaded yet. Paste changes manually in the text area
              to generate a changelog. You can also{" "}
              <button
                type="button"
                onClick={() => navigate(backHref)}
                className="underline underline-offset-2 hover:text-foreground/80"
              >
                go back to the dashboard
              </button>{" "}
              and select a pipeline run or a pending PR.
            </span>
          </div>
        )}

        <ConfirmDialog
          open={saveConfirmOpen}
          title={
            version
              ? `Save this Developer changelog for v${version}?`
              : "Save this Developer changelog?"
          }
          description="This writes the text below to the database. It won't be pushed to the repo until you confirm a separate Push."
          confirmLabel="Save"
          pendingLabel="Saving…"
          loading={saving}
          error={saveError}
          onConfirm={handleSaveGenerated}
          onCancel={cancelSaveGeneratedConfirm}
        >
          <div className="max-h-64 overflow-y-auto rounded-lg border border-border/20 p-3">
            <ChangelogBody text={audienceTexts.developer ?? ""} />
          </div>
        </ConfirmDialog>

        <ConfirmDialog
          open={editSaveConfirmOpen}
          title={
            version
              ? `Save this edit for v${version}?`
              : "Save this edit?"
          }
          description="This writes the edited text below to the database, replacing what's currently saved for Developer."
          confirmLabel="Save"
          pendingLabel="Saving…"
          loading={editSaving}
          error={editSaveError}
          onConfirm={saveEdit}
          onCancel={cancelSaveEditConfirm}
        >
          <div className="max-h-64 overflow-y-auto rounded-lg border border-border/20 p-3">
            <ChangelogBody text={editText} />
          </div>
        </ConfirmDialog>

        <ConfirmDialog
          open={pushConfirmOpen}
          title={`Push v${pushVersion || "?"} to the repo?`}
          description={`This commits directly to ${pushBranch ?? "?"} — no PR — replacing v${pushVersion || "?"}'s Developer entry in CHANGELOG.md with the text shown below.`}
          diff={{ before: pushRepoText, after: audienceTexts.developer ?? "" }}
          confirmLabel="Push"
          pendingLabel="Pushing…"
          loading={pushLoading}
          error={pushError}
          onConfirm={handlePush}
          onCancel={() => {
            setPushConfirmOpen(false);
            setPushError(null);
          }}
        >
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-sm">
              <span className="text-muted-foreground">Version</span>
              <Input
                value={pushVersion}
                onChange={(e) => handlePushVersionChange(e.target.value)}
                disabled={pushLoading || pushRepoTextLoading}
                placeholder="e.g. 1.0.5"
                size={Math.max(8, pushVersion.length + 1)}
                className="h-8 w-fit min-w-[120px] text-xs font-mono"
              />
            </div>
            {branches.status === "success" && branches.data.length > 0 && (
              <div className="flex items-center gap-2 text-sm">
                <span className="text-muted-foreground">Target branch</span>
                <Select
                  value={pushBranch}
                  onValueChange={handlePushBranchChange}
                  disabled={pushLoading || pushRepoTextLoading}
                >
                  <SelectTrigger className="h-8 w-[220px] text-xs">
                    <SelectValue placeholder="Select a branch" />
                  </SelectTrigger>
                  <SelectContent>
                    {branches.data.map((b) => (
                      <SelectItem key={b} value={b}>
                        {b}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            )}
          </div>
        </ConfirmDialog>
      </div>
    </div>
  );
}
