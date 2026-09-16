package com.hubsabai.changelog.benchmark;

import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.ai.NimAiProvider;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Prints the raw developer-audience changelog output for a set of models so the benchmark's
 * quality scores can be eyeballed before locking in the recommended set.
 */
public final class ProbeOutputs {

    public static void main(String[] args) {
        String apiKey = System.getenv("AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL",
                "https://integrate.api.nvidia.com/v1/chat/completions");
        NimAiProvider provider = new NimAiProvider(baseUrl, "probe", apiKey,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        List<String> models = List.of(
                "meta/llama-3.2-11b-vision-instruct",
                "nvidia/nemotron-3-super-120b-a12b",
                "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                "nvidia/nemotron-3-ultra-550b-a55b",
                "meta/muse-glimmer-30b",
                "nvidia/nemotron-3.5-lightning-30b-a3b",
                "openai/gpt-oss-20b");
        ReleaseData data = ModelBenchmark.sampleRelease();
        for (String model : models) {
            System.out.println("\n======================== " + model + " ========================");
            try {
                long start = System.currentTimeMillis();
                AiResult r = provider.generateForAudienceStrict(data.getItems(), data.getRelease(), "developer", model);
                long ms = System.currentTimeMillis() - start;
                System.out.println("[" + ms + "ms] tokens="
                        + (r.getUsage() != null ? r.getUsage().getTotalTokens() : "n/a"));
                System.out.println("---- output ----");
                System.out.println(r.getText());
                System.out.println("---- end ----");
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getMessage());
            }
        }
        System.exit(0);
    }
}