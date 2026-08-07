import { useCallback, useEffect, useState } from "react";
import { ChevronRight, Eye, History, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { deleteChangelogRevision, getChangelogMeta, type ChangelogAudience } from "@/api/client";
import type { ChangelogRevisionDto } from "@/api/types";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { DEVELOPER_TAB, GENERATED_TABS, formatTimestamp, sourceLabel } from "@/lib/historyTabs";
import { cn } from "@/lib/utils";

const AUDIENCES = [DEVELOPER_TAB, ...GENERATED_TABS];
const AUDIENCE_KEYS: ChangelogAudience[] = ["developer", "qa", "business"];

export interface AudienceSnapshot {
  source: string | null;
  model: string | null;
  editedBy: string | null;
  text: string;
}

/** One shared revision across all three audiences — Developer/QA/Business are different VIEWS
 * onto the same version, not three independent histories, so a revision here is a full snapshot
 * in time: whichever audience actually changed gets new content, the other two carry their own
 * latest text forward unchanged. An audience with nothing generated yet simply has no snapshot
 * here (see {@link developer}/{@link qa}/{@link business} all being optional). */
export interface HistoryRow {
  key: string;
  sequence: number;
  isLatest: boolean;
  /** Which audience's change actually produced this revision — null only if that can't be
   * determined (shouldn't normally happen). Purely informational for the row's own label. */
  changedAudience: ChangelogAudience | null;
  at: string;
  developer?: AudienceSnapshot;
  qa?: AudienceSnapshot;
  business?: AudienceSnapshot;
}

type BySequence = Record<number, Partial<Record<ChangelogAudience, ChangelogRevisionDto>>>;

function toSnapshot(rev: ChangelogRevisionDto | undefined): AudienceSnapshot | undefined {
  if (!rev) return undefined;
  return { source: rev.source, model: rev.model, editedBy: rev.editedBy, text: rev.text };
}

interface ChangelogEditHistoryPanelProps {
  project: string;
  repo: string;
  version: string;
  branch?: string;
  className?: string;
  selectedKey?: string | null;
  onSelect?: (row: HistoryRow) => void;
  /** Called after a revision is successfully deleted — e.g. to clear a selection pointing at it. */
  onDelete?: (row: HistoryRow) => void;
  refreshToken?: number;
}

export function ChangelogEditHistoryPanel({
  project,
  repo,
  version,
  branch,
  className,
  selectedKey,
  onSelect,
  onDelete,
  refreshToken,
}: ChangelogEditHistoryPanelProps) {
  const [rows, setRows] = useState<HistoryRow[] | null>(null);
  const [deleteConfirmRow, setDeleteConfirmRow] = useState<HistoryRow | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [developerMeta, qaMeta, businessMeta] = await Promise.all(
      AUDIENCES.map((a) =>
        getChangelogMeta(project, repo, version, a.key as ChangelogAudience, branch).catch(() => null),
      ),
    );

    // Sequence numbers are shared across all three audiences (see ChangelogService#createSnapshot
    // on the backend) — group each audience's own revisions by that shared sequence so every row
    // below can show all three views' state at that point in time, not just one audience's.
    const bySequence: BySequence = {};
    const metaPairs: [ChangelogAudience, typeof developerMeta][] = [
      ["developer", developerMeta],
      ["qa", qaMeta],
      ["business", businessMeta],
    ];
    for (const [audience, meta] of metaPairs) {
      if (!meta) continue;
      for (const rev of meta.revisions) {
        (bySequence[rev.sequence] ??= {})[audience] = rev;
      }
    }

    const sequences = Object.keys(bySequence).map(Number).sort((a, b) => b - a);
    const maxSequence = sequences[0];
    return sequences.map((seq): HistoryRow => {
      const snap = bySequence[seq];
      const prevSnap = bySequence[seq - 1];
      // Whichever audience's row actually differs from its own row one sequence back is the one
      // that changed here — the other two are byte-for-byte carried forward by construction.
      let changedAudience: ChangelogAudience | null = null;
      let at = "";
      for (const a of AUDIENCE_KEYS) {
        const cur = snap[a];
        if (!cur) continue;
        const prevRow = prevSnap?.[a];
        if (!prevRow || prevRow.text !== cur.text || prevRow.source !== cur.source) {
          changedAudience = a;
          at = cur.createdAt ?? "";
          break;
        }
      }
      if (!at) {
        at = snap.developer?.createdAt ?? snap.qa?.createdAt ?? snap.business?.createdAt ?? "";
      }
      return {
        key: `rev-${seq}`,
        sequence: seq,
        isLatest: seq === maxSequence,
        changedAudience,
        at,
        developer: toSnapshot(snap.developer),
        qa: toSnapshot(snap.qa),
        business: toSnapshot(snap.business),
      };
    });
  }, [project, repo, version, branch]);

  useEffect(() => {
    let cancelled = false;
    setRows(null);
    load().then((next) => {
      if (!cancelled) setRows(next);
    });
    return () => {
      cancelled = true;
    };
  }, [load, refreshToken]);

  function requestDelete(e: React.MouseEvent, row: HistoryRow) {
    e.stopPropagation();
    setDeleteError(null);
    setDeleteConfirmRow(row);
  }

  function cancelDelete() {
    setDeleteConfirmRow(null);
    setDeleteError(null);
  }

  function confirmDelete() {
    if (!deleteConfirmRow) return;
    const row = deleteConfirmRow;
    setDeleting(true);
    setDeleteError(null);
    // A single backend call — it deletes whichever of the three audiences actually has a row at
    // this sequence and renumbers all three together, so the shared numbering stays aligned.
    deleteChangelogRevision(project, repo, version, row.sequence)
      .then(() => load())
      .then((next) => {
        setRows(next);
        setDeleteConfirmRow(null);
        onDelete?.(row);
        toast.warning(`Revision #${row.sequence} deleted`, {
          description: "Removed for every view that had it — this can't be undone.",
        });
      })
      .catch((e) => {
        setDeleteError(e instanceof Error ? e.message : "Failed to delete revision. Please try again.");
      })
      .finally(() => setDeleting(false));
  }

  return (
    <>
    <div className={cn("flex flex-col overflow-hidden rounded-lg bg-card shadow-xs", className)}>
      <div className="flex shrink-0 items-center justify-between px-3 py-2.5">
        <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground/80">
          <History className="size-3.5" />
          v{version} · Revisions
        </span>
        <span className="text-[10px] text-muted-foreground/50">Click to view →</span>
      </div>

      <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-2">
        {rows === null && (
          <p className="px-2 py-6 text-center text-xs text-muted-foreground">Loading…</p>
        )}
        {rows !== null && rows.length === 0 && (
          <p className="px-2 py-6 text-center text-xs text-muted-foreground">
            No revisions for v{version} yet.
          </p>
        )}
        {rows?.map((row) => {
          const changedConfig = row.changedAudience ? AUDIENCES.find((a) => a.key === row.changedAudience) : undefined;
          const changedSnapshot = row.changedAudience ? row[row.changedAudience] : undefined;
          const label = sourceLabel(changedSnapshot?.source);
          const isSelected = row.key === selectedKey || (row.isLatest && !selectedKey);

          return (
            <button
              key={row.key}
              type="button"
              onClick={() => onSelect?.(row)}
              className={cn(
                "group relative flex w-full cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 text-left transition-colors",
                isSelected
                  ? "border-primary/50 bg-primary/10 ring-1 ring-primary/20"
                  : "border-transparent hover:border-border/60 hover:bg-muted/40",
              )}
            >
              <div
                className={cn(
                  "mt-1 size-2.5 shrink-0 rounded-full border-2",
                  row.isLatest
                    ? "border-primary bg-primary/20"
                    : "border-muted-foreground/30 bg-muted-foreground/10",
                )}
              />

              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-xs font-medium text-foreground/90">
                    #{row.sequence} {row.isLatest ? "(current)" : ""}
                  </span>
                  {isSelected && (
                    <span className="inline-flex items-center gap-1 rounded-full bg-primary/15 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                      <Eye className="size-2.5" />
                      Viewing
                    </span>
                  )}
                  {label && (
                    <span className={cn("rounded-full px-1.5 py-0.5 text-[10px] font-medium", label.className)}>
                      {label.label}
                    </span>
                  )}
                </div>
                <p className="mt-0.5 truncate text-[11px] text-muted-foreground/70">
                  {changedConfig ? `${changedConfig.label} · ` : ""}
                  {changedSnapshot?.source === "edit"
                    ? `Edited by ${changedSnapshot.editedBy ?? "someone"}`
                    : changedSnapshot?.model
                      ? `Generated with ${changedSnapshot.model}`
                      : changedSnapshot?.source === "raw"
                        ? "Pipeline import"
                        : null}
                  {(changedSnapshot?.source === "edit" || changedSnapshot?.model || changedSnapshot?.source === "raw") && row.at ? " · " : ""}
                  {row.at ? formatTimestamp(row.at) : ""}
                </p>
                {/* Which views actually exist by this revision — filled for the one that just
                    changed, muted for ones carried forward, hidden if that view has nothing yet. */}
                <div className="mt-1 flex items-center gap-1">
                  {AUDIENCES.map((a) => {
                    const has = !!row[a.key as ChangelogAudience];
                    if (!has) return null;
                    const changed = a.key === row.changedAudience;
                    return (
                      <a.icon
                        key={a.key}
                        className={cn("size-3", changed ? a.accent : "text-muted-foreground/25")}
                      />
                    );
                  })}
                </div>
              </div>
              {!row.isLatest && (
                <>
                  <span
                    className="mt-0.5 shrink-0 text-[10px] font-medium text-muted-foreground/50 group-hover:text-primary"
                    title="Click, then Rollback to this version to make it current again"
                  >
                    Use this →
                  </span>
                  <span
                    role="button"
                    tabIndex={0}
                    onClick={(e) => requestDelete(e, row)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") requestDelete(e as unknown as React.MouseEvent, row);
                    }}
                    className="mt-0.5 shrink-0 text-muted-foreground/50 transition-colors hover:text-destructive"
                    title="Delete this revision"
                  >
                    <Trash2 className="size-3" />
                  </span>
                </>
              )}
              <ChevronRight
                className={cn(
                  "mt-0.5 size-3.5 shrink-0 transition-colors",
                  isSelected ? "text-primary" : "text-muted-foreground/30 group-hover:text-muted-foreground/70",
                )}
              />
            </button>
          );
        })}
      </div>
    </div>

    <ConfirmDialog
      open={deleteConfirmRow !== null}
      title={`Delete revision #${deleteConfirmRow?.sequence ?? ""}?`}
      description="This removes it for every view that has it here (Developer/QA/Business) and can't be undone."
      confirmLabel="Delete"
      pendingLabel="Deleting…"
      loading={deleting}
      error={deleteError}
      onConfirm={confirmDelete}
      onCancel={cancelDelete}
    />
    </>
  );
}
