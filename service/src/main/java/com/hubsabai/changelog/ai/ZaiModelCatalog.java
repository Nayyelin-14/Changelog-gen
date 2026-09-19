package com.hubsabai.changelog.ai;

import java.util.List;

/**
 * Curated fallback list of Z.AI free Flash models. The live {@code /models} endpoint does not
 * expose these models, but they are accessible via direct API calls and priced at $0 for
 * input/output/cached-input on Z.AI's public pricing page.
 * <p>
 * This list is health-checked against the real chat endpoint at generation time via the normal
 * fallback chain — every entry must be verified working, or the fallback itself ships dead models
 * to the picker.
 */
public final class ZaiModelCatalog {

    /** Health-checked OK against https://api.z.ai/api/paas/v4/chat/completions (when not rate-limited). */
    public static final List<AiModelOption> FREE_MODELS = List.of(
            AiModelOption.available("glm-4.7-flash", "GLM 4.7 Flash", true),
            AiModelOption.available("glm-4.5-flash", "GLM 4.5 Flash", true),
            AiModelOption.available("glm-4.6v-flash", "GLM 4.6V Flash", true));

    private ZaiModelCatalog() {
    }
}