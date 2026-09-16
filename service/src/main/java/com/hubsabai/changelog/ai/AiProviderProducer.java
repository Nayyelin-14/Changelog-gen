package com.hubsabai.changelog.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class AiProviderProducer {

    @Produces
    @ApplicationScoped
    public AiProvider produceAiProvider(
            @ConfigProperty(name = "ai.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "ai.base-url", defaultValue = "https://integrate.api.nvidia.com/v1/chat/completions") String baseUrl,
            @ConfigProperty(name = "ai.model", defaultValue = "meta/llama-3.2-11b-vision-instruct") String model,
            @ConfigProperty(name = "ai.fallback-models",
                    defaultValue = "nvidia/nemotron-3-super-120b-a12b,"
                            + "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning,"
                            + "nvidia/nemotron-3-ultra-550b-a55b,"
                            + "meta/muse-glimmer-30b")
                    Optional<String> fallbackModels,
            @ConfigProperty(name = "ai.prompt.developer") Optional<String> developerPrompt,
            @ConfigProperty(name = "ai.prompt.qa") Optional<String> qaPrompt,
            @ConfigProperty(name = "ai.prompt.business") Optional<String> businessPrompt) {

        return new NimAiProvider(baseUrl, model, apiKey.orElse(""),
                fallbackModels, developerPrompt, qaPrompt, businessPrompt);
    }
}
