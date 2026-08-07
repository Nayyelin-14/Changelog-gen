import { Clock, Coins } from "lucide-react";

import type { GeneratedMeta } from "@/lib/historyTabs";
import { formatTimestamp } from "@/lib/historyTabs";

/** "Edited by X · <time>" or "Generated with <model> · N tokens · Ns" — shared by every page
 * that shows a tab's current-text provenance (Dev dashboard, QA page), so there's one place this
 * footer is defined rather than near-identical copies drifting apart. */
export function ChangelogMetaSpans({ meta }: { meta: GeneratedMeta | undefined }) {
  // "import" means this text is exactly what's already in the repo's CHANGELOG.md, copied in
  // as-is — nothing was generated or edited via this app, so there's no provenance worth
  // reporting (and definitely not "Generated with AI", which it never was).
  if (!meta || meta.source === "import") return null;
  if (meta.source === "edit") {
    return (
      <>
        <span>Edited by {meta.editedBy ?? "someone"}</span>
        {meta.editedAt && <span>{formatTimestamp(meta.editedAt)}</span>}
      </>
    );
  }
  if (meta.source === "raw") {
    return <span>Auto-drafted from pipeline data — not yet AI-generated or reviewed</span>;
  }
  return (
    <>
      <span>Generated with {meta.model ?? "AI"}</span>
      {meta.tokens != null && (
        <span className="flex items-center gap-1">
          <Coins className="size-3" />
          {meta.tokens.toLocaleString()} tokens
        </span>
      )}
      {meta.durationMs != null && (
        <span className="flex items-center gap-1">
          <Clock className="size-3" />
          {(meta.durationMs / 1000).toFixed(1)}s
        </span>
      )}
    </>
  );
}
