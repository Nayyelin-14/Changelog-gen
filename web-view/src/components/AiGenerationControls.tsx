import { Loader2, Sparkles } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { AiModelSelect } from "@/components/AiModelSelect";
import { cn } from "@/lib/utils";
import type { UseAiModelsReturn } from "@/hooks/useAiModels";

interface AiGenerationControlsProps {
  ai: UseAiModelsReturn;
  onGenerate: () => void;
  generating?: boolean;
  disabled?: boolean;
  /** Hide provider selector when there's only one enabled provider */
  hideProviderWhenSingle?: boolean;
  /** Layout variant */
  variant?: "toolbar" | "inline";
  className?: string;
}

/** Shared provider + model + Generate button controls.
 *  Used by both GenerateNewChangelogPage and GenerateChangelogPage. */
export function AiGenerationControls({
  ai,
  onGenerate,
  generating = false,
  disabled = false,
  hideProviderWhenSingle = true,
  variant = "toolbar",
  className,
}: AiGenerationControlsProps) {
  const showProvider = !hideProviderWhenSingle || ai.providerOptions.length > 1;
  const canGenerate = ai.isReady && !generating && !disabled;

  if (variant === "inline") {
    return (
      <div className={cn("flex items-center gap-1.5", className)}>
        {showProvider && (
          <Select value={ai.provider} onValueChange={ai.setProvider} disabled={generating}>
            <SelectTrigger className="h-7 w-fit gap-1.5 px-2.5 text-xs font-medium">
              <SelectValue placeholder="Provider">
                {ai.providerOptions.find((p) => p.id === ai.provider)?.label}
              </SelectValue>
            </SelectTrigger>
            <SelectContent side="bottom" align="end">
              {ai.providerOptions.map((p) => (
                <SelectItem key={p.id} value={p.id} className="pr-8 text-xs">
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="min-w-0 truncate">{p.label}</span>
                  </span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
        <AiModelSelect
          models={ai.models}
          status={ai.modelsStatus}
          value={ai.model}
          onChange={ai.setModel}
          disabled={generating}
          triggerClassName="h-7 text-xs font-medium"
        />
        <Button
          size="sm"
          variant="outline"
          className="gap-1.5 border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-400 dark:hover:bg-amber-900"
          onClick={onGenerate}
          disabled={!canGenerate}
        >
          {generating ? (
            <><Loader2 className="size-3 animate-spin" /> Regen…</>
          ) : (
            "Regen"
          )}
        </Button>
      </div>
    );
  }

  // toolbar variant — full-width responsive layout
  return (
    <div className={cn(
      "flex flex-col gap-3 rounded-xl border border-border/40 bg-card/50 p-3 sm:flex-row sm:items-center sm:justify-between",
      className,
    )}>
      {/* Left side: provider + model selects — aligned to same height */}
      <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-center sm:gap-2">
        {showProvider && (
          <Select value={ai.provider} onValueChange={ai.setProvider} disabled={generating}>
            <SelectTrigger className="h-8 w-full gap-1.5 text-xs sm:w-auto sm:min-w-[140px]">
              <SelectValue placeholder="Provider…" />
            </SelectTrigger>
            <SelectContent className="min-w-[180px]" side="bottom" align="end">
              {ai.providerOptions.map((p) => (
                <SelectItem key={p.id} value={p.id} className="pr-8">
                  <span className="flex items-center gap-2">
                    <span className="truncate">{p.label}</span>
                  </span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
        <AiModelSelect
          models={ai.models}
          status={ai.modelsStatus}
          value={ai.model}
          onChange={ai.setModel}
          disabled={generating}
        />
      </div>

      {/* Right side: Generate button */}
      <Button
        onClick={onGenerate}
        disabled={!canGenerate}
        className={cn(
          "h-8 gap-1.5 px-4 text-xs transition-all whitespace-nowrap sm:w-auto",
          generating && "animate-pulse",
        )}
      >
        {generating ? (
          <>
            <Loader2 className="size-3.5 animate-spin" /> Generating…
          </>
        ) : (
          <>
            <Sparkles className="size-3.5" /> Generate
          </>
        )}
      </Button>
    </div>
  );
}
