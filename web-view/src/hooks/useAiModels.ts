import { useCallback, useEffect, useRef, useState } from "react";

import { listAiModels, listAiProviders } from "@/api/client";
import type { AiModelOption, AiProviderOption } from "@/api/types";
import { useQuery } from "@/hooks/useQuery";
import {
  ensureStoredAiProviderValid,
  getStoredAiProvider,
  setStoredAiProvider,
} from "@/lib/aiProvider";

export interface UseAiModelsReturn {
  providers: AiProviderOption[];
  providerOptions: AiProviderOption[];
  provider: string;
  setProvider: (id: string) => void;
  models: AiModelOption[];
  modelsStatus: "loading" | "success" | "error";
  model: string | undefined;
  setModel: (id: string | undefined) => void;
  isReady: boolean;
  hasModels: boolean;
}

/**
 * Single source of truth for provider→model selection. Handles:
 * - Fetching enabled providers from the server
 * - Resolving the stored provider against the enabled set
 * - Fetching models for the selected provider
 * - Keeping provider and model in sync (provider change clears model)
 * - Auto-selecting the first model when models load
 */
export function useAiModels(): UseAiModelsReturn {
  const [provider, setProviderState] = useState<string>(() => getStoredAiProvider());
  const [model, setModel] = useState<string | undefined>(undefined);

  // Fetch enabled providers
  const providersQuery = useQuery(
    useCallback(() => listAiProviders(), []),
    [],
    { cacheKey: "ai-providers", ttlMs: 5 * 60_000 },
  );

  const allProviders: AiProviderOption[] =
    providersQuery.status === "success" ? providersQuery.data : [];
  const providerOptions = allProviders.filter((p) => p.enabled);
  const enabledIds = providerOptions.map((p) => p.id);

  // Resolve stored provider against enabled set
  const resolved = ensureStoredAiProviderValid(enabledIds);
  useEffect(() => {
    if (enabledIds.length > 0 && !enabledIds.includes(provider)) {
      setProviderState(resolved);
    }
  }, [enabledIds, provider, resolved]);

  // Fetch models for current provider
  const modelsQuery = useQuery(
    useCallback(() => listAiModels(provider), [provider]),
    [provider],
    { cacheKey: `ai-models-${provider}`, ttlMs: 5 * 60_000 },
  );

  const modelsRef = useRef<AiModelOption[]>([]);
  modelsRef.current = modelsQuery.status === "success" ? modelsQuery.data : [];
  const models = modelsRef.current;
  const modelsStatus = modelsQuery.status;

  // Auto-select first model when models load and no model is selected
  useEffect(() => {
    if (modelsStatus === "success" && models.length > 0 && !model) {
      setModel(models[0].id);
    }
  }, [modelsStatus, models, model]);

  // When provider changes, clear model and result model
  const setProvider = useCallback((id: string) => {
    setStoredAiProvider(id);
    setProviderState(id);
    setModel(undefined);
  }, []);

  // Validate that the current model belongs to the current provider
  useEffect(() => {
    if (modelsStatus === "success" && model) {
      const modelExists = models.some((m) => m.id === model);
      if (!modelExists) {
        setModel(models.length > 0 ? models[0].id : undefined);
      }
    }
  }, [modelsStatus, models, model]);

  return {
    providers: allProviders,
    providerOptions,
    provider,
    setProvider,
    models,
    modelsStatus,
    model,
    setModel,
    isReady: modelsStatus === "success" && models.length > 0,
    hasModels: models.length > 0,
  };
}
