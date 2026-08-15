package com.hubsabai.changelog.ai;

import java.util.List;

/**
 * Fallback UI dropdown list when live {@code /ai/models} call fails. Kept in sync with
 * {@code NimAiProvider.RECOMMENDED_MODELS} — a retired model is worse than no dropdown.
 */
public final class AiModelCatalog {

    public static final List<AiModelOption> FREE_MODELS = List.of(
            new AiModelOption("meta/llama-3.1-8b-instruct", "LLaMA 3.1 8B", true),
            new AiModelOption("poolside/laguna-xs-2.1", "Laguna XS 2.1", true),
            new AiModelOption("mistralai/mistral-nemotron", "Mistral Nemotron", true),
            new AiModelOption("meta/llama-3.1-70b-instruct", "LLaMA 3.1 70B", true),
            new AiModelOption("nvidia/llama-3.1-nemotron-nano-vl-8b-v1", "Nemotron Nano VL 8B", true));

    private AiModelCatalog() {
    }
}
