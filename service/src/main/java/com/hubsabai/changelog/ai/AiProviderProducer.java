package com.hubsabai.changelog.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Exposes the default provider (always {@code nvidia}) as the single injected {@link AiProvider},
 * so {@link AiProviderRegistry} stays the one place providers are built from config. Requests that
 * want a specific provider resolve it from the registry themselves — this bean is the back-compat
 * default only.
 */
@ApplicationScoped
public class AiProviderProducer {

    @Inject
    AiProviderRegistry registry;

    @Produces
    @ApplicationScoped
    public AiProvider produceAiProvider() {
        return registry.resolve(registry.defaultId());
    }
}