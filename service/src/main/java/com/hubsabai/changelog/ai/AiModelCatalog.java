package com.hubsabai.changelog.ai;

import java.util.List;

/**
 * Fallback UI dropdown list when live {@code /ai/models} call fails. Kept in sync with
 * {@code NimAiProvider.RECOMMENDED_MODELS} — a retired model is worse than no dropdown.
 */
public final class AiModelCatalog {

    public static final List<AiModelOption> FREE_MODELS = List.of(
            new AiModelOption("meta/llama-3.2-11b-vision-instruct", "LLaMA 3.2 11B Vision", true),
            new AiModelOption("nvidia/nemotron-3-super-120b-a12b", "Nemotron 3 Super 120B", true),
            new AiModelOption("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", "Nemotron 3 Nano Omni 30B", true),
            new AiModelOption("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron 3 Ultra 550B", true),
            new AiModelOption("meta/muse-glimmer-30b", "Muse Glimmer 30B", true));

    private AiModelCatalog() {
    }
}
