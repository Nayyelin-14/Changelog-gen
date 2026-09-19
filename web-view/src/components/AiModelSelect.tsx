import { Loader2 } from "lucide-react";

import type { AiModelOption } from "@/api/types";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

interface AiModelSelectProps {
  models: AiModelOption[];
  status: "loading" | "success" | "error";
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  triggerClassName?: string;
}

/** Reusable model dropdown that correctly shows loading, empty, and populated states.
 *  Models are always scoped to the currently selected provider — the parent owns that relationship.
 *  When models have status="checking", a subtle indicator shows background health probing. */
export function AiModelSelect({
  models,
  status,
  value,
  onChange,
  disabled = false,
  placeholder = "Select model…",
  className,
  triggerClassName,
}: AiModelSelectProps) {
  const hasChecking = models.some((m) => m.status === "checking");

  if (status === "loading" && models.length === 0) {
    return (
      <div className={cn("flex items-center gap-1.5", className)}>
        <Loader2 className="size-3.5 animate-spin text-muted-foreground/50" />
        <span className="text-xs text-muted-foreground/50">Loading models…</span>
      </div>
    );
  }

  if (status === "error" && models.length === 0) {
    return (
      <div className={cn("flex items-center gap-1.5", className)}>
        <span className="text-xs text-destructive">Failed to load models</span>
      </div>
    );
  }

  if (models.length === 0) {
    return (
      <div className={cn("flex items-center gap-1.5", className)}>
        <span className="text-xs text-muted-foreground/50">No models available</span>
      </div>
    );
  }

  return (
    <div className={cn("flex flex-col gap-0.5", className)}>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger className={cn("h-8 w-full gap-1.5 text-xs sm:w-auto", triggerClassName)}>
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent className="min-w-[220px]" side="bottom" align="end">
          {models.map((m) => (
            <SelectItem key={m.id} value={m.id} className="pr-8">
              <span className="flex min-w-0 items-center gap-2">
                {m.status === "checking" ? (
                  <Loader2 className="size-3 shrink-0 animate-spin text-muted-foreground/40" />
                ) : m.status === "available" ? (
                  <span className="size-1.5 shrink-0 rounded-full bg-emerald-500" />
                ) : null}
                <span className="min-w-0 truncate">{m.label}</span>
                {m.recommended && (
                  <Badge
                    variant="outline"
                    className="shrink-0 border-amber-500/40 px-1.5 py-0 text-[9px] leading-none text-amber-500"
                  >
                    Recommended
                  </Badge>
                )}
              </span>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {hasChecking && (
        <span className="pl-1 text-[10px] text-muted-foreground/40">
          Checking additional models…
        </span>
      )}
    </div>
  );
}
