package com.hubsabai.changelog.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Diagnostics — NOT part of the normal suite (skipped unless -Dmodel.readiness.probe=true).
 * Runs the exact registry construction + real listModels()/chat probe for every provider
 * configured in the local .env (gemini, groq, openrouter) plus the legacy nvidia block, then
 * reports, per provider: base URL + model (API keys are never printed), how many models surfaced,
 * and whether a minimal chat round-trip succeeds. Run:
 *
 *   ./mvnw test -Dtest=ProviderReadinessProbeTest -Dmodel.readiness.probe=true
 */
public class ProviderReadinessProbeTest {

    @Test
    void probesEveryConfiguredProvider() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("model.readiness.probe")),
                "skipped: run with -Dmodel.readiness.probe=true");

        String[] ids = {"nvidia", "gemini", "groq", "openrouter"};
        List<String> rows = new ArrayList<>();
        for (String id : ids) {
            String baseUrl = dotEnv("AI_PROVIDERS_" + id.toUpperCase() + "_BASE_URL");
            String apiKey = dotEnv("AI_PROVIDERS_" + id.toUpperCase() + "_API_KEY");
            String model = dotEnv("AI_PROVIDERS_" + id.toUpperCase() + "_MODEL");
            if (id.equals("nvidia")) {
                baseUrl = firstNonNull(dotEnv("AI_BASE_URL"), dotEnv("AI_PROVIDERS_NVIDIA_BASE_URL"));
                apiKey = firstNonNull(dotEnv("AI_API_KEY"), dotEnv("AI_PROVIDERS_NVIDIA_API_KEY"));
                model = firstNonNull(dotEnv("AI_MODEL"), dotEnv("AI_PROVIDERS_NVIDIA_MODEL"));
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                rows.add(id + " | NOT CONFIGURED (no base-url in .env)");
                continue;
            }
            String keyState = (apiKey == null || apiKey.isBlank())
                    ? "MISSING" : "present(redacted)";
            if (keyState.equals("MISSING")) {
                rows.add(id + " | base=" + baseUrl + " apiKey=" + keyState + " -> DISABLED in registry");
                continue;
            }
            Optional<String> fallback = Optional.ofNullable(
                    dotEnv("AI_PROVIDERS_" + id.toUpperCase() + "_FALLBACK_MODELS"));
            Optional<String> allowed = Optional.ofNullable(
                    dotEnv("AI_PROVIDERS_" + id.toUpperCase() + "_MODELS"));
            Optional<String> devPrompt = Optional.ofNullable(
                    dotEnv("AI_PROVIDERS_" + id.toUpperCase() + "_PROMPT_DEVELOPER"));
            NimAiProvider provider = new NimAiProvider(
                    baseUrl,
                    model == null || model.isBlank() ? "meta/llama-3.2-11b-vision-instruct" : model,
                    apiKey,
                    fallback, allowed, devPrompt, Optional.empty(), Optional.empty());

            String modelState;
            List<String> catalogPreview = new ArrayList<>();
            try {
                List<AiModelOption> models = provider.listModels();
                modelState = "listed=" + models.size();
                models.stream().map(AiModelOption::id).limit(15).forEach(catalogPreview::add);
            } catch (Exception e) {
                modelState = "listModels ERR: " + e.getMessage();
            }

            String chatState;
            try {
                String target = model == null || model.isBlank() ? "meta/llama-3.2-11b-vision-instruct" : model;
                AiResult result = runMinimalChat(provider, target);
                chatState = "chat OK (" + result.getText().length()
                        + " chars, " + (result.getUsage() != null ? result.getUsage().getCompletionTokens() : "?") + " out-tok)";
            } catch (Exception e) {
                chatState = "chat ERR: " + truncate(e.getMessage(), 220);
            }
            rows.add(id + " | model=" + (model == null ? "(default)" : model)
                    + " | " + modelState + " | " + chatState);
            if (!catalogPreview.isEmpty()) {
                rows.add("    catalog(15): " + String.join(", ", catalogPreview));
            }
        }

        StringBuilder table = new StringBuilder("\n=== PROVIDER READINESS (local .env) ===\n");
        for (String row : rows) {
            table.append("- ").append(row).append("\n");
        }
        System.out.println(table);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static AiResult runMinimalChat(NimAiProvider provider, String model) {
        com.hubsabai.changelog.core.model.ReleaseData.ReleaseMeta meta =
                new com.hubsabai.changelog.core.model.ReleaseData.ReleaseMeta();
        meta.setOrg("hubsabai");
        meta.setProject("changelog-composer");
        meta.setRepo("changelog-composer");
        meta.setBranch("main");
        meta.setMilestone("1.0.0");
        meta.setReleaseDate("2026-09-17");
        com.hubsabai.changelog.core.model.ChangeItem item =
                new com.hubsabai.changelog.core.model.ChangeItem();
        item.setId("PR-0");
        item.setType(com.hubsabai.changelog.core.model.ChangeItem.ItemType.PULL_REQUEST);
        item.setTitle("readiness probe");
        item.setCategory("feature");
        item.setDescription("Probe that a provider round-trips a real changelog request.");
        item.setAuthor("probe");
        item.setDate("2026-09-17");
        return provider.generateForAudienceStrict(List.of(item), meta, "developer", model);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String dotEnv(String key) {
        if (System.getenv(key) != null && !System.getenv(key).isBlank()) return System.getenv(key);
        for (Path candidate : new Path[]{Path.of("../.env"), Path.of(".env")}) {
            try {
                if (Files.isReadable(candidate)) {
                    for (String line : Files.readAllLines(candidate)) {
                        if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}