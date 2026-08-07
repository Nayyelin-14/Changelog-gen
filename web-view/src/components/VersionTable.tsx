import { ChevronLeft, ChevronRight, GitPullRequest } from "lucide-react";

import type { GenerationRecord } from "@/api/types";
import { Button } from "@/components/ui/button";
import { formatTimestamp, sourceLabel } from "@/lib/historyTabs";
import { cn } from "@/lib/utils";

interface VersionTableProps {
  items: GenerationRecord[];
  selectedId?: string;
  onSelect: (item: GenerationRecord) => void;
  page: number;
  onPageChange: (page: number) => void;
  pageSize?: number;
  total: number;
  className?: string;
  emptyMessage?: string;
}

/** Dense, table-based version history — Dev-only. Every row is one entry from the backend.
 * When the list contains a mix of ungenerated PRs (generated: false) and generated versions,
 * a visual "Pending PRs" / "Version History" separator is inserted at the transition point
 * so you can immediately tell which rows still need attention.
 * `className` sets the outer container's sizing so callers can make it fill the screen — the
 * header row stays pinned and the body scrolls internally so the pagination footer stays put. */
export function VersionTable({ items, selectedId, onSelect, page, onPageChange, pageSize = 20, total, className, emptyMessage = "No entries." }: VersionTableProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.min(page, totalPages - 1);
  const hasPrs = items.some((e) => e.generated === false);

  return (
    <div className={cn("flex flex-col overflow-hidden rounded-lg border border-border/60 bg-card", className)}>
      <div className="min-h-0 flex-1 overflow-auto">
        <table className="w-full table-fixed text-xs">
          <thead className="sticky top-0 z-10 border-b border-border/60 bg-muted/95 backdrop-blur-sm">
            <tr>
              <th className="w-[40%] px-3 py-2 text-left font-medium text-muted-foreground">
                {hasPrs ? "Version" : "Version / PR"}
              </th>
              <th className="w-[25%] px-3 py-2 text-left font-medium text-muted-foreground">Status</th>
              <th className="w-[35%] px-3 py-2 text-right font-medium text-muted-foreground">Date</th>
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
            {(() => {
              // Build a flat row list: data rows + a section-break row inserted at the
              // ungenerated→generated transition, so neither row replaces the other. The break
              // only makes sense once we've actually seen a pending PR — otherwise (e.g. a page
              // that's all real versions) there's no transition to mark.
              const rows: ({ kind: "data"; entry: GenerationRecord } | { kind: "break" })[] = [];
              let insertedBreak = false;
              let sawPending = false;
              for (const entry of items) {
                const isGenerated = entry.generated !== false;
                if (!insertedBreak && sawPending && isGenerated) {
                  rows.push({ kind: "break" });
                  insertedBreak = true;
                }
                if (!isGenerated) sawPending = true;
                rows.push({ kind: "data", entry });
              }
              return rows.map((row) => {
                if (row.kind === "break") {
                  return (
                    <tr key="section-break">
                      <td colSpan={3} className="border-0 p-0">
                        <div className="flex items-center gap-2 border-y border-border/40 bg-muted/30 px-3 py-1.5">
                          <div className="h-px flex-1 bg-border/20" />
                          <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/60">
                            Version History
                          </span>
                          <div className="h-px flex-1 bg-border/20" />
                        </div>
                      </td>
                    </tr>
                  );
                }
                const entry = row.entry;
                const isSelected = entry.id === selectedId;
                const isGenerated = entry.generated !== false;
                const label = entry.version ? `v${entry.version}` : entry.developer || "—";
                const source = sourceLabel(entry.source);
                return (
                  <tr
                    key={entry.id}
                    onClick={() => onSelect(entry)}
                    className={cn(
                      "cursor-pointer transition-colors",
                      isSelected ? "bg-primary/5" : "hover:bg-muted/30",
                    )}
                  >
                    <td className="max-w-64 min-w-0 px-3 py-2">
                      <div className="flex items-center gap-2">
                        <div className={cn("h-4 w-0.5 shrink-0 rounded-full", isSelected ? "bg-primary" : isGenerated ? "bg-transparent" : "bg-amber-400")} />
                        {!isGenerated ? (
                          <span className="flex items-center gap-1.5 truncate font-medium text-foreground/90">
                            <GitPullRequest className="size-3 shrink-0 text-amber-500" />
                            <span className="truncate">{label}</span>
                          </span>
                        ) : (
                          <span className={cn("truncate font-mono font-semibold", isSelected ? "text-foreground" : "text-foreground/85")}>
                            {label}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      {!isGenerated ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400">
                          PR #{entry.id.startsWith("pr-") ? entry.id.slice(3) : "?"}
                        </span>
                      ) : source ? (
                        <span className={cn("inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium", source.className)}>
                          {source.label}
                        </span>
                      ) : (
                        <span className="text-muted-foreground/50">—</span>
                      )}
                    </td>
                    <td className="whitespace-nowrap px-3 py-2 text-right tabular-nums text-muted-foreground">
                      {formatTimestamp(entry.timestamp)}
                    </td>
                  </tr>
                );
              });
            })()}
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
