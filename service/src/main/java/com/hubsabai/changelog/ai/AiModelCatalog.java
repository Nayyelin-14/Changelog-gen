package com.hubsabai.changelog.ai;

import java.util.List;

/**
 * Fallback UI dropdown list when live {@code /ai/models} call fails. Kept in sync with
 * {@code NimAiProvider.RECOMMENDED_MODELS} — a retired model is worse than no dropdown.
 */
public final class AiModelCatalog {

    public static final List<AiModelOption> FREE_MODELS = List.of(
            new AiModelOption("meta/llama-3.1-8b-instruct", "LLaMA 3.1 8B", true),
            new AiModelOption("google/gemma-2-2b-it", "Gemma 2 2B IT", true),
            new AiModelOption("mistralai/mistral-small-4-119b-2603", "Mistral Small 4 119B", true),
            new AiModelOption("poolside/laguna-xs-2.1", "Laguna XS 2.1", true),
            new AiModelOption("nvidia/nemotron-mini-4b-instruct", "Nemotron Mini 4B", true));

    private AiModelCatalog() {
    }
}
