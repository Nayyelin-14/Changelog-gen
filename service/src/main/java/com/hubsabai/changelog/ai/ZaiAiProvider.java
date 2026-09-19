package com.hubsabai.changelog.ai;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Z.AI-specific provider that extends {@link NimAiProvider} with a curated free-model fallback.
 * The Z.AI {@code /models} endpoint does not expose the free Flash models ({@code glm-4.7-flash},
 * {@code glm-4.5-flash}, {@code glm-4.6v-flash}), but they are accessible via direct API calls.
 * This provider always includes the curated free Flash models in the dropdown, merging them with
 * any live-discovered models that pass health probes.
 */
public class ZaiAiProvider extends NimAiProvider {

    public ZaiAiProvider(
            String baseUrl,
            String model,
            String apiKey,
            Optional<String> fallbackModels,
            Optional<String> allowedModels,
            Optional<String> developerPrompt,
            Optional<String> qaPrompt,
            Optional<String> businessPrompt) {
        super(baseUrl, model, apiKey, fallbackModels, allowedModels, developerPrompt, qaPrompt, businessPrompt);
    }

    /**
     * Synchronous model listing — probes all candidates and returns only confirmed-healthy models.
     * For UI dropdown, use {@link #listModelsWithStatus()} which returns curated models immediately.
     */
    @Override
    public List<AiModelOption> listModels() {
        // Get live-discovered models (may be empty on failure)
        List<AiModelOption> live = tryLiveDiscovery();

        // Always include curated free Flash models — Z.AI-specific, never another provider's list
        List<AiModelOption> curated = new ArrayList<>(ZaiModelCatalog.FREE_MODELS);

        // Merge: curated models first (they're free), then live models that aren't already in curated
        Set<String> curatedIds = curated.stream().map(AiModelOption::id).collect(Collectors.toSet());
        List<AiModelOption> merged = new ArrayList<>(curated);
        for (AiModelOption liveModel : live) {
            if (!curatedIds.contains(liveModel.id())) {
                merged.add(liveModel);
            }
        }

        // Sort: requested model first, then curated free models (by recommendedRank), then others
        return merged.stream()
                .sorted(Comparator.<AiModelOption, Boolean>comparing(
                        m -> m.id().equals(model), Comparator.reverseOrder())
                        .thenComparingInt(m -> recommendedRank(m.id()))
                        .thenComparing(AiModelOption::label))
                .toList();
    }

    @Override
    public List<AiModelOption> listModelsWithStatus() {
        // Always include curated free Flash models — Z.AI-specific, never another provider's list
        List<AiModelOption> curated = new ArrayList<>();
        for (AiModelOption m : ZaiModelCatalog.FREE_MODELS) {
            curated.add(AiModelOption.available(m.id(), m.label(), m.recommended()));
        }

        // Best-effort live discovery (may be empty on failure)
        List<AiModelOption> live = tryLiveDiscoveryWithStatus();

        // Merge: curated models first (they're free), then live models that aren't already in curated
        Set<String> curatedIds = curated.stream().map(AiModelOption::id).collect(Collectors.toSet());
        List<AiModelOption> merged = new ArrayList<>(curated);
        for (AiModelOption liveModel : live) {
            if (!curatedIds.contains(liveModel.id())) {
                merged.add(liveModel);
            }
        }

        // Sort: requested model first, then curated free models (by recommendedRank), then others
        return merged.stream()
                .sorted(Comparator.<AiModelOption, Boolean>comparing(
                        m -> m.id().equals(model), Comparator.reverseOrder())
                        .thenComparingInt(m -> recommendedRank(m.id()))
                        .thenComparing(AiModelOption::label))
                .toList();
    }

    /**
     * Synchronous live model discovery — probes all candidates and returns only confirmed-healthy models.
     * Returns empty list on any failure.
     */
    private List<AiModelOption> tryLiveDiscovery() {
        String modelsUrl = baseUrl.replace("/chat/completions", "/models");
        List<String> candidateIds;
        try {
            Response raw = client.target(modelsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .get();
            NvidiaModelsResponse response = MAPPER.readValue(raw.readEntity(String.class), NvidiaModelsResponse.class);
            candidateIds = response.dataOrEmpty().stream()
                    .map(NvidiaModelsResponse.NvidiaModel::getId)
                    .filter(NimAiProvider::looksLikeChatModel)
                    .filter(id -> !EXCLUDED_MODELS.contains(id))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }

        List<String> probe = allowedModels.isEmpty()
                ? candidateIds
                : candidateIds.stream().filter(allowedModels::contains).toList();

        List<CompletableFuture<AiModelOption>> futures = probe.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> healthyOption(id), PROBE_POOL))
                .toList();

        List<AiModelOption> healthy = new ArrayList<>(futures.size());
        for (CompletableFuture<AiModelOption> future : futures) {
            try {
                AiModelOption option = future.join();
                if (option != null) healthy.add(option);
            } catch (Exception e) {
                // Individual probe failure — skip this model
            }
        }

        return healthy;
    }

    /**
     * Async-discovery model listing — returns curated models immediately (status=available),
     * live models as status=checking. Probes run in background without blocking the UI.
     */
    private List<AiModelOption> tryLiveDiscoveryWithStatus() {
        String modelsUrl = baseUrl.replace("/chat/completions", "/models");
        List<String> candidateIds;
        try {
            Response raw = client.target(modelsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .get();
            NvidiaModelsResponse response = MAPPER.readValue(raw.readEntity(String.class), NvidiaModelsResponse.class);
            candidateIds = response.dataOrEmpty().stream()
                    .map(NvidiaModelsResponse.NvidiaModel::getId)
                    .filter(NimAiProvider::looksLikeChatModel)
                    .filter(id -> !EXCLUDED_MODELS.contains(id))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }

        List<String> probe = allowedModels.isEmpty()
                ? candidateIds
                : candidateIds.stream().filter(allowedModels::contains).toList();

        long now = System.currentTimeMillis();
        List<AiModelOption> result = new ArrayList<>();

        for (String id : probe) {
            ModelHealth cached = MODEL_HEALTH.get(id);
            if (cached != null && cached.ok() && (now - cached.checkedAtMillis() < PROBE_OK_TTL_MS)) {
                result.add(AiModelOption.available(id, prettifyModelId(id), false));
            } else {
                // Not yet probed or cache expired — show as checking, probe in background
                result.add(AiModelOption.checking(id, prettifyModelId(id), false));
                CompletableFuture.runAsync(() -> {
                    try { healthyOption(id); } catch (Exception ignored) {}
                }, PROBE_POOL);
            }
        }

        return result;
    }

    // Z.AI-specific recommended model ranking
    @Override
    protected int recommendedRank(String id) {
        return switch (id) {
            case "glm-4.7-flash" -> 0;
            case "glm-4.5-flash" -> 1;
            case "glm-4.6v-flash" -> 2;
            default -> Integer.MAX_VALUE;
        };
    }
}