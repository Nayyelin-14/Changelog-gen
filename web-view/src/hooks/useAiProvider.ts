import { useCallback, useEffect } from "react";

import { listAiProviders } from "@/api/client";
import type { AiProviderOption } from "@/api/types";
import { useQuery } from "@/hooks/useQuery";
import {
  ensureStoredAiProviderValid,
  getStoredAiProvider,
  resolveAiProvider,
} from "@/lib/aiProvider";

export interface ResolvedAiProvider {
  provider: string;
  options: AiProviderOption[];
  enabled: string[];
  stored: string;
}

/**
 * Single source of truth for "which AI provider do we actually send on a call". The stored
 * value can outlive the admin's enabled set (key revoked, old picker choice), and an unknown
 * provider id makes the backend reject the call — so this resolves to a provider the server
 * serves, and persists the correction so even the code paths that read localStorage directly
 * (chat, SSE) get the right one. Deduped across all callers via useQuery's shared cache.
 */
export function useResolvedAiProvider(): ResolvedAiProvider {
  const providers = useQuery(
    useCallback(() => listAiProviders(), []),
    [],
    { cacheKey: "ai-providers", ttlMs: 5 * 60_000 },
  );
  const options =
    providers.status === "success"
      ? providers.data.filter((p) => p.enabled)
      : [];
  const enabled = options.map((p) => p.id);
  const stored = getStoredAiProvider();
  const provider =
    enabled.length > 0
      ? resolveAiProvider(stored, enabled)
      : stored;

  useEffect(() => {
    ensureStoredAiProviderValid(enabled);
  }, [enabled]);

  return { provider, options, enabled, stored };
}