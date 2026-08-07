import { Briefcase, Code2, FlaskConical } from "lucide-react";

export const GENERATED_TABS = [
  {
    key: "qa" as const,
    label: "QA",
    icon: FlaskConical,
    description: "AI-generated summary of what changed and what to test.",
    accent: "text-amber-600 dark:text-amber-400",
    activeBg: "bg-amber-500/15",
  },
  {
    key: "business" as const,
    label: "Business",
    icon: Briefcase,
    description: "AI-generated summary for stakeholders — plain language, no technical jargon.",
    accent: "text-violet-600 dark:text-violet-400",
    activeBg: "bg-violet-500/15",
  },
];
export type GeneratedAudience = (typeof GENERATED_TABS)[number]["key"];

export const DEVELOPER_TAB = {
  key: "developer" as const,
  label: "Developer",
  icon: Code2,
  description: "Unedited commit history — full technical detail, always available.",
  accent: "text-sky-600 dark:text-sky-400",
  activeBg: "bg-sky-500/15",
};

export interface GeneratedContent {
  text: string;
}

/** Provenance of the current text: AI generation (model/tokens/duration) or human edit (who/when).
 * `hasPrevious`/`previousText` support restore preview. Developer-only fields (`hasUnpushedChanges`,
 * `pushedAt`, `pushedPullRequestUrl`) describe the last successful push to the repo. */
export interface GeneratedMeta {
  source: 'ai' | 'edit' | 'import' | 'raw';
  model?: string;
  tokens?: number;
  durationMs?: number;
  editedBy?: string;
  editedAt?: string;
  hasPrevious?: boolean;
  previousText?: string;
  hasUnpushedChanges?: boolean;
  pushedAt?: string;
  pushedPullRequestUrl?: string;
  pushedText?: string;
}

export function formatTimestamp(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleDateString();
}

// Maps a HistoryEntry/GenerationRecord's `source` (see the backend's ChangelogCacheService
// currentSource) to what a reviewer sees before deciding whether a version is ready to release.
const SOURCE_LABELS: Record<string, { label: string; className: string }> = {
  raw: { label: "Raw draft", className: "bg-amber-500/15 text-amber-600 dark:text-amber-400" },
  ai: { label: "AI generated", className: "bg-primary/10 text-primary" },
  edit: { label: "Edited", className: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400" },
  restore: { label: "Restored", className: "bg-sky-500/15 text-sky-600 dark:text-sky-400" },
  import: { label: "Imported", className: "bg-muted text-muted-foreground" },
  changelog: { label: "From CHANGELOG.md", className: "bg-muted text-muted-foreground" },
};

export function sourceLabel(source: string | null | undefined): { label: string; className: string } | null {
  if (!source) return null;
  return SOURCE_LABELS[source] ?? null;
}
