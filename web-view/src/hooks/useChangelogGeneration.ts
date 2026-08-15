import { useCallback, useState } from "react";
import { toast } from "sonner";

import { commitChangelog, generateChangelog } from "@/api/client";
import type { EditableTab, GeneratedMeta } from "./useChangelogState";
import { TAB_LABELS } from "./useChangelogState";

export interface GenerationConfirmState {
  tab: EditableTab;
  force: boolean;
  text: string;
  model: string;
  tokens: number;
  durationMs: number;
}

export interface UseChangelogGenerationReturn {
  generating: EditableTab | null;
  genError: string | null;
  setGenError: (error: string | null) => void;
  generateConfirm: GenerationConfirmState | null;
  confirmingGenerate: boolean;
  requestGenerate: (tab: EditableTab, force?: boolean) => Promise<void>;
  cancelGenerateConfirm: () => void;
  handleGenerate: () => Promise<void>;
}

export function useChangelogGeneration(
  project: string | undefined,
  repo: string | undefined,
  selectedEntry: { version?: string | null; branch?: string | null } | undefined,
  model: string | undefined,
  setGeneratedByEntry: React.Dispatch<React.SetStateAction<Record<string, Partial<Record<EditableTab, { text: string }>>>>>,
  setMetaByEntry: React.Dispatch<React.SetStateAction<Record<string, Partial<Record<EditableTab, GeneratedMeta>>>>>,
  setDeveloperOverrides: React.Dispatch<React.SetStateAction<Record<string, string>>>,
  entryId: string | undefined,
  setMutationCount: React.Dispatch<React.SetStateAction<number>>,
): UseChangelogGenerationReturn {
  const [generating, setGenerating] = useState<EditableTab | null>(null);
  const [genError, setGenError] = useState<string | null>(null);
  const [generateConfirm, setGenerateConfirm] = useState<GenerationConfirmState | null>(null);
  const [confirmingGenerate, setConfirmingGenerate] = useState(false);

  const requestGenerate = useCallback(
    async (tab: EditableTab, force = false) => {
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
    },
    [project, repo, selectedEntry, model],
  );

  const cancelGenerateConfirm = useCallback(() => {
    setGenerateConfirm(null);
    setGenError(null);
  }, []);

  const handleGenerate = useCallback(async () => {
    if (!project || !repo || !selectedEntry || !generateConfirm || !entryId) return;
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
        setDeveloperOverrides((prev) => ({ ...prev, [entryId]: text }));
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
          entryMap.developer = { source: "ai", model: usedModel, tokens, durationMs };
          return { ...prev, [entryId]: entryMap };
        });
      } else {
        setGeneratedByEntry((prev) => ({
          ...prev,
          [entryId]: { ...prev[entryId], [tab]: { text } },
        }));
        setMetaByEntry((prev) => ({
          ...prev,
          [entryId]: { ...prev[entryId], [tab]: { source: "ai", model: usedModel, tokens, durationMs } },
        }));
      }
      toast.success(`${TAB_LABELS[tab]} changelog ${force ? "regenerated" : "generated"} for v${selectedEntry.version}`, {
        description: "Saved to the database.",
      });
      setMutationCount((c) => c + 1);
      setGenerateConfirm(null);
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to save the generated changelog.";
      setGenError(message);
      toast.error(`Failed to save ${TAB_LABELS[tab]} changelog`, { description: message });
    } finally {
      setConfirmingGenerate(false);
    }
  }, [
    project,
    repo,
    selectedEntry,
    generateConfirm,
    entryId,
    setGeneratedByEntry,
    setMetaByEntry,
    setDeveloperOverrides,
    setMutationCount,
  ]);

  return {
    generating,
    genError,
    setGenError,
    generateConfirm,
    confirmingGenerate,
    requestGenerate,
    cancelGenerateConfirm,
    handleGenerate,
  };
}