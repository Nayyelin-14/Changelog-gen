package com.hubsabai.changelog.benchmark;

import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.ai.NimAiProvider;
import com.hubsabai.changelog.ai.NvidiaModelsResponse;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs every chat-capable free model on the live NIM catalog through a real changelog
 * generation, measures success, latency, token usage, and output quality, and ranks the
 * models that actually work. Top 5 become the "recommended" set.
 *
 * <p>Standalone CLI — run after {@code mvn compile}:
 * <pre>
 *   java -cp target/classes:&lt;compile-classpath&gt; \
 *       com.hubsabai.changelog.benchmark.ModelBenchmark [trials]
 * </pre>
 * Reads {@code AI_API_KEY}, {@code AI_BASE_URL}, {@code AI_MODEL} from the environment. Writes
 * results to {@code model-benchmark-results.csv} and {@code model-benchmark-results.md}.
 */
public final class ModelBenchmark {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "was", "were", "from", "have",
            "been", "users", "user", "your", "into", "when", "after", "before", "about",
            "will", "can", "more", "less", "than", "then", "they", "them", "their",
            "also", "does", "doesn", "should", "would", "could", "now", "via", "under");

    private static final Pattern BULLET = Pattern.compile("(?m)^- \\*\\*.*\\*\\*:");

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("AI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("AI_API_KEY is not set.");
            System.exit(1);
        }
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL",
                "https://integrate.api.nvidia.com/v1/chat/completions");
        String defaultModel = System.getenv().getOrDefault("AI_MODEL",
                "mistralai/mistral-small-4-119b-2603");
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Set<String> restricted = args.length > 1
                ? new LinkedHashSet<>(java.util.Arrays.stream(args[1].split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList())
                : Set.of();

        String modelsUrl = baseUrl.replace("/chat/completions", "/models");

        List<String> candidates = fetchChatModelIds(modelsUrl, apiKey);
        if (!restricted.isEmpty()) {
            candidates.retainAll(restricted);
        }
        System.out.println("Discovered " + candidates.size() + " chat-capable models.");

        NimAiProvider provider = new NimAiProvider(baseUrl, defaultModel, apiKey,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Ensure the defaults and current recommended catalog models are never skipped,
        // even if the live catalog view differs.
        addIfMissing(candidates, defaultModel);
        com.hubsabai.changelog.ai.AiModelCatalog.FREE_MODELS.forEach(m -> addIfMissing(candidates, m.id()));

        ReleaseData sample = sampleRelease();
        List<ModelResult> results = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            String modelId = candidates.get(i);
            ModelResult r = benchmark(provider, modelId, sample, trials);
            results.add(r);
            printProgress(results, candidates.size());
            if (i < candidates.size() - 1) {
                Thread.sleep(1200);
            }
        }

        writeReports(results, apiKey);
        printRanking(results);
        System.exit(0);
    }

    private static void addIfMissing(List<String> list, String id) {
        if (id != null && !id.isBlank() && !list.contains(id)) {
            list.add(id);
        }
    }

    private static ModelResult benchmark(NimAiProvider provider, String modelId,
            ReleaseData data, int trials) {
        long totalLatency = 0;
        long totalTokens = 0;
        long totalCompletion = 0;
        boolean ok = false;
        String lastError = null;
        String bestOutput = null;
        int quality = 0;

        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            try {
                AiResult result = provider.generateForAudienceStrict(
                        data.getItems(), data.getRelease(), "developer", modelId);
                long ms = TimeUnit.NANOSECONDS.toMillis(Math.max(1, System.nanoTime() - start));
                totalLatency += ms;
                ok = true;
                String text = result.getText();
                if (text != null && !text.isBlank()) {
                    bestOutput = text;
                }
                if (result.getUsage() != null) {
                    totalTokens += result.getUsage().getTotalTokens();
                    totalCompletion += result.getUsage().getCompletionTokens();
                }
                String scored = result.getText() != null ? result.getText() : "";
                quality = Math.max(quality, scoreQuality(scored, data));
            } catch (AiException e) {
                lastError = firstLine(e.getMessage());
            } catch (Exception e) {
                lastError = firstLine(e.getMessage());
            }
        }

        Long avgLatency = ok ? totalLatency / Math.max(1, trials) : null;
        Integer total = ok ? (int) (totalTokens / Math.max(1, trials)) : null;
        Integer completion = ok ? (int) (totalCompletion / Math.max(1, trials)) : null;
        return new ModelResult(modelId, ok, avgLatency, total, completion, quality, lastError, bestOutput != null
                ? bestOutput : null);
    }

    private static void printProgress(List<ModelResult> done, int total) {
        long ok = done.stream().filter(m -> m.ok).count();
        System.out.printf("[%3d/%3d] completed — %d working so far%n", done.size(), total, ok);
    }

    private static String firstLine(String message) {
        if (message == null) return "unknown error";
        int idx = message.indexOf('\n');
        String line = idx > 0 ? message.substring(0, idx) : message;
        return line.length() > 140 ? line.substring(0, 140) + "…" : line;
    }

    /** 0–100 quality score for a developer-audience changelog output. */
    static int scoreQuality(String output, ReleaseData data) {
        if (output == null || output.isBlank()) return 0;
        String lower = output.toLowerCase(Locale.ROOT);

        int expected = data.getItems().size();
        long bullets = BULLET.matcher(output).results().count();

        int score = 0;
        score += Math.min(40, (int) (40L * bullets / Math.max(1, expected)));

        List<String> keywords = expectedKeywords(data);
        long seen = keywords.stream().filter(k -> lower.contains(k)).count();
        score += (int) Math.round(35.0 * seen / Math.max(1, keywords.size()));

        if (!output.contains("```")) score += 10;
        if (output.length() >= 200) score += 10;
        if (output.trim().length() >= 60 && output.trim().length() < 200) score += 5;

        if (bullets == 0) score = Math.min(score, 20);
        return Math.max(0, Math.min(100, score));
    }

    static List<String> expectedKeywords(ReleaseData data) {
        Set<String> words = new LinkedHashSet<>();
        for (ChangeItem item : data.getItems()) {
            for (String field : new String[]{item.getTitle(), item.getDescription()}) {
                if (field == null) continue;
                for (String w : field.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (w.length() > 3 && !STOPWORDS.contains(w)) words.add(w);
                }
            }
        }
        return new ArrayList<>(words);
    }

    private static List<String> fetchChatModelIds(String modelsUrl, String apiKey) throws Exception {
        Client client = ClientBuilder.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        try {
            jakarta.ws.rs.core.Response raw = client.target(modelsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .get();
            String body = raw.readEntity(String.class);
            if (raw.getStatus() >= 300) {
                throw new AiException("Failed to list models: HTTP " + raw.getStatus() + " — " + body);
            }
            NvidiaModelsResponse response = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, NvidiaModelsResponse.class);
            return new ArrayList<>(response.dataOrEmpty().stream()
                    .map(NvidiaModelsResponse.NvidiaModel::getId)
                    .filter(ModelBenchmark::looksLikeChatModel)
                    .sorted()
                    .toList());
        } finally {
            client.close();
        }
    }

    private static boolean looksLikeChatModel(String id) {
        String lower = id.toLowerCase();
        if (lower.contains("embed") || lower.contains("guard") || lower.contains("safety")
                || lower.contains("translate") || lower.contains("clip") || lower.contains("kosmos")
                || lower.contains("fuyu") || lower.contains("deplot") || lower.contains("gliner")
                || lower.contains("detector") || lower.contains("parse") || lower.contains("reward")
                || lower.contains("vila") || lower.contains("neva") || lower.contains("bge")
                || lower.contains("diffusion")) {
            return false;
        }
        return true;
    }

    static ReleaseData sampleRelease() {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setProject("Changelog Composer");
        meta.setRepo("changelog-composer");
        meta.setBranch("main");
        meta.setMilestone("2.3.0");
        meta.setReleaseDate("2026-08-10");
        data.setRelease(meta);

        List<ChangeItem> items = new ArrayList<>();
        items.add(commit("Prevent duplicate session refresh",
                "Multiple refresh requests could occur when several API calls expired simultaneously.",
                "fix", List.of("src/auth/TokenRefresher.java")));
        items.add(pr("128", "Support resumable uploads",
                "Large file uploads can now resume from the last completed chunk after a network interruption.",
                "feat", List.of("src/upload/UploadEndpoint.ts")));
        items.add(workItem("5678", "Fix login lockout after failed MFA",
                "Users were permanently locked out after a single failed multi-factor authentication attempt.",
                "fix", List.of()));
        items.add(commit("Add dark mode toggle",
                "Users can now switch between light and dark themes from the settings panel.",
                "feat", List.of("src/ui/ThemeToggle.tsx")));
        items.add(commit("Pin Node.js 22 in CI pipeline",
                "",
                "ci", List.of(".github/workflows/ci.yml")));
        items.add(commit("Bump Jackson to 2.15.2",
                "Upgrade the Jackson dependency to the latest stable patch release.",
                "build", List.of("pom.xml")));
        items.add(commit("Document API rate limits",
                "Add a rate-limit usage guide to the developer documentation.",
                "docs", List.of("docs/api-rate-limits.md")));
        items.add(commit("Refactor auth cache",
                "Make token cache reads non-blocking to reduce lock contention under load.",
                "chore", List.of("src/auth/AuthCache.java")));
        data.setItems(items);
        return data;
    }

    private static ChangeItem commit(String title, String description, String category, List<String> paths) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setFilePaths(paths);
        return item;
    }

    private static ChangeItem pr(String id, String title, String description, String category, List<String> paths) {
        ChangeItem item = commit(title, description, category, paths);
        item.setType(ChangeItem.ItemType.PULL_REQUEST);
        item.setId(id);
        return item;
    }

    private static ChangeItem workItem(String id, String title, String description, String category, List<String> paths) {
        ChangeItem item = commit(title, description, category, paths);
        item.setType(ChangeItem.ItemType.WORK_ITEM);
        item.setId(id);
        return item;
    }

    static void writeReports(List<ModelResult> results, String apiKey) throws IOException {
        computeComposites(results);
        List<ModelResult> working = results.stream()
                .filter(m -> m.ok)
                .sorted(rankComparator())
                .toList();
        int top = Math.min(5, working.size());
        Set<String> recommended = working.stream().limit(top)
                .map(ModelResult::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        System.out.println("Recommended models: " + recommended);

        StringBuilder csv = new StringBuilder("model,working,latencyMs,totalTokens,completionTokens,quality,error\n");
        for (ModelResult r : results) {
            csv.append(csvEscape(r.id)).append(',')
                    .append(r.ok).append(',')
                    .append(r.latencyMs == null ? "" : r.latencyMs).append(',')
                    .append(r.totalTokens == null ? "" : r.totalTokens).append(',')
                    .append(r.completionTokens == null ? "" : r.completionTokens).append(',')
                    .append(r.quality).append(',')
                    .append(csvEscape(r.error == null ? "" : r.error)).append('\n');
        }
        Files.writeString(Path.of("model-benchmark-results.csv"), csv.toString());

        StringBuilder md = new StringBuilder();
        md.append("# Model Benchmark Results\n\n");
        md.append("Config: baseUrl=").append("https://integrate.api.nvidia.com/v1/chat/completions")
                .append("\n\n");
        md.append("## Recommended (Top 5)\n\n");
        for (String id : recommended) {
            md.append("- ").append(id).append('\n');
        }
        md.append("\n## Full ranking\n\n");
        md.append("| # | Model | Working | Composite | Avg latency (ms) | Avg total tokens | Completion tokens | Quality |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        int position = 0;
        for (ModelResult r : results.stream().sorted(rankComparator()).toList()) {
            position++;
            md.append("| ").append(position).append(" | ").append(r.id).append(" | ").append(r.ok)
                    .append(" | ").append(r.ok ? r.composite : "-")
                    .append(" | ").append(r.latencyMs == null ? "-" : r.latencyMs)
                    .append(" | ").append(r.totalTokens == null ? "-" : r.totalTokens)
                    .append(" | ").append(r.completionTokens == null ? "-" : r.completionTokens)
                    .append(" | ").append(r.quality).append(" |\n");
        }
        md.append("\n## Non-working models\n\n");
        for (ModelResult r : results) {
            if (!r.ok) {
                md.append("- ").append(r.id).append(" — ").append(r.error == null ? "unknown" : r.error).append('\n');
            }
        }
        Files.writeString(Path.of("model-benchmark-results.md"), md.toString());
    }

    private static String csvEscape(String value) {
        return value == null ? "" : value.replace("\"", "\"\"");
    }

    /**
     * Ranks working models by a composite score balancing the three things the benchmark
     * measures: quality of the changelog (good response), speed (less time), and token
     * economy (less token usage). Each of the two cost axes is normalized against the best
     * model seen, then merged as 0.5*quality + 0.25*speed + 0.25*cost. Non-working models sort
     * last, each below every working one.
     */
    static Comparator<ModelResult> rankComparator() {
        return Comparator
                .comparing(ModelResult::ok, Comparator.reverseOrder())
                .thenComparing(ModelResult::composite, Comparator.reverseOrder())
                .thenComparing(ModelResult::latencyMs, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ModelResult::totalTokens, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ModelResult::id);
    }

    /** Computes the composite score for each working model relative to the best of the batch. */
    static void computeComposites(List<ModelResult> results) {
        List<ModelResult> working = results.stream().filter(ModelResult::ok).toList();
        if (working.isEmpty()) return;
        long bestLatency = working.stream().mapToLong(ModelResult::latencyMs).min().orElse(1);
        int bestTokens = working.stream().mapToInt(ModelResult::totalTokens).min().orElse(1);
        for (ModelResult r : results) {
            if (!r.ok) {
                r.composite = 0.0;
                continue;
            }
            double quality = r.quality;
            double speed = 100.0 * bestLatency / r.latencyMs;
            double cost = 100.0 * bestTokens / r.totalTokens;
            r.composite = Math.round((0.5 * quality + 0.25 * speed + 0.25 * cost) * 10.0) / 10.0;
        }
    }

    private static void printRanking(List<ModelResult> results) {
        computeComposites(results);
        List<ModelResult> ranked = results.stream().sorted(rankComparator()).toList();
        System.out.println("\n================= FULL RANKING =================");
        System.out.printf("%-52s %-7s %-12s %-12s %-10s %-8s%n",
                "MODEL", "OK", "LATENCY(ms)", "TOKENS", "COMPOSITE", "RANK");
        int rank = 0;
        for (ModelResult r : ranked) {
            rank++;
            System.out.printf("%-52s %-7s %-12s %-12s %-10.1f %-8s%n",
                    r.id, r.ok,
                    r.latencyMs == null ? "-" : r.latencyMs,
                    r.totalTokens == null ? "-" : r.totalTokens,
                    r.composite,
                    r.ok && rank <= 5 ? "★ " + rank : "");
        }
        System.out.println("================= END RANKING =================");
    }

    public static class ModelResult {
        private final String id;
        private final boolean ok;
        private final Long latencyMs;
        private final Integer totalTokens;
        private final Integer completionTokens;
        private final int quality;
        private final String error;
        private final String output;
        private double composite;

        public ModelResult(String id, boolean ok, Long latencyMs, Integer totalTokens,
                           Integer completionTokens, int quality, String error, String output) {
            this.id = id;
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.totalTokens = totalTokens;
            this.completionTokens = completionTokens;
            this.quality = quality;
            this.error = error;
            this.output = output;
        }

        public String id() { return id; }
        public boolean ok() { return ok; }
        public Long latencyMs() { return latencyMs; }
        public Integer totalTokens() { return totalTokens; }
        public Integer completionTokens() { return completionTokens; }
        public int quality() { return quality; }
        public String error() { return error; }
        public String output() { return output; }
        public double composite() { return composite; }
    }
}