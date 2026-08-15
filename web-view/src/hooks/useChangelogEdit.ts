import { useCallback, useState } from "react";
import { toast } from "sonner";

import { saveChangelogEdit } from "@/api/client";
import { getStoredRole } from "@/lib/role";
import type { EditableTab, GeneratedMeta } from "./useChangelogState";
import { TAB_LABELS } from "./useChangelogState";

export interface UseChangelogEditReturn {
  editingTab: EditableTab | null;
  editText: string;
  setEditText: (text: string) => void;
  editSaving: boolean;
  editError: string | null;
  saveConfirmingTab: EditableTab | null;
  startEdit: (tab: EditableTab, text: string) => void;
  cancelEdit: () => void;
  requestSaveEdit: (tab: EditableTab) => void;
  cancelSaveConfirm: () => void;
  handleSaveEdit: (tab: EditableTab) => Promise<void>;
}

export function useChangelogEdit(
  project: string | undefined,
  repo: string | undefined,
  selectedEntry: { version?: string | null; branch?: string | null } | undefined,
  setGeneratedByEntry: React.Dispatch<React.SetStateAction<Record<string, Partial<Record<EditableTab, { text: string }>>>>>,
  setMetaByEntry: React.Dispatch<React.SetStateAction<Record<string, Partial<Record<EditableTab, GeneratedMeta>>>>>,
  setDeveloperOverrides: React.Dispatch<React.SetStateAction<Record<string, string>>>,
  entryId: string | undefined,
  setMutationCount: React.Dispatch<React.SetStateAction<number>>,
): UseChangelogEditReturn {
  const [editingTab, setEditingTab] = useState<EditableTab | null>(null);
  const [editText, setEditText] = useState("");
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  const [saveConfirmingTab, setSaveConfirmingTab] = useState<EditableTab | null>(null);

  const startEdit = useCallback((tab: EditableTab, text: string) => {
    setEditingTab(tab);
    setEditText(text);
    setEditError(null);
  }, []);

  const cancelEdit = useCallback(() => {
    setEditingTab(null);
    setEditError(null);
  }, []);

  const requestSaveEdit = useCallback((tab: EditableTab) => {
    setEditError(null);
    setSaveConfirmingTab(tab);
  }, []);

  const cancelSaveConfirm = useCallback(() => {
    setSaveConfirmingTab(null);
    setEditError(null);
  }, []);

  const handleSaveEdit = useCallback(
    async (tab: EditableTab) => {
      if (!project || !repo || !selectedEntry || !entryId) return;
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
          setDeveloperOverrides((prev) => ({ ...prev, [entryId]: editText }));
          setGeneratedByEntry((prev) => {
            const entryMap = { ...prev[entryId] };
            if (res.qa) entryMap.qa = { text: res.qa };
            if (res.business) entryMap.business = { text: res.business };
            return { ...prev, [entryId]: entryMap };
          });
          setMetaByEntry((prev) => {
            const entryMap = { ...prev[entryId] };
            delete entryMap.qa;
            delete entryMap.business;
            entryMap.developer = { source: "edit", editedBy: role, editedAt };
            return { ...prev, [entryId]: entryMap };
          });
        } else {
          setGeneratedByEntry((prev) => ({
            ...prev,
            [entryId]: { ...prev[entryId], [tab]: { text: editText } },
          }));
          setMetaByEntry((prev) => ({
            ...prev,
            [entryId]: { ...prev[entryId], [tab]: { source: "edit", editedBy: role, editedAt } },
          }));
        }
        setEditingTab(null);
        setSaveConfirmingTab(null);
        setMutationCount((c) => c + 1);
        toast.success(`${TAB_LABELS[tab]} changelog edit saved for v${selectedEntry.version}`, {
          description: "Saved to the database. Nothing has been pushed to the repo yet.",
        });
      } catch (e) {
        const message = e instanceof Error ? e.message : "Failed to save edit.";
        setEditError(message);
        toast.error(`Failed to save ${TAB_LABELS[tab]} edit`, { description: message });
      } finally {
        setEditSaving(false);
      }
    },
    [
      project,
      repo,
      selectedEntry,
      entryId,
      editText,
      setGeneratedByEntry,
      setMetaByEntry,
      setDeveloperOverrides,
      setMutationCount,
    ],
  );

  return {
    editingTab,
    editText,
    setEditText,
    editSaving,
    editError,
    saveConfirmingTab,
    startEdit,
    cancelEdit,
    requestSaveEdit,
    cancelSaveConfirm,
    handleSaveEdit,
  };
}