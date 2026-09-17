package com.hubsabai.changelog.ai;

import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics helper — NOT part of the normal test suite (skipped unless
 * -Dmodel.latency.probe=true). Measures, against the live NVIDIA NIM endpoint using the same
 * code path as the app, how long each health-checked model actually takes to generate a
 * changelog for a representative release. Run:
 *
 *   ./mvnw test -Dtest=ModelLatencyProbeTest -Dmodel.latency.probe=true
 *
 * The default candidate list is every model the picker surfaces as working (health-checked).
 * Cap with -Dmodel.latency.probe.maxModels=N and -Dmodel.latency.probe.trials=N.
 */
public class ModelLatencyProbeTest {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModelLatencyProbeTest.class);

    @Test
    void measuresGenerationLatencyPerWorkingModel() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("model.latency.probe")),
                "skipped: run with -Dmodel.latency.probe=true");

        String baseUrl = envOrSystem("AI_BASE_URL", "https://integrate.api.nvidia.com/v1/chat/completions");
        String apiKey = envOrSystem("AI_API_KEY", "");
        Assumptions.assumeFalse(apiKey.isBlank(), "skipped: no AI_API_KEY");
        String defaultModel = envOrSystem("AI_MODEL", "meta/llama-3.2-11b-vision-instruct");

        int maxModels = Integer.getInteger("model.latency.probe.maxModels", Integer.MAX_VALUE);
        int trials = Integer.max(1, Integer.min(3, Integer.getInteger("model.latency.probe.trials", 1)));
        int concurrency = Integer.max(1, Integer.min(6, Integer.getInteger("model.latency.probe.concurrency", 3)));

        NimAiProvider provider = new NimAiProvider(
                baseUrl, defaultModel, apiKey,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        List<AiModelOption> working = new ArrayList<>(provider.listModels());
        if (working.isEmpty()) {
            LOG.warn("Live catalog probe returned nothing");
            return;
        }
        working.sort(Comparator.comparingInt(o -> recommendedRank(o.id())));
        Set<String> candidates = new LinkedHashSet<>();
        working.stream().limit(maxModels).forEach(o -> candidates.add(o.id()));
        AiModelCatalog.FREE_MODELS.forEach(o -> candidates.add(o.id()));
        LOG.info("Benchmarking {} candidate models against {} ({} trial(s) each)", candidates.size(), baseUrl, trials);

        ReleaseData data = representativeRelease();

        List<String> ordered = new ArrayList<>(candidates);
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<Row>> futures = new ArrayList<>();
            for (String candidate : ordered) {
                futures.add(pool.submit(() -> run(provider, candidate, data, trials)));
            }
            List<Row> rows = new ArrayList<>();
            for (Future<Row> f : futures) {
                rows.add(f.get());
            }
            rows.sort(Comparator.<Row>comparingLong(r -> r.failures > 0 ? Long.MAX_VALUE : r.avgLatencyMs)
                    .thenComparing(r -> r.avgLatencyMs));

            StringBuilder table = new StringBuilder("\n=== MODEL GENERATION LATENCY (" + baseUrl + ") ===\n");
            table.append(String.format("%-45s %-8s %-10s %-9s %-9s %-9s %s%n",
                    "MODEL", "OK", "AVG(s)", "P50(s)", "P95(s)", "OUT-TOK", "ERROR"));
            for (Row r : rows) {
                table.append(String.format("%-45s %-8s %-10s %-9s %-9s %-9s %s%n",
                        truncate(r.model, 45),
                        r.successes + "/" + r.trials,
                        fmtSec(r.avgLatencyMs),
                        fmtSec(r.p50),
                        fmtSec(r.p95),
                        r.avgCompletionTokens == null ? "-" : String.valueOf(r.avgCompletionTokens),
                        truncate(r.firstError, 70)));
            }
            LOG.info("\n{}", table);
        }
    }

    private record Row(String model, int trials, int successes, int failures, long avgLatencyMs,
                       Long p50, Long p95, Integer avgCompletionTokens, String firstError) {
    }

    private static Row run(NimAiProvider provider, String model, ReleaseData data, int trials) {
        List<Long> latencies = new ArrayList<>();
        int successes = 0;
        long completionTokens = 0;
        String firstError = null;
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            try {
                AiResult result = provider.generateForAudienceStrict(
                        data.getItems(), data.getRelease(), "developer", model);
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                latencies.add(latencyMs);
                successes++;
                if (result.getUsage() != null) {
                    completionTokens += result.getUsage().getCompletionTokens();
                }
            } catch (Exception e) {
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                latencies.add(latencyMs);
                if (firstError == null) {
                    firstError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        }
        latencies.sort(Long::compareTo);
        Long p50 = successes > 0 ? percentile(latencies, 0.50) : null;
        Long p95 = successes > 0 ? percentile(latencies, 0.95) : null;
        Integer avgOut = successes > 0 ? (int) (completionTokens / successes) : null;
        long avg = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        return new Row(model, trials, successes, trials - successes, avg, p50, p95, avgOut, firstError);
    }

    private static String envOrSystem(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            v = System.getProperty(key);
        }
        if (v == null || v.isBlank()) {
            v = loadFromDotEnv(key);
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String loadFromDotEnv(String key) {
        for (Path candidate : new Path[]{Path.of("../.env"), Path.of(".env")}) {
            try {
                if (Files.isReadable(candidate)) {
                    for (String line : Files.readAllLines(candidate)) {
                        if (line.startsWith(key + "=")) {
                            return line.substring(key.length() + 1).trim();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int recommendedRank(String id) {
        List<String> recommended = List.of(
                "meta/llama-3.2-11b-vision-instruct",
                "nvidia/nemotron-3-super-120b-a12b",
                "meta/muse-glimmer-30b",
                "nvidia/nemotron-3.5-lightning-30b-a3b",
                "openai/gpt-oss-20b",
                "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                "nvidia/nemotron-3-ultra-550b-a55b");
        int rank = recommended.indexOf(id);
        return rank < 0 ? Integer.MAX_VALUE : rank;
    }

    private static Long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return null;
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static String fmtSec(Long ms) {
        if (ms == null) return "-";
        return String.format("%.1f", ms / 1000.0);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static ReleaseData representativeRelease() {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("hubsabai");
        meta.setProject("changelog-composer");
        meta.setRepo("changelog-composer");
        meta.setBranch("main");
        meta.setMilestone("v1.0.24");
        meta.setReleaseDate("2026-09-17");
        data.setRelease(meta);

        List<ChangeItem> items = new ArrayList<>();
        addPr(items, "PR-142", "feat(drafts): persist run-keyed regen/edit", "Save edits to the recorded run's AI draft text so a refresh keeps the new changelog instead of showing the old one; every save pushes the previous draft into per-run revision history (max 25).");
        addPr(items, "PR-143", "feat(drafts): draft revision history", "Adds ai_draft_source, ai_draft_edited_by and ai_draft_history columns via Flyway V18; changelog-meta accepts a buildId for run-keyed drafts; the edit-history panel lists and restores draft revisions.");
        addPr(items, "PR-144", "feat(push): guard against duplicate versions", "The push modal now shows the latest version already in the repo plus a suggested next version, and refuses to enter a version that already exists unless it is the entry's own.");
        addPr(items, "PR-141", "fix(history): show saved AI text for run drafts", "Run-keyed changelog entries now surface the saved AI draft text instead of the run title after a refresh.");
        addPr(items, "PR-140", "fix(ai): retry model probes", "Health-probe catalog models so only working ones appear in the picker; failed models are blacklisted for 10 minutes instead of burning the read timeout on retries.");
        addPr(items, "PR-139", "feat(ai): multi-provider support", "Adds Gemini, Groq, OpenRouter, Together and DeepSeek providers with a provider picker; provider-specific model allow-lists trim giant catalogs.");
        addPr(items, "PR-138", "fix(backend): long Azure buildIds", "Build IDs longer than a few digits are now handled everywhere they are parsed or persisted (Long vs Integer).");
        addPr(items, "PR-137", "docs(ai): per-provider models allow-list", "Document per-provider model allow-lists and OpenRouter config in .env.example.");
        addCommit(items, "C-501", "fix: resolve stale AI provider on load", "Resolve the selected AI provider freshly on page load instead of trusting localStorage, so a retired provider no longer 400s.");
        addCommit(items, "C-502", "feat: show generation duration in history", "Changelog-history spans now render the stored duration in seconds for each revision.");
        addCommit(items, "C-503", "fix: stream totalTokens in done event", "The generate-stream done event now carries totalTokens alongside durationMs so the UI can log usage.");
        addCommit(items, "C-504", "refactor: shared prompt composer for all audiences", "developer/qa/business prompts all flow through the same PromptComposer with versioned prompt blocks.");
        addCommit(items, "C-505", "feat: release-note entry preprocessing", "ChangeItems are normalized into release-note entries (type/scope detection and description cleaning) before the prompt is assembled.");
        addCommit(items, "C-506", "fix: strip code fences from model output", "Trim stray markdown code blocks around generated changelog text before storing.");
        addPr(items, "PR-136", "feat(history): edit, restore and delete revisions", "Changelog history panel supports editing an old revision, restoring it as the current text, and deleting it with an undo-free confirm.");
        addPr(items, "PR-135", "fix(ui): lazy-load history panel", "The edit-history panel mounts lazily so it only fetches changelog-meta when opened.");
        addPr(items, "PR-134", "feat(auth): session-encoded OAuth state", "GitHub OAuth state carries the intended next URL so redirects land exactly where the user left.");
        addCommit(items, "C-507", "fix: pipeline run snapshot maps provider correctly", "Recorded runs now keep provider name lowercased consistently for both Azure and GitHub pipelines.");
        addCommit(items, "C-508", "test: draft revision history unit tests", "Cover save-accumulation, restore-walks-bumps, identical-text no-op, and unknown-revision rejection.");
        addCommit(items, "C-509", "test: benchmark endpoint convergence", "The default benchmark candidates now merge the recommended and free-model lists rather than letting them drift.");
        data.setItems(items);
        return data;
    }

    private static void addPr(List<ChangeItem> items, String id, String title, String description) {
        items.add(item(ChangeItem.ItemType.PULL_REQUEST, id, title, description));
    }

    private static void addCommit(List<ChangeItem> items, String id, String title, String description) {
        items.add(item(ChangeItem.ItemType.COMMIT, id, title, description));
    }

    private static ChangeItem item(ChangeItem.ItemType type, String id, String title, String description) {
        ChangeItem c = new ChangeItem();
        c.setType(type);
        c.setId(id);
        c.setTitle(title);
        c.setCategory("feature");
        c.setDescription(description);
        c.setAuthor(type == ChangeItem.ItemType.PULL_REQUEST ? "nayyelin" : "dev-bot");
        c.setProject("changelog-composer");
        c.setRepo("changelog-composer");
        c.setDate("2026-09-10");
        c.setLinks(List.of("https://dev.azure.com/hubsabai/_git/changelog-composer/pullrequest/" + id.replace("PR-", "")));
        c.setFilePaths(type == ChangeItem.ItemType.PULL_REQUEST
                ? List.of("service/src/main/java/com/hubsabai/changelog/api/AzureDevOpsResource.java", "web-view/src/hooks/useChangelogEdit.ts")
                : List.of("service/src/main/java/com/hubsabai/changelog/storage/RecordedRunService.java"));
        c.setAdditions(type == ChangeItem.ItemType.PULL_REQUEST ? 120 : 15);
        c.setDeletions(type == ChangeItem.ItemType.PULL_REQUEST ? 30 : 4);
        return c;
    }
}