import { useCallback, useState } from "react";
import { toast } from "sonner";

import {
  restoreChangelogPrevious,
  restoreChangelogToPushed,
  restoreChangelogRevision,
} from "@/api/client";
import type { EditableTab, GeneratedMeta } from "./useChangelogState";
import { TAB_LABELS } from "./useChangelogState";

export interface UseChangelogRestoreReturn {
  restoring: boolean;
  restoreError: string | null;
  restoreConfirmingTab: EditableTab | null;
  restoringPushed: boolean;
  restorePushedError: string | null;
  restorePushedConfirmingTab: EditableTab | null;
  restoringRevision: boolean;
  restoreRevisionError: string | null;
  restoreRevisionConfirmingTab: EditableTab | null;
  restoreRevisionSequence: number;
  requestRestore: (tab: EditableTab) => void;
  cancelRestore: () => void;
  handleRestore: (tab: EditableTab) => Promise<void>;
  requestRestorePushed: (tab: EditableTab) => void;
  cancelRestorePushed: () => void;
  handleRestorePushed: (tab: EditableTab) => Promise<void>;
  requestRestoreRevision: (tab: EditableTab, sequence: number) => void;
  cancelRestoreRevision: () => void;
  handleRestoreRevision: () => Promise<void>;
}

export function useChangelogRestore(
  project: string | undefined,
  repo: string | undefined,
  selectedEntry: { version?: string | null } | undefined,
  setGeneratedByEntry: React.Dispatch<React.SetStateAction<Record<string, Partial<Record<EditableTab, { text: string }>>>>>,
  setMetaByEntry: React.Dispatch<React.SetStateAction<Record<string, Partial<Record<EditableTab, GeneratedMeta>>>>>,
  setDeveloperOverrides: React.Dispatch<React.SetStateAction<Record<string, string>>>,
  entryId: string | undefined,
  setMutationCount: React.Dispatch<React.SetStateAction<number>>,
): UseChangelogRestoreReturn {
  const [restoring, setRestoring] = useState(false);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const [restoreConfirmingTab, setRestoreConfirmingTab] = useState<EditableTab | null>(null);

  const [restoringPushed, setRestoringPushed] = useState(false);
  const [restorePushedError, setRestorePushedError] = useState<string | null>(null);
  const [restorePushedConfirmingTab, setRestorePushedConfirmingTab] = useState<EditableTab | null>(null);

  const [restoringRevision, setRestoringRevision] = useState(false);
  const [restoreRevisionError, setRestoreRevisionError] = useState<string | null>(null);
  const [restoreRevisionConfirmingTab, setRestoreRevisionConfirmingTab] = useState<EditableTab | null>(null);
  const [restoreRevisionSequence, setRestoreRevisionSequence] = useState(0);

  const requestRestore = useCallback((tab: EditableTab) => {
    setRestoreError(null);
    setRestoreConfirmingTab(tab);
  }, []);

  const cancelRestore = useCallback(() => {
    setRestoreConfirmingTab(null);
    setRestoreError(null);
  }, []);

  const handleRestore = useCallback(
    async (tab: EditableTab) => {
      if (!project || !repo || !selectedEntry || !entryId) return;
      setRestoring(true);
      setRestoreError(null);
      try {
        const restoredText = await restoreChangelogPrevious(project, repo, selectedEntry.version ?? "", tab);
        if (tab === "developer") {
          setDeveloperOverrides((prev) => ({ ...prev, [entryId]: restoredText }));
        } else {
          setGeneratedByEntry((prev) => ({
            ...prev,
            [entryId]: { ...prev[entryId], [tab]: { text: restoredText } },
          }));
        }
        setMetaByEntry((prev) => {
          const entryMap = { ...prev[entryId] };
          delete entryMap[tab];
          return { ...prev, [entryId]: entryMap };
        });
        setRestoreConfirmingTab(null);
        toast.success(`${TAB_LABELS[tab]} changelog restored for v${selectedEntry.version}`, {
          description: "Reverted to the previous saved version in the database.",
        });
      } catch (e) {
        const message = e instanceof Error ? e.message : "Failed to restore the previous version.";
        setRestoreError(message);
        toast.error(`Failed to restore ${TAB_LABELS[tab]} changelog`, { description: message });
      } finally {
        setRestoring(false);
      }
    },
    [project, repo, selectedEntry, entryId, setGeneratedByEntry, setMetaByEntry, setDeveloperOverrides],
  );

  const requestRestorePushed = useCallback((tab: EditableTab) => {
    setRestorePushedError(null);
    setRestorePushedConfirmingTab(tab);
  }, []);

  const cancelRestorePushed = useCallback(() => {
    setRestorePushedConfirmingTab(null);
    setRestorePushedError(null);
  }, []);

  const handleRestorePushed = useCallback(
    async (tab: EditableTab) => {
      if (!project || !repo || !selectedEntry || !entryId) return;
      setRestoringPushed(true);
      setRestorePushedError(null);
      try {
        const restoredText = await restoreChangelogToPushed(project, repo, selectedEntry.version ?? "", tab);
        if (tab === "developer") {
          setDeveloperOverrides((prev) => ({ ...prev, [entryId]: restoredText }));
        } else {
          setGeneratedByEntry((prev) => ({
            ...prev,
            [entryId]: { ...prev[entryId], [tab]: { text: restoredText } },
          }));
        }
        setMetaByEntry((prev) => {
          const entryMap = { ...prev[entryId] };
          delete entryMap[tab];
          return { ...prev, [entryId]: entryMap };
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
    },
    [project, repo, selectedEntry, entryId, setGeneratedByEntry, setMetaByEntry, setDeveloperOverrides],
  );

  const requestRestoreRevision = useCallback((tab: EditableTab, sequence: number) => {
    setRestoreRevisionError(null);
    setRestoreRevisionConfirmingTab(tab);
    setRestoreRevisionSequence(sequence);
  }, []);

  const cancelRestoreRevision = useCallback(() => {
    setRestoreRevisionConfirmingTab(null);
    setRestoreRevisionError(null);
  }, []);

  const handleRestoreRevision = useCallback(async () => {
    if (!project || !repo || !selectedEntry || !entryId || !restoreRevisionConfirmingTab) return;
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
        setDeveloperOverrides((prev) => ({ ...prev, [entryId]: restoredText }));
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [entryId]: { ...prev[entryId], [restoreRevisionConfirmingTab]: { text: restoredText } },
        }));
      }
      setMetaByEntry((prev) => {
        const entryMap = { ...prev[entryId] };
        delete entryMap[restoreRevisionConfirmingTab];
        return { ...prev, [entryId]: entryMap };
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
  }, [
    project,
    repo,
    selectedEntry,
    entryId,
    restoreRevisionConfirmingTab,
    restoreRevisionSequence,
    setGeneratedByEntry,
    setMetaByEntry,
    setDeveloperOverrides,
    setMutationCount,
  ]);

  return {
    restoring,
    restoreError,
    restoreConfirmingTab,
    restoringPushed,
    restorePushedError,
    restorePushedConfirmingTab,
    restoringRevision,
    restoreRevisionError,
    restoreRevisionConfirmingTab,
    restoreRevisionSequence,
    requestRestore,
    cancelRestore,
    handleRestore,
    requestRestorePushed,
    cancelRestorePushed,
    handleRestorePushed,
    requestRestoreRevision,
    cancelRestoreRevision,
    handleRestoreRevision,
  };
}