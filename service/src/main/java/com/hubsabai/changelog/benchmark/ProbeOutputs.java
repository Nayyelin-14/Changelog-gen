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
                "meta/llama-3.1-8b-instruct",
                "nvidia/nemotron-mini-4b-instruct",
                "minimaxai/minimax-m3",
                "poolside/laguna-xs-2.1",
                "meta/llama-3.2-11b-vision-instruct",
                "nvidia/llama-3.3-nemotron-super-49b-v1",
                "thinkingmachines/inkling");
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