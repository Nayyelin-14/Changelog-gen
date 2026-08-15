import { useEffect, useState } from "react";

import { useChangelogState } from "./useChangelogState";
import { useChangelogGeneration } from "./useChangelogGeneration";
import { useChangelogEdit } from "./useChangelogEdit";
import { useChangelogRestore } from "./useChangelogRestore";
import type { GenerationRecord } from "@/api/types";

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
  const [mutationCount, setMutationCount] = useState(0);

  const state = useChangelogState(project, repo, selectedEntry);

  const generation = useChangelogGeneration(
    project,
    repo,
    selectedEntry ? { version: selectedEntry.version, branch: selectedEntry.branch } : undefined,
    state.model,
    state.setGeneratedByEntry,
    state.setMetaByEntry,
    state.setDeveloperOverrides,
    state.entryId,
    setMutationCount,
  );

  const edit = useChangelogEdit(
    project,
    repo,
    selectedEntry ? { version: selectedEntry.version, branch: selectedEntry.branch } : undefined,
    state.setGeneratedByEntry,
    state.setMetaByEntry,
    state.setDeveloperOverrides,
    state.entryId,
    setMutationCount,
  );

  const restore = useChangelogRestore(
    project,
    repo,
    selectedEntry ? { version: selectedEntry.version } : undefined,
    state.setGeneratedByEntry,
    state.setMetaByEntry,
    state.setDeveloperOverrides,
    state.entryId,
    setMutationCount,
  );

  // Clear interaction state on version switch (same as original)
  useEffect(() => {
    if (!state.entryId) return;
    state.setActiveTab("developer");
    generation.setGenError(null);
    generation.cancelGenerateConfirm();
    edit.cancelEdit();
    edit.cancelSaveConfirm();
    restore.cancelRestore();
    restore.cancelRestorePushed();
    restore.cancelRestoreRevision();
  }, [state.entryId, generation, edit, restore]);

  return {
    // State
    activeTab: state.activeTab,
    setActiveTab: state.setActiveTab,
    model: state.model,
    setModel: state.setModel,
    models: state.models,
    developerOverride: state.developerOverride,
    generated: state.generated,
    checked: state.checked,
    meta: state.meta,
    mutationCount,
    // Generation
    generating: generation.generating,
    genError: generation.genError,
    setGenError: generation.setGenError,
    generateConfirm: generation.generateConfirm,
    confirmingGenerate: generation.confirmingGenerate,
    // Edit
    editingTab: edit.editingTab,
    editText: edit.editText,
    setEditText: edit.setEditText,
    editSaving: edit.editSaving,
    editError: edit.editError,
    saveConfirmingTab: edit.saveConfirmingTab,
    // Restore (previous)
    restoring: restore.restoring,
    restoreError: restore.restoreError,
    restoreConfirmingTab: restore.restoreConfirmingTab,
    // Restore (pushed)
    restoringPushed: restore.restoringPushed,
    restorePushedError: restore.restorePushedError,
    restorePushedConfirmingTab: restore.restorePushedConfirmingTab,
    // Restore (revision)
    restoringRevision: restore.restoringRevision,
    restoreRevisionError: restore.restoreRevisionError,
    restoreRevisionConfirmingTab: restore.restoreRevisionConfirmingTab,
    restoreRevisionSequence: restore.restoreRevisionSequence,
    // Actions
    requestGenerate: generation.requestGenerate,
    cancelGenerateConfirm: generation.cancelGenerateConfirm,
    handleGenerate: generation.handleGenerate,
    startEdit: edit.startEdit,
    cancelEdit: edit.cancelEdit,
    requestSaveEdit: edit.requestSaveEdit,
    cancelSaveConfirm: edit.cancelSaveConfirm,
    handleSaveEdit: edit.handleSaveEdit,
    clearMeta: state.clearMeta,
    requestRestore: restore.requestRestore,
    cancelRestore: restore.cancelRestore,
    handleRestore: restore.handleRestore,
    requestRestorePushed: restore.requestRestorePushed,
    cancelRestorePushed: restore.cancelRestorePushed,
    handleRestorePushed: restore.handleRestorePushed,
    requestRestoreRevision: restore.requestRestoreRevision,
    cancelRestoreRevision: restore.cancelRestoreRevision,
    handleRestoreRevision: restore.handleRestoreRevision,
  };
}

export type { EditableTab } from "./useChangelogState";
export type { GenerationConfirmState } from "./useChangelogGeneration";