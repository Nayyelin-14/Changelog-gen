package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics — NOT part of the normal suite (skipped unless -Dmodel.probe.classify=true).
 * Calls every model NVIDIA's catalog advertises and classifies why each is hidden from the
 * picker: genuinely dead (4xx), rate-limited free tier (503), or just slow (>8s probe timeout).
 */
public class ModelCatalogClassificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void classifiesEveryCatalogModel() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("model.probe.classify")),
                "skipped: run with -Dmodel.probe.classify=true");

        String baseUrl = env("AI_BASE_URL", "https://integrate.api.nvidia.com/v1/chat/completions");
        String apiKey = env("AI_API_KEY", "");
        Assumptions.assumeFalse(apiKey.isBlank(), "skipped: no AI_API_KEY");

        Client modelsClient = ClientBuilder.newBuilder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).build();
        Client probeClient = ClientBuilder.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS).build();

        String modelsUrl = baseUrl.replace("/chat/completions", "/models");
        Response raw = modelsClient.target(modelsUrl).request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey).get();
        String body = raw.readEntity(String.class);
        NvidiaModelsResponse response = MAPPER.readValue(body, NvidiaModelsResponse.class);
        List<String> catalog = response.dataOrEmpty().stream()
                .map(NvidiaModelsResponse.NvidiaModel::getId)
                .filter(id -> !excludedModels().contains(id))
                .toList();
        System.out.println("Catalog after deny-list: " + catalog.size() + " candidate models");

        ExecutorService pool = Executors.newFixedThreadPool(16);
        Map<String, String> verdict = new ConcurrentHashMap<>();
        Map<String, Long> latency = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (String id : catalog) {
            futures.add(pool.submit(() -> classify(baseUrl, apiKey, probeClient, id, verdict, latency)));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        long ok = verdict.values().stream().filter("OK 200"::equals).count();
        long rl = verdict.values().stream().filter(v -> v.startsWith("503")).count();
        long dead = verdict.values().stream().filter(v -> v.startsWith("4")).count();
        long err = verdict.values().stream().filter(v -> v.startsWith("err:")).count();
        long r503 = verdict.values().stream().filter(v -> v.startsWith("timeout-")).count();
        System.out.printf("%nWORKING=%d RATE_LIMITED_503=%d DEAD_4xx=%d TIMED_OUT_OR_ERR=%d%n%n", ok, rl, dead, err + r503);

        List<String> sorted = new ArrayList<>(catalog);
        sorted.sort((a, b) -> {
            int c = verdict.getOrDefault(a, "").compareTo(verdict.getOrDefault(b, ""));
            return c != 0 ? c : latency.getOrDefault(a, 0L).compareTo(latency.getOrDefault(b, 0L));
        });
        for (String id : sorted) {
            System.out.printf("%-50s %-10s %6d ms%n", id, verdict.getOrDefault(id, "?"), latency.getOrDefault(id, -1L));
        }
    }

    private static void classify(String baseUrl, String apiKey, Client probeClient, String id,
                                 Map<String, String> verdict, Map<String, Long> latency) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", id);
        req.put("messages", List.of(new AiMessage("user", "hi")));
        req.put("max_tokens", 5);
        long start = System.nanoTime();
        try {
            String json = MAPPER.writeValueAsString(req);
            Response r = probeClient.target(baseUrl).request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(Entity.entity(json, MediaType.APPLICATION_JSON));
            int status = r.getStatus();
            String verdictValue;
            if (status == 200) verdictValue = "OK 200";
            else if (status == 503) verdictValue = "503 (rate-limited/queue)";
            else verdictValue = status + " (gone/forbidden)";
            r.close();
            verdict.put(id, verdictValue);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("timed out") || msg.contains("Timeout") || msg.contains("Read timed out")) {
                verdict.put(id, "timeout->8s");
            } else {
                verdict.put(id, "err:" + e.getClass().getSimpleName());
            }
        }
        latency.put(id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> excludedModels() {
        try {
            var field = NimAiProvider.class.getDeclaredField("EXCLUDED_MODELS");
            field.setAccessible(true);
            return (Set<String>) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read EXCLUDED_MODELS", e);
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            for (Path candidate : new Path[]{Path.of("../.env"), Path.of(".env")}) {
                try {
                    for (String line : Files.readAllLines(candidate)) {
                        if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }
}