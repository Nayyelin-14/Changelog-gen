import { useCallback, useEffect, useState } from "react";

import {
  getChangelogMeta,
  getChangelogText,
  listAiModels,
} from "@/api/client";
import type { GenerationRecord } from "@/api/types";
import { DEVELOPER_TAB, GENERATED_TABS } from "@/lib/historyTabs";
import type { GeneratedAudience, GeneratedContent, GeneratedMeta } from "@/lib/historyTabs";
import { useQuery } from "@/hooks/useQuery";

export type EditableTab = "developer" | GeneratedAudience;
export type { GeneratedMeta };

export const TAB_LABELS: Record<EditableTab, string> = Object.fromEntries(
  [DEVELOPER_TAB, ...GENERATED_TABS].map((tab) => [tab.key, tab.label]),
) as Record<EditableTab, string>;

/**
 * Shared state for viewing/editing/regenerating/restoring version text across Dev and QA pages.
 * One place, not two hand-rolled copies that drift apart. Page-specific UI (branch picker,
 * push-to-repo) stays in the page.
 */
export function useChangelogState(
  project: string | undefined,
  repo: string | undefined,
  selectedEntry: GenerationRecord | undefined,
) {
  const [activeTab, setActiveTab] = useState<EditableTab>("developer");
  const [model, setModel] = useState<string | undefined>(undefined);

  // Keyed by entryId — switching versions and coming back must reuse what's already been
  // loaded/generated/edited this session, not wipe it and re-fetch/re-generate from scratch.
  const [generatedByEntry, setGeneratedByEntry] = useState<
    Record<string, Partial<Record<GeneratedAudience, GeneratedContent>>>
  >({});
  const [metaByEntry, setMetaByEntry] = useState<Record<string, Partial<Record<EditableTab, GeneratedMeta>>>>({});
  // Tracks whether the "peek" below has settled for a given entry+tab, independent of whether it
  // found anything — lets the UI tell "haven't checked yet" apart from "confirmed nothing saved",
  // so switching to an already-generated tab shows a brief loading state instead of flashing the
  // "Generate with AI" empty-state prompt before the real text arrives.
  const [checkedByEntry, setCheckedByEntry] = useState<Record<string, Partial<Record<GeneratedAudience, boolean>>>>(
    {},
  );
  // Developer text has no "generated" slot of its own (it's always available, straight from
  // /history) — this is the only override needed for it. qa/business reuse the existing
  // `generated` slot for edits/restores too, since the view once something exists is the same either way.
  const [developerOverrides, setDeveloperOverrides] = useState<Record<string, string>>({});

  const models = useQuery(
    useCallback(() => listAiModels(), []),
    [],
  );
  useEffect(() => {
    if (models.status === "success" && models.data.length > 0 && !model) {
      setModel(models.data[0].id);
    }
  }, [models.status, models, model]);

  const entryId = selectedEntry?.id;
  const developerOverride = entryId ? developerOverrides[entryId] : undefined;
  const generated = (entryId && generatedByEntry[entryId]) || {};
  const meta = (entryId && metaByEntry[entryId]) || {};
  const checked = (entryId && checkedByEntry[entryId]) || {};

  // Only the UI interaction state resets on version switch — the loaded content itself is kept
  // (see generatedByEntry/metaByEntry/developerOverrides above), not wiped just because the user
  // looked elsewhere.
  useEffect(() => {
    setActiveTab("developer");
  }, [entryId]);

  // Peek whatever's already saved for qa/business the moment a tab opens — otherwise a version
  // generated/edited in an earlier session (or by the pipeline) would show the "click Generate"
  // empty state even though real content already exists in Postgres. No AI call: this only ever
  // reads what's already there.
  useEffect(() => {
    if (!project || !repo || !selectedEntry || activeTab === "developer") return;
    if (generatedByEntry[selectedEntry.id]?.[activeTab]) return;
    if (checkedByEntry[selectedEntry.id]?.[activeTab]) return;
    let cancelled = false;
    getChangelogText(project, repo, selectedEntry.version ?? "", activeTab)
      .then((text) => {
        if (cancelled) return;
        if (text) {
          setGeneratedByEntry((prev) => {
            const entryMap = prev[selectedEntry.id] ?? {};
            if (entryMap[activeTab]) return prev;
            return { ...prev, [selectedEntry.id]: { ...entryMap, [activeTab]: { text } } };
          });
        }
        setCheckedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [activeTab]: true },
        }));
      })
      .catch(() => {
        // Nothing saved yet for this version — the empty-state prompt already covers that case,
        // but it's still a settled check, not a pending one.
        if (cancelled) return;
        setCheckedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [activeTab]: true },
        }));
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project, repo, selectedEntry?.id, activeTab, generatedByEntry, checkedByEntry]);

  // Whether the active tab's current text is an AI generation or a human edit, and whether it has
  // a previous version to restore — looked up independently of the text peek above, since
  // developer's text is always available from /history but its meta still needs a lookup.
  useEffect(() => {
    if (!project || !repo || !selectedEntry) return;
    if (metaByEntry[selectedEntry.id]?.[activeTab]) return;
    let cancelled = false;
    getChangelogMeta(project, repo, selectedEntry.version ?? "", activeTab, selectedEntry.branch ?? undefined)
      .then((m) => {
        if (cancelled || !m.source) return;
        setMetaByEntry((prev) => {
          const entryMap = prev[selectedEntry.id] ?? {};
          if (entryMap[activeTab]) return prev;
          return {
            ...prev,
            [selectedEntry.id]: {
              ...entryMap,
              [activeTab]:
                m.source === "edit"
                  ? {
                      source: "edit",
                      editedBy: m.editedBy ?? undefined,
                      editedAt: m.at ?? undefined,
                      hasPrevious: m.hasPrevious,
                      previousText: m.previousText ?? undefined,
                      hasUnpushedChanges: m.hasUnpushedChanges,
                      pushedAt: m.pushedAt ?? undefined,
                      pushedPullRequestUrl: m.pushedPullRequestUrl ?? undefined,
                      pushedText: m.pushedText ?? undefined,
                    }
                  : {
                      source: "ai",
                      model: m.model ?? undefined,
                      tokens: m.tokens ?? undefined,
                      durationMs: m.durationMs ?? undefined,
                      hasPrevious: m.hasPrevious,
                      previousText: m.previousText ?? undefined,
                      hasUnpushedChanges: m.hasUnpushedChanges,
                      pushedAt: m.pushedAt ?? undefined,
                      pushedPullRequestUrl: m.pushedPullRequestUrl ?? undefined,
                      pushedText: m.pushedText ?? undefined,
                    },
            },
          };
        });
      })
      .catch(() => {
        // No meta saved yet for this version+audience — nothing to show, not an error.
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project, repo, selectedEntry?.id, activeTab, metaByEntry]);

  /** Drops the cached meta for one tab so the lookup effect re-fetches it — for page-local actions
   * that change what {@code changelog-meta} would report (like a push) without going through this
   * hook's own save/restore paths. */
  function clearMeta(tab: EditableTab) {
    if (!entryId) return;
    setMetaByEntry((prev) => {
      const entryMap = { ...prev[entryId] };
      delete entryMap[tab];
      return { ...prev, [entryId]: entryMap };
    });
  }

  return {
    // State
    activeTab,
    setActiveTab,
    model,
    setModel,
    models,
    developerOverride,
    generated,
    checked,
    meta,
    entryId,
    TAB_LABELS,
    // Raw state (for feature hooks that need to update)
    generatedByEntry,
    metaByEntry,
    // Setters
    setGeneratedByEntry,
    setMetaByEntry,
    setDeveloperOverrides,
    clearMeta,
  };
}