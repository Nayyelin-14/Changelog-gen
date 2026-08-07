import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import {
  commitChangelog,
  generateChangelog,
  getChangelogMeta,
  getChangelogText,
  listAiModels,
  restoreChangelogPrevious,
  restoreChangelogRevision,
  restoreChangelogToPushed,
  saveChangelogEdit,
} from "@/api/client";
import type { GenerationRecord } from "@/api/types";
import { DEVELOPER_TAB, GENERATED_TABS } from "@/lib/historyTabs";
import type { GeneratedAudience, GeneratedContent, GeneratedMeta } from "@/lib/historyTabs";
import { getStoredRole } from "@/lib/role";
import { useQuery } from "@/hooks/useQuery";

export type EditableTab = "developer" | GeneratedAudience;

const TAB_LABELS: Record<EditableTab, string> = Object.fromEntries(
  [DEVELOPER_TAB, ...GENERATED_TABS].map((tab) => [tab.key, tab.label]),
) as Record<EditableTab, string>;

/**
 * Shared logic for viewing/editing/regenerating/restoring version text across Dev and QA pages
 * — one place, not two hand-rolled copies that drift apart. Page-specific UI (branch picker,
 * push-to-repo) stays in the page.
 */
export function useChangelogEditor(
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

  // True while the AI call itself is in flight (fetching a candidate to preview) — nothing is
  // saved yet at this point, so this drives the Generate/Regenerate button's own spinner, not the
  // confirm dialog's.
  const [generating, setGenerating] = useState<EditableTab | null>(null);
  const [genError, setGenError] = useState<string | null>(null);
  // Set only once a preview call succeeds — holds the candidate text so the confirm dialog can
  // show a real before/after diff instead of asking "are you sure" about something not generated
  // yet. `force` is carried through only to word the dialog/toast (Regenerate vs Generate);
  // `tokens`/`durationMs` come from the preview call, since committing doesn't call the AI again.
  const [generateConfirm, setGenerateConfirm] = useState<{
    tab: EditableTab;
    force: boolean;
    text: string;
    model: string;
    tokens: number;
    durationMs: number;
  } | null>(null);
  // True only while the confirmed preview is being written to Postgres — a separate flag from
  // `generating` so the dialog's own spinner doesn't fight with the Generate/Regenerate button's.
  const [confirmingGenerate, setConfirmingGenerate] = useState(false);

  const [editingTab, setEditingTab] = useState<EditableTab | null>(null);
  const [editText, setEditText] = useState("");
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  const [saveConfirmingTab, setSaveConfirmingTab] = useState<EditableTab | null>(null);

  const [restoring, setRestoring] = useState(false);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  // Set only while the "are you sure" confirmation for this tab is open — restore is a rollback,
  // so it gets a preview of what it would bring back before it actually fires, same as any other
  // action that changes what's saved.
  const [restoreConfirmingTab, setRestoreConfirmingTab] = useState<EditableTab | null>(null);

  // Rolling back to the last successfully pushed version — a separate rollback target from the
  // edit/regeneration history above, only ever relevant for the developer tab (push is
  // developer-only), but kept here rather than in the page for the same reason restorePrevious is:
  // it's a Postgres-only text rollback, identical in shape to the one above, just a different
  // source of truth to roll back to.
  const [restoringPushed, setRestoringPushed] = useState(false);
  const [restorePushedError, setRestorePushedError] = useState<string | null>(null);
  const [restorePushedConfirmingTab, setRestorePushedConfirmingTab] = useState<EditableTab | null>(null);

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
    setGenError(null);
    setGenerateConfirm(null);
    setConfirmingGenerate(false);
    setEditingTab(null);
    setEditError(null);
    setSaveConfirmingTab(null);
    setRestoreError(null);
    setRestoreConfirmingTab(null);
    setRestorePushedError(null);
    setRestorePushedConfirmingTab(null);
    setRestoreRevisionConfirmingTab(null);
    setRestoreRevisionError(null);
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

  /** Runs the AI call right away in preview mode (commit=false — nothing touches Postgres) and,
   * once it succeeds, opens the confirm dialog with the real candidate text so the user reviews
   * an actual diff before anything real changes. {@link cancelGenerateConfirm} discards it with no
   * API call at all, since nothing was ever saved; {@link handleGenerate} is the only thing that
   * persists it. */
  async function requestGenerate(tab: EditableTab, force = false) {
    if (!project || !repo || !selectedEntry || !model) return;
    setGenError(null);
    setGenerating(tab);
    try {
      const res = await generateChangelog(
        project,
        repo,
        model,
        selectedEntry.version ?? undefined,
        selectedEntry.branch ?? undefined,
        undefined,
        undefined,
        tab,
        force,
        false,
      );
      const tokens = res.usage.reduce((sum, u) => sum + u.totalTokens, 0);
      setGenerateConfirm({ tab, force, text: res[tab], model, tokens, durationMs: res.durationMs });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to generate changelog.";
      setGenError(message);
      toast.error(`Failed to generate ${TAB_LABELS[tab]} changelog`, { description: message });
    } finally {
      setGenerating(null);
    }
  }

  function cancelGenerateConfirm() {
    setGenerateConfirm(null);
    setGenError(null);
  }

  /** Writes the previewed candidate from {@link generateConfirm} to Postgres exactly as shown —
   * no new AI call. Developer cascades forward the same way an edit does (see {@link
   * handleSaveEdit}): qa/business are stale the moment developer's text changes, so whichever
   * hasn't itself been hand-edited comes back regenerated in the same response. */
  async function handleGenerate() {
    if (!project || !repo || !selectedEntry || !generateConfirm) return;
    const { tab, force, text, model: usedModel, tokens, durationMs } = generateConfirm;
    setConfirmingGenerate(true);
    setGenError(null);
    try {
      const res = await commitChangelog(
        project,
        repo,
        selectedEntry.version ?? "",
        tab,
        usedModel,
        text,
        selectedEntry.branch ?? undefined,
        tokens,
        durationMs,
      );
      if (tab === "developer") {
        setDeveloperOverrides((prev) => ({ ...prev, [selectedEntry.id]: text }));
        setGeneratedByEntry((prev) => {
          const entryMap = { ...prev[selectedEntry.id] };
          if (res.qa) entryMap.qa = { text: res.qa };
          if (res.business) entryMap.business = { text: res.business };
          return { ...prev, [selectedEntry.id]: entryMap };
        });
        // Which of qa/business got freshly regenerated vs left as an existing edit isn't known
        // here — drop their cached meta so the lookup effect re-derives the truth next time
        // either tab is opened, same as handleSaveEdit's developer branch.
        setMetaByEntry((prev) => {
          const entryMap = { ...prev[selectedEntry.id] };
          delete entryMap.qa;
          delete entryMap.business;
          entryMap.developer = { source: "ai", model: usedModel, tokens, durationMs };
          return { ...prev, [selectedEntry.id]: entryMap };
        });
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [tab]: { text } },
        }));
        setMetaByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [tab]: { source: "ai", model: usedModel, tokens, durationMs } },
        }));
      }
      toast.success(`${TAB_LABELS[tab]} changelog ${force ? "regenerated" : "generated"} for v${selectedEntry.version}`, {
        description: "Saved to the database.",
      });
      setMutationCount((c) => c + 1);
      setGenerateConfirm(null);
    } catch (e) {
      // Confirm dialog stays open on failure — the error shows inline there, same as restore.
      const message = e instanceof Error ? e.message : "Failed to save the generated changelog.";
      setGenError(message);
      toast.error(`Failed to save ${TAB_LABELS[tab]} changelog`, { description: message });
    } finally {
      setConfirmingGenerate(false);
    }
  }

  function startEdit(tab: EditableTab, text: string) {
    setEditingTab(tab);
    setEditText(text);
    setEditError(null);
  }

  function cancelEdit() {
    setEditingTab(null);
    setEditError(null);
  }

  /** Opens the "are you sure" confirmation for a Save — doesn't call the API yet, that only
   * happens from {@link handleSaveEdit} once confirmed. */
  function requestSaveEdit(tab: EditableTab) {
    setEditError(null);
    setSaveConfirmingTab(tab);
  }

  function cancelSaveConfirm() {
    setSaveConfirmingTab(null);
    setEditError(null);
  }

  async function handleSaveEdit(tab: EditableTab) {
    if (!project || !repo || !selectedEntry) return;
    setEditSaving(true);
    setEditError(null);
    const role = getStoredRole() ?? undefined;
    try {
      const res = await saveChangelogEdit(
        project,
        repo,
        selectedEntry.version ?? "",
        tab,
        editText,
        role,
        selectedEntry.branch ?? undefined,
      );
      const editedAt = new Date().toISOString();
      if (tab === "developer") {
        setDeveloperOverrides((prev) => ({ ...prev, [selectedEntry.id]: editText }));
        // Editing developer cascades server-side: qa/business may have just been regenerated
        // from the new text (unless they carry their own edit, which the backend leaves alone).
        setGeneratedByEntry((prev) => {
          const entryMap = { ...prev[selectedEntry.id] };
          if (res.qa) entryMap.qa = { text: res.qa };
          if (res.business) entryMap.business = { text: res.business };
          return { ...prev, [selectedEntry.id]: entryMap };
        });
        // Which of the two happened above isn't known here — rather than guess, drop their
        // cached meta so the lookup effect re-derives the truth next time either tab is opened.
        setMetaByEntry((prev) => {
          const entryMap = { ...prev[selectedEntry.id] };
          delete entryMap.qa;
          delete entryMap.business;
          entryMap.developer = { source: "edit", editedBy: role, editedAt };
          return { ...prev, [selectedEntry.id]: entryMap };
        });
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [tab]: { text: editText } },
        }));
        setMetaByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [tab]: { source: "edit", editedBy: role, editedAt } },
        }));
      }
      setEditingTab(null);
      setSaveConfirmingTab(null);
      setMutationCount((c) => c + 1);
      toast.success(`${TAB_LABELS[tab]} changelog edit saved for v${selectedEntry.version}`, {
        description: "Saved to the database. Nothing has been pushed to the repo yet.",
      });
    } catch (e) {
      // Confirm dialog stays open on failure — the error shows inline there, same as restore.
      const message = e instanceof Error ? e.message : "Failed to save edit.";
      setEditError(message);
      toast.error(`Failed to save ${TAB_LABELS[tab]} edit`, { description: message });
    } finally {
      setEditSaving(false);
    }
  }

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

  /** Opens the "are you sure" confirmation for this tab, previewing what it would restore —
   * doesn't call the API yet, that only happens from {@link handleRestore} once confirmed. */
  function requestRestore(tab: EditableTab) {
    setRestoreError(null);
    setRestoreConfirmingTab(tab);
  }

  function cancelRestore() {
    setRestoreConfirmingTab(null);
    setRestoreError(null);
  }

  async function handleRestore(tab: EditableTab) {
    if (!project || !repo || !selectedEntry) return;
    setRestoring(true);
    setRestoreError(null);
    try {
      const restoredText = await restoreChangelogPrevious(project, repo, selectedEntry.version ?? "", tab);
      if (tab === "developer") {
        setDeveloperOverrides((prev) => ({ ...prev, [selectedEntry.id]: restoredText }));
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [tab]: { text: restoredText } },
        }));
      }
      // Restore's own source/model/editedBy aren't known here (it could have brought back either
      // an AI generation or an edit) — drop the cached meta so the lookup effect re-derives it.
      setMetaByEntry((prev) => {
        const entryMap = { ...prev[selectedEntry.id] };
        delete entryMap[tab];
        return { ...prev, [selectedEntry.id]: entryMap };
      });
      setRestoreConfirmingTab(null);
      toast.success(`${TAB_LABELS[tab]} changelog restored for v${selectedEntry.version}`, {
        description: "Reverted to the previous saved version in the database.",
      });
    } catch (e) {
      // Modal stays open on failure — the error shows inline there, next to the same Confirm
      // button, rather than being dismissed out from under the user.
      const message = e instanceof Error ? e.message : "Failed to restore the previous version.";
      setRestoreError(message);
      toast.error(`Failed to restore ${TAB_LABELS[tab]} changelog`, { description: message });
    } finally {
      setRestoring(false);
    }
  }

  /** Opens the "are you sure" confirmation for rolling back to the last pushed version — see
   * {@link requestRestore} for the same pattern applied to edit/regeneration history instead. */
  function requestRestorePushed(tab: EditableTab) {
    setRestorePushedError(null);
    setRestorePushedConfirmingTab(tab);
  }

  function cancelRestorePushed() {
    setRestorePushedConfirmingTab(null);
    setRestorePushedError(null);
  }

  async function handleRestorePushed(tab: EditableTab) {
    if (!project || !repo || !selectedEntry) return;
    setRestoringPushed(true);
    setRestorePushedError(null);
    try {
      const restoredText = await restoreChangelogToPushed(project, repo, selectedEntry.version ?? "", tab);
      if (tab === "developer") {
        setDeveloperOverrides((prev) => ({ ...prev, [selectedEntry.id]: restoredText }));
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [tab]: { text: restoredText } },
        }));
      }
      // Same reasoning as handleRestore: what this brought back could be either kind of prior
      // write, so drop the cached meta and let the lookup effect re-derive it.
      setMetaByEntry((prev) => {
        const entryMap = { ...prev[selectedEntry.id] };
        delete entryMap[tab];
        return { ...prev, [selectedEntry.id]: entryMap };
      });
      setRestorePushedConfirmingTab(null);
      toast.success(`${TAB_LABELS[tab]} changelog restored to the last pushed version for v${selectedEntry.version}`, {
        description: "Reverted in the database. Nothing new was pushed to the repo.",
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to restore the last pushed version.";
      setRestorePushedError(message);
      toast.error(`Failed to restore ${TAB_LABELS[tab]} changelog`, { description: message });
    } finally {
      setRestoringPushed(false);
    }
  }

  // Restores to a specific revision from the edit history (changelog_revision table) — more
  // precise than the generic one-step-back Restore above, which only undoes the last change.
  const [restoringRevision, setRestoringRevision] = useState(false);
  const [restoreRevisionError, setRestoreRevisionError] = useState<string | null>(null);
  const [restoreRevisionConfirmingTab, setRestoreRevisionConfirmingTab] = useState<EditableTab | null>(null);
  const [restoreRevisionSequence, setRestoreRevisionSequence] = useState(0);
  const [mutationCount, setMutationCount] = useState(0);

  function requestRestoreRevision(tab: EditableTab, sequence: number) {
    setRestoreRevisionError(null);
    setRestoreRevisionConfirmingTab(tab);
    setRestoreRevisionSequence(sequence);
  }

  function cancelRestoreRevision() {
    setRestoreRevisionConfirmingTab(null);
    setRestoreRevisionError(null);
  }

  async function handleRestoreRevision() {
    if (!project || !repo || !selectedEntry || !restoreRevisionConfirmingTab) return;
    setRestoringRevision(true);
    setRestoreRevisionError(null);
    try {
      const restoredText = await restoreChangelogRevision(
        project,
        repo,
        selectedEntry.version ?? "",
        restoreRevisionConfirmingTab,
        restoreRevisionSequence,
      );
      if (restoreRevisionConfirmingTab === "developer") {
        setDeveloperOverrides((prev) => ({ ...prev, [selectedEntry.id]: restoredText }));
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [selectedEntry.id]: { ...prev[selectedEntry.id], [restoreRevisionConfirmingTab]: { text: restoredText } },
        }));
      }
      setMetaByEntry((prev) => {
        const entryMap = { ...prev[selectedEntry.id] };
        delete entryMap[restoreRevisionConfirmingTab];
        return { ...prev, [selectedEntry.id]: entryMap };
      });
      setRestoreRevisionConfirmingTab(null);
      setMutationCount((c) => c + 1);
      toast.success(`${TAB_LABELS[restoreRevisionConfirmingTab]} changelog restored to revision #${restoreRevisionSequence} for v${selectedEntry.version}`, {
        description: "Reverted in the database.",
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to restore revision.";
      setRestoreRevisionError(message);
      toast.error(`Failed to restore revision #${restoreRevisionSequence}`, { description: message });
    } finally {
      setRestoringRevision(false);
    }
  }

  return {
    activeTab,
    setActiveTab,
    model,
    setModel,
    models,
    developerOverride,
    generated,
    checked,
    meta,
    generating,
    genError,
    setGenError,
    generateConfirm,
    confirmingGenerate,
    editingTab,
    editText,
    setEditText,
    editSaving,
    editError,
    saveConfirmingTab,
    restoring,
    restoreError,
    restoreConfirmingTab,
    restoringPushed,
    restorePushedError,
    restorePushedConfirmingTab,
    mutationCount,
    restoringRevision,
    restoreRevisionError,
    restoreRevisionConfirmingTab,
    restoreRevisionSequence,
    requestRestoreRevision,
    cancelRestoreRevision,
    handleRestoreRevision,
    requestGenerate,
    cancelGenerateConfirm,
    handleGenerate,
    startEdit,
    cancelEdit,
    requestSaveEdit,
    cancelSaveConfirm,
    handleSaveEdit,
    clearMeta,
    requestRestore,
    cancelRestore,
    handleRestore,
    requestRestorePushed,
    cancelRestorePushed,
    handleRestorePushed,
  };
}
