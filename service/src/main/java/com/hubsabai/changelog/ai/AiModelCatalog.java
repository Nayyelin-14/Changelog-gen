package com.hubsabai.changelog.ai;

import java.util.List;

/**
 * Fallback UI dropdown list when the live {@code /ai/models} call breaks. Only models that are
 * health-checked against the real chat endpoint belong here — every entry must be verified working,
 * or the fallback itself ships dead models to the picker.
 */
public final class AiModelCatalog {

    /** Health-checked OK against https://integrate.api.nvidia.com/v1/chat/completions. */
    public static final List<AiModelOption> FREE_MODELS = List.of(
            AiModelOption.available("nvidia/nemotron-3.5-bolt-e4b", "Nemotron 3.5 Bolt", true),
            AiModelOption.available("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron 3 Ultra 550B", true),
            AiModelOption.available("openai/gpt-oss-20b", "GPT-OSS 20B", true),
            AiModelOption.available("meta/llama-3.2-11b-vision-instruct", "LLaMA 3.2 11B Vision", true),
            AiModelOption.available("nvidia/nemotron-3.5-lightning-30b-a3b", "Nemotron 3.5 Lightning 30B", true),
            AiModelOption.available("meta/muse-glimmer-30b", "Muse Glimmer 30B", true));

    private AiModelCatalog() {
    }
}
