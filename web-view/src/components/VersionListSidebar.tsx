import { ChevronLeft, ChevronRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import { formatTimestamp } from "@/lib/historyTabs";
import { cn } from "@/lib/utils";

// Large enough that a full page of rows fills the sidebar's height on typical screens instead of
// leaving dead space below the list — callers fetching server-paginated data should request this
// many per page too (see AudienceChangelogPage/GenerateChangelogPage), so the "Page X of Y" math
// here stays in sync with what was actually fetched.
const PAGE_SIZE = 20;

interface VersionListSidebarProps<T> {
  items: T[];
  getId: (item: T) => string;
  getVersion: (item: T) => string | null | undefined;
  getTimestamp: (item: T) => string;
  getBranch?: (item: T) => string | null | undefined;
  // Omit for lists where every item is a real generated entry. Return false to mark an item as
  // not generated yet (e.g. a merged PR with no changelog) — its label falls back to getLabel.
  getGenerated?: (item: T) => boolean;
  getLabel?: (item: T) => string | null | undefined;
  // "raw" surfaces a "Needs review" badge — the one source value meaning nobody's looked at this
  // version yet. Other sources (ai/edit/import/changelog) aren't flagged here to keep the list
  // scannable; see the detail pane for the full source label.
  getSource?: (item: T) => string | null | undefined;
  selectedId?: string;
  onSelect: (item: T) => void;
  page: number;
  onPageChange: (page: number) => void;
  pageSize?: number;
  total?: number;
  heightClassName?: string;
}

export function VersionListSidebar<T>({
  items,
  getId,
  getVersion,
  getTimestamp,
  getBranch,
  getGenerated,
  getLabel,
  getSource,
  selectedId,
  onSelect,
  page,
  onPageChange,
  pageSize = PAGE_SIZE,
  total,
  heightClassName = "lg:h-[calc(100vh-14rem)]",
}: VersionListSidebarProps<T>) {
  const totalPages = Math.max(1, Math.ceil((total ?? items.length) / pageSize));
  const currentPage = Math.min(page, totalPages - 1);
  const pageItems = total !== undefined ? items : items.slice(currentPage * pageSize, (currentPage + 1) * pageSize);

  return (
    <div className={cn("flex flex-col overflow-hidden rounded-lg bg-card shadow-xs", heightClassName)}>
      <div className="flex items-center justify-between shrink-0 px-3 py-2.5">
        <span className="text-xs font-semibold text-muted-foreground/80">
          Versions
        </span>
        {total !== undefined && (
          <span className="text-[11px] tabular-nums text-muted-foreground/60">
            {total}
          </span>
        )}
      </div>

      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto p-2">
        {pageItems.map((item) => {
          const id = getId(item);
          const isSelected = id === selectedId;
          const branch = getBranch?.(item);
          const isGenerated = getGenerated ? getGenerated(item) : true;
          const version = getVersion(item);
          const label = version ? `v${version}` : getLabel?.(item) || "—";
          const needsReview = getSource?.(item) === "raw";

          return (
            <button
              key={id}
              type="button"
              onClick={() => onSelect(item)}
              className={cn(
                "flex w-full items-center gap-2.5 rounded-lg border px-3 py-2 text-left transition-all",
                isSelected
                  ? "border-primary/30 bg-primary/5 shadow-xs"
                  : "border-transparent hover:border-border/50 hover:bg-muted/30",
              )}
            >
              <div className={cn("h-6 w-0.5 shrink-0 rounded-full", isSelected ? "bg-primary" : "bg-transparent")} />
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline justify-between gap-2">
                  <span
                    className={cn(
                      "truncate font-mono text-xs font-bold",
                      isSelected ? "text-foreground" : "text-foreground/85",
                    )}
                  >
                    {label}
                  </span>
                  <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground/80">
                    {formatTimestamp(getTimestamp(item))}
                  </span>
                </div>
                {!isGenerated && (
                  <span className="inline-flex items-center gap-1 mt-0.5 rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400">
                    Not generated
                  </span>
                )}
                {isGenerated && needsReview && (
                  <span className="inline-flex items-center gap-1 mt-0.5 rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400">
                    <span className="size-1.5 rounded-full bg-amber-500 animate-pulse" />
                    Needs review
                  </span>
                )}
                {branch && (
                  <div className="mt-0.5 truncate text-[11px] text-muted-foreground/70">{branch}</div>
                )}
              </div>
            </button>
          );
        })}
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-between gap-2 border-t border-border/20 px-3 py-2">
          <span className="text-[11px] font-medium tabular-nums text-muted-foreground">
            Page {currentPage + 1} of {totalPages}
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
      )}
    </div>
  );
}
