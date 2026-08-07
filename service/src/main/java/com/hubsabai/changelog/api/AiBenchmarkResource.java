package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.ai.AiModelCatalog;
import com.hubsabai.changelog.ai.AiProvider;
import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector;
import com.hubsabai.changelog.core.model.ReleaseData;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measures which model is actually reliable, rather than asserting it: runs N trials per
 * candidate model against one real release and reports success rate + latency percentiles.
 * Uses generateForAudienceStrict so a model's failures can't be masked by the provider's
 * automatic fallback to the configured default.
 */
@Path("/ai/models/bench")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AiBenchmarkResource {

    @Inject
    AzureDevOpsOrgConnector orgConnector;

    @Inject
    AiProvider aiProvider;

    @POST
    public List<ModelBenchResult> benchmark(BenchmarkRequest request) {
        if (request.getProject() == null || request.getRepo() == null || request.getVersion() == null) {
            throw new AiException("project, repo, and version are required to benchmark against a real release.");
        }

        ReleaseData data = orgConnector.fetchRepoChanges(
                request.getProject(), request.getRepo(), request.getFromVersion(), request.getVersion(), request.getBranch());
        if (data.getItems().isEmpty()) {
            throw new AiException("No commits or PRs found for v" + request.getVersion()
                    + " — nothing to benchmark against.");
        }

        List<String> candidates = request.getModels() != null && !request.getModels().isEmpty()
                ? request.getModels()
                : defaultCandidates();
        int trials = Math.max(1, Math.min(10, request.getTrials()));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ModelBenchResult>> futures = new ArrayList<>();
            for (String candidate : candidates) {
                futures.add(executor.submit(() -> benchmarkModel(candidate, data, trials)));
            }
            List<ModelBenchResult> results = new ArrayList<>();
            for (Future<ModelBenchResult> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    private ModelBenchResult benchmarkModel(String modelId, ReleaseData data, int trials) {
        List<Long> latenciesMs = new ArrayList<>();
        LinkedHashSet<String> errors = new LinkedHashSet<>();
        int successes = 0;
        String sampleOutput = null;
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        long totalTokens = 0;

        for (int i = 0; i < trials; i++) {
            long start = System.currentTimeMillis();
            try {
                AiResult result = aiProvider.generateForAudienceStrict(data.getItems(), data.getRelease(), "developer", modelId);
                sampleOutput = result.getText();
                successes++;
                if (result.getUsage() != null) {
                    totalPromptTokens += result.getUsage().getPromptTokens();
                    totalCompletionTokens += result.getUsage().getCompletionTokens();
                    totalTokens += result.getUsage().getTotalTokens();
                }
            } catch (Exception e) {
                errors.add(truncate(e.getMessage()));
            } finally {
                latenciesMs.add(System.currentTimeMillis() - start);
            }
        }

        latenciesMs.sort(Long::compareTo);
        Long p50 = successes > 0 ? percentile(latenciesMs, 0.50) : null;
        Long p95 = successes > 0 ? percentile(latenciesMs, 0.95) : null;
        Integer avgPrompt = successes > 0 ? (int) (totalPromptTokens / successes) : null;
        Integer avgCompletion = successes > 0 ? (int) (totalCompletionTokens / successes) : null;
        Integer avgTotal = successes > 0 ? (int) (totalTokens / successes) : null;

        return new ModelBenchResult(
                modelId, trials, successes, trials - successes,
                (successes * 100.0) / trials, p50, p95, new ArrayList<>(errors), sampleOutput,
                avgPrompt, avgCompletion, avgTotal);
    }

    private static Long percentile(List<Long> sortedLatenciesMs, double p) {
        int index = Math.min(sortedLatenciesMs.size() - 1, (int) Math.ceil(p * sortedLatenciesMs.size()) - 1);
        return sortedLatenciesMs.get(Math.max(0, index));
    }

    private static String truncate(String message) {
        if (message == null) return "unknown error";
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    /** Union of both curated lists in the codebase — they've drifted apart, which is exactly what this endpoint should settle. */
    private List<String> defaultCandidates() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            aiProvider.listModels().stream().filter(m -> m.recommended()).forEach(m -> ids.add(m.id()));
        } catch (Exception ignored) {
            // Live catalog unreachable — fall through to the static list below.
        }
        AiModelCatalog.FREE_MODELS.forEach(m -> ids.add(m.id()));
        return new ArrayList<>(ids);
    }

    private static ModelBenchResult await(Future<ModelBenchResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Benchmark was interrupted", e);
        } catch (Exception e) {
            throw new AiException("Benchmark failed: " + e.getMessage(), e);
        }
    }
}
