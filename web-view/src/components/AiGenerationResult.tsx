import { Clock, Coins, Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

export interface GenerationMetadata {
  provider?: string;
  model?: string;
  modelLabel?: string;
  durationMs?: number;
  totalTokens?: number;
}

interface AiGenerationResultProps {
  metadata: GenerationMetadata;
  generating?: boolean;
  runNumber?: number | string;
  children?: React.ReactNode;
  className?: string;
}

/** Shared result card with generation metadata display.
 *  Shows provider, model, latency, and token usage from actual generation data. */
export function AiGenerationResult({
  metadata,
  generating = false,
  runNumber,
  children,
  className,
}: AiGenerationResultProps) {
  const { provider, model, modelLabel, durationMs, totalTokens } = metadata;
  const hasMetadata = !!(provider || model || durationMs || totalTokens);

  return (
    <div
      className={cn(
        "animate-in fade-in slide-in-from-bottom-4 flex min-w-0 flex-col overflow-hidden rounded-xl border border-border/50 bg-card shadow-sm",
        className,
      )}
    >
      {/* Status banner */}
      <div
        className={cn(
          "flex flex-col gap-2 border-b border-border/30 px-4 py-3 transition-colors duration-300 sm:flex-row sm:items-center sm:justify-between",
          generating
            ? "bg-linear-to-r from-primary/6 to-primary/2"
            : "bg-linear-to-r from-emerald-500/6 to-emerald-500/2",
        )}
      >
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
          {generating ? (
            <span className="inline-flex items-center gap-1.5 text-primary">
              <Loader2 className="size-3 shrink-0 animate-spin" />
            </span>
          ) : (
            <span className="inline-flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400">
              <span className="size-2 shrink-0 rounded-full bg-emerald-500" />
              <span className="font-medium">
                Changelog generated{runNumber != null ? ` for Run - ${runNumber}` : ""}
              </span>
            </span>
          )}

          {hasMetadata && !generating && (
            <span className="hidden text-muted-foreground/40 sm:inline">·</span>
          )}

          {!generating && (modelLabel || model) && (
            <span className="text-muted-foreground/60" title={provider ? `${provider}/${model}` : model}>
              {modelLabel || model}
            </span>
          )}

          {durationMs != null && !generating && (
            <>
              <span className="hidden text-muted-foreground/40 sm:inline">·</span>
              <span className="inline-flex items-center gap-1 text-muted-foreground/60">
                <Clock className="size-3" />
                {(durationMs / 1000).toFixed(1)}s
              </span>
            </>
          )}

          {totalTokens != null && !generating && (
            <>
              <span className="hidden text-muted-foreground/40 sm:inline">·</span>
              <span className="inline-flex items-center gap-1 text-muted-foreground/60">
                <Coins className="size-3" />
                {totalTokens.toLocaleString()} tokens
              </span>
            </>
          )}
        </div>
      </div>

      {/* Content */}
      {children}
    </div>
  );
}
