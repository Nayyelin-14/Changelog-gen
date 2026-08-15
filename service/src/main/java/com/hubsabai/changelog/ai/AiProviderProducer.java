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
            @ConfigProperty(name = "ai.model", defaultValue = "meta/llama-3.1-8b-instruct") String model,
            @ConfigProperty(name = "ai.fallback-models",
                    defaultValue = "poolside/laguna-xs-2.1,mistralai/mistral-nemotron,"
                            + "meta/llama-3.1-70b-instruct,nvidia/llama-3.1-nemotron-nano-vl-8b-v1")
                    Optional<String> fallbackModels,
            @ConfigProperty(name = "ai.prompt.developer") Optional<String> developerPrompt,
            @ConfigProperty(name = "ai.prompt.qa") Optional<String> qaPrompt,
            @ConfigProperty(name = "ai.prompt.business") Optional<String> businessPrompt) {

        return new NimAiProvider(baseUrl, model, apiKey.orElse(""),
                fallbackModels, developerPrompt, qaPrompt, businessPrompt);
    }
}
