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
            @ConfigProperty(name = "ai.model", defaultValue = "mistralai/mistral-small-4-119b-2603") String model,
            @ConfigProperty(name = "ai.fallback-models",
                    defaultValue = "nvidia/nemotron-mini-4b-instruct,poolside/laguna-xs-2.1,"
                            + "meta/llama-3.1-8b-instruct,mistralai/mistral-medium-3.5-128b")
                    Optional<String> fallbackModels,
            @ConfigProperty(name = "ai.prompt.developer") Optional<String> developerPrompt,
            @ConfigProperty(name = "ai.prompt.qa") Optional<String> qaPrompt,
            @ConfigProperty(name = "ai.prompt.business") Optional<String> businessPrompt) {

        return new NimAiProvider(baseUrl, model, apiKey.orElse(""),
                fallbackModels, developerPrompt, qaPrompt, businessPrompt);
    }
}
