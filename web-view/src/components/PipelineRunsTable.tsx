import { ChevronLeft, ChevronRight, Loader2 } from "lucide-react";

import type { PipelineRunSummary } from "@/api/types";
import { Button } from "@/components/ui/button";
import { formatTimestamp } from "@/lib/historyTabs";
import { cn } from "@/lib/utils";

interface PipelineRunsTableProps {
  items: PipelineRunSummary[];
  onSelect: (run: PipelineRunSummary) => void;
  page: number;
  onPageChange: (page: number) => void;
  pageSize?: number;
  total: number;
  className?: string;
  emptyMessage?: string;
}

/** True while a run hasn't finished yet. Providers differ in casing: Azure sends
 * "inProgress"/"notStarted", GitHub sends snake_case "in_progress"/"queued"/"waiting"/"pending". */
function isRunningStatus(status: string | null | undefined): boolean {
  if (!status) return false;
  const s = status.toLowerCase();
  // Actively executing
  return s === "inprogress" || s === "in_progress";
}

function isQueuedStatus(status: string | null | undefined): boolean {
  if (!status) return false;
  const s = status.toLowerCase();
  // Waiting for a runner / not yet started
  return s === "queued" || s === "waiting" || s === "pending" || s === "notstarted";
}

function resultBadgeClass(result: string | null, status: string | null): string {
  switch (result) {
    case "succeeded":
      return "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400";
    case "failed":
      return "bg-destructive/15 text-destructive";
    case "canceled":
      return "bg-muted text-muted-foreground";
    case "partiallySucceeded":
      return "bg-amber-500/15 text-amber-600 dark:text-amber-400";
    default:
      // No result yet — a still-running (or queued) build hasn't finished, so `result` is null.
      if (isRunningStatus(status) || isQueuedStatus(status)) {
        return "bg-blue-500/15 text-blue-600 dark:text-blue-400";
      }
      return "bg-muted text-muted-foreground";
  }
}

function resultLabel(run: PipelineRunSummary): string {
  if (run.result) return run.result;
  if (isRunningStatus(run.status)) return "running";
  if (isQueuedStatus(run.status)) return "queued";
  return run.status ?? "—";
}

/** Dense, table-based pipeline-run list — mirrors VersionTable's layout (pinned header, internal
 * scroll, always-visible pagination footer) so the three dashboard tables read as one family. */
export function PipelineRunsTable({
  items,
  onSelect,
  page,
  onPageChange,
  pageSize = 20,
  total,
  className,
  emptyMessage = "No pipeline runs.",
}: PipelineRunsTableProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.min(page, totalPages - 1);

  return (
    <div className={cn("flex flex-col overflow-hidden rounded-lg border border-border/60 bg-card", className)}>
      <div className="min-h-0 min-w-0 flex-1 overflow-auto">
        <table className="w-full min-w-[34rem] table-fixed text-xs">
          <thead className="sticky top-0 z-10 border-b border-border/60 bg-muted/95 backdrop-blur-sm">
            <tr>
              <th className="w-[50%] px-3 py-2.5 text-left font-medium text-muted-foreground">Build</th>
              <th className="w-[18%] px-3 py-2.5 text-left font-medium text-muted-foreground">Result</th>
              <th className="w-[32%] px-3 py-2.5 text-right font-medium text-muted-foreground">Finished</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/40">
            {items.length === 0 && (
              <tr>
                <td colSpan={3} className="px-3 py-12 text-center text-muted-foreground">
                  {emptyMessage}
                </td>
              </tr>
            )}
            {items.map((run) => {
              const title = run.commitTitle ?? run.buildNumber ?? `Build #${run.buildId}`;
              return (
              <tr
                key={run.buildId}
                onClick={() => onSelect(run)}
                tabIndex={0}
                role="link"
                aria-label={title}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onSelect(run);
                  }
                }}
                className="cursor-pointer transition-colors hover:bg-muted/30 focus-visible:bg-muted/40 focus:outline-none"
              >
                <td className="max-w-64 min-w-0 px-3 py-2.5">
                  <p className="truncate text-sm font-medium text-foreground/90" title={title}>
                    {title}
                  </p>
                   <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[10px] text-muted-foreground/70">
                     <span className="shrink-0 font-mono">#{run.buildId}</span>
                     {run.runNumber && (
                       <span className="inline-block shrink-0 rounded-full bg-primary/10 px-1.5 py-0.5 font-mono font-medium text-primary">
                         Pipeline run #{run.runNumber}
                       </span>
                     )}
                     {run.buildNumber && (
                       <span className="inline-block shrink-0 rounded-full bg-muted px-1.5 py-0.5 font-mono font-medium">
                         {run.buildNumber}
                       </span>
                     )}
                    {run.prNumber != null && (
                      <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-primary/10 px-1.5 py-0.5 font-medium text-primary">
                        PR #{run.prNumber}
                      </span>
                    )}
                    {run.pipelineName && <span className="min-w-0 flex-1 truncate" title={run.pipelineName}>{run.pipelineName}</span>}
                    {run.sourceVersion && (
                      <span className="shrink-0 font-mono" title={run.sourceVersion}>{run.sourceVersion.slice(0, 7)}</span>
                    )}
                  </div>
                </td>
                <td className="px-3 py-2.5">
                  <span className={cn("inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-medium", resultBadgeClass(run.result, run.status))}>
                    {isRunningStatus(run.status) && !run.result && (
                      <Loader2 className="size-3 animate-spin" />
                    )}
                    {resultLabel(run)}
                  </span>
                </td>
                <td className="whitespace-nowrap px-3 py-2.5 text-right tabular-nums text-muted-foreground">
                  {run.finishTime ? formatTimestamp(run.finishTime) : "—"}
                </td>
              </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border/40 px-3 py-2">
        <span className="text-[11px] font-medium tabular-nums text-muted-foreground">
          {total > 0 ? `Page ${currentPage + 1} of ${totalPages} · ${total} total` : "0 total"}
        </span>
        <div className="flex items-center gap-1.5">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={currentPage === 0}
            onClick={() => onPageChange(currentPage - 1)}
            className="h-6 px-2 text-[11px]"
          >
            <ChevronLeft className="size-3" />
            Prev
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={currentPage >= totalPages - 1}
            onClick={() => onPageChange(currentPage + 1)}
            className="h-6 px-2 text-[11px]"
          >
            Next
            <ChevronRight className="size-3" />
          </Button>
        </div>
      </div>
    </div>
  );
}
