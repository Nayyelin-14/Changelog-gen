import type { AiModelOption, AiProviderOption } from "@/api/types";
import { AiModelSelect } from "@/components/AiModelSelect";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

interface AiProviderModelSelectProps {
  provider: string;
  providers: AiProviderOption[];
  onProviderChange: (id: string) => void;
  models: AiModelOption[];
  modelsStatus: "loading" | "success" | "error";
  model: string | undefined;
  onModelChange: (id: string | undefined) => void;
  disabled?: boolean;
  /** Hide provider selector when there's only one enabled provider. */
  hideProviderWhenSingle?: boolean;
  variant?: "toolbar" | "inline";
  className?: string;
}

/**
 * Provider + model dropdowns without a generate button, for places that keep their own
 * action button (e.g. the version page's Regen/Generate controls).
 */
export function AiProviderModelSelect({
  provider,
  providers,
  onProviderChange,
  models,
  modelsStatus,
  model,
  onModelChange,
  disabled = false,
  hideProviderWhenSingle = true,
  variant = "toolbar",
  className,
}: AiProviderModelSelectProps) {
  const showProvider = !hideProviderWhenSingle || providers.length > 1;
  const providerLabel = providers.find((p) => p.id === provider)?.label;
  const inline = variant === "inline";

  return (
    <div className={cn("flex flex-wrap items-center gap-1.5", className)}>
      {showProvider && (
        <Select value={provider} onValueChange={onProviderChange} disabled={disabled}>
          <SelectTrigger
            className={cn(
              "w-fit gap-1.5",
              inline ? "h-7 px-2.5 text-xs font-medium" : "h-8 min-w-[140px] text-xs",
            )}
          >
            <SelectValue placeholder="Provider">
              {providerLabel}
            </SelectValue>
          </SelectTrigger>
          <SelectContent className="min-w-[180px]" side="bottom" align="end">
            {providers.map((p) => (
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
        models={models}
        status={modelsStatus}
        value={model}
        onChange={onModelChange}
        disabled={disabled}
        providerLabel={providerLabel}
        triggerClassName={inline ? "h-7 text-xs font-medium" : undefined}
      />
    </div>
  );
}
