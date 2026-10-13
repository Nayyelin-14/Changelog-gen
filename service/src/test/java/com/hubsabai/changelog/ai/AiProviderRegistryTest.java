package com.hubsabai.changelog.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderRegistryTest {

    @AfterEach
    void clearProperties() {
        for (String key : new String[]{
                "ai.api-key", "ai.base-url", "ai.model",
                "ai.providers",
                "ai.providers.gemini.base-url", "ai.providers.gemini.api-key", "ai.providers.gemini.model",
                "ai.providers.groq.base-url", "ai.providers.groq.api-key",
                "ai.providers.groq.model", "ai.providers.groq.models",
                "ai.providers.groq.fallback-models"}) {
            System.clearProperty(key);
        }
        // NEVER clearProperty("groq.api-key"): clearing falls through to service/.env, whose real
        // GROQ_API_KEY would auto-enable groq again and leak into the next test. Surefire sets it
        // empty at JVM start for exactly this reason — restore that blanked state.
        System.setProperty("groq.api-key", "");
    }

    private AiProviderRegistry registry() {
        AiProviderRegistry registry = new AiProviderRegistry();
        registry.init();
        return registry;
    }

    @Test
    void onlyNvidiaRegisteredByDefault() {
        System.setProperty("ai.api-key", "test");
        System.setProperty("ai.providers", "");
        AiProviderRegistry registry = registry();

        List<AiProviderInfo> list = registry.list();
        assertEquals(1, list.size());
        assertEquals("nvidia", list.get(0).id());
        assertEquals("NVIDIA NIM", list.get(0).label());
        assertTrue(list.get(0).enabled());

        assertNotNull(registry.resolve("nvidia"));
        assertNotNull(registry.resolve(null));
        assertNotNull(registry.resolve(""));
        assertNotNull(registry.resolve("  NVIDIA  "));
    }

    @Test
    void keylessExtraProviderIsHidden() {
        System.setProperty("ai.api-key", "test");
        System.setProperty("ai.providers", "gemini");
        System.setProperty("ai.providers.gemini.base-url", "https://example.test/v1/chat/completions");
        System.setProperty("ai.providers.gemini.api-key", "");

        AiProviderRegistry registry = registry();
        assertEquals(1, registry.list().size());
        AiException ex = assertThrows(AiException.class, () -> registry.resolve("gemini"));
        assertTrue(ex.getMessage().contains("not configured"), ex.getMessage());
    }

    @Test
    void keyedExtraProviderIsUsableAndLabeled() {
        System.setProperty("ai.api-key", "test");
        System.setProperty("ai.providers", "gemini,groq");
        System.setProperty("ai.providers.gemini.base-url", "https://example.test/v1/chat/completions");
        System.setProperty("ai.providers.gemini.api-key", "gemini-key");
        System.setProperty("ai.providers.gemini.model", "gemini-2.5-flash");
        System.setProperty("ai.providers.groq.api-key", "groq-key");

        AiProviderRegistry registry = registry();
        List<AiProviderInfo> list = registry.list();
        assertEquals(3, list.size());

        AiProviderInfo gemini = list.stream().filter(p -> p.id().equals("gemini")).findFirst().orElseThrow();
        assertEquals("Google Gemini", gemini.label());
        assertNotNull(registry.resolve("gemini"));
        assertNotNull(registry.resolve("GROQ"));
        assertThrows(AiException.class, () -> registry.resolve("together"));
    }

    @Test
    void unknownProviderThrows() {
        System.setProperty("ai.providers", "gemini");
        AiProviderRegistry registry = registry();
        AiException ex = assertThrows(AiException.class, () -> registry.resolve("openrouter"));
        assertTrue(ex.getMessage().contains("Unknown AI provider"), ex.getMessage());
    }

    /** The bare-key contract: {@code GROQ_API_KEY} alone (no ai.providers declaration) turns
     * Groq on with the known endpoint/model/fallback/allow-list defaults — and the allow-list
     * rejects the non-chat ids the live /models catalog also advertises. */
    @Test
    void bareGroqKeyAutoEnablesProviderWithKnownDefaults() {
        System.setProperty("ai.providers", "");
        System.setProperty("groq.api-key", "gsk-test");

        AiProviderRegistry registry = registry();
        List<AiProviderInfo> list = registry.list();
        assertEquals(2, list.size());

        AiProviderInfo groq = list.stream().filter(p -> p.id().equals("groq")).findFirst().orElseThrow();
        assertEquals("Groq", groq.label());
        assertTrue(groq.enabled());

        NimAiProvider provider = (NimAiProvider) registry.resolve("GROQ");
        assertEquals("https://api.groq.com/openai/v1/chat/completions", provider.baseUrl);
        assertEquals("qwen/qwen3.8-27b", provider.model);
        assertTrue(provider.fallbackModels.contains("allam-2-7b"));
        assertEquals(
                Set.of("qwen/qwen3.8-27b", "allam-2-7b", "openai/gpt-oss-120b", "openai/gpt-oss-20b"),
                provider.allowedModels);

        // Chat-capable ids are served; non-chat catalog entries are rejected before any call.
        assertDoesNotThrow(() -> provider.ensureRequestedModelAllowed("qwen/qwen3.8-27b"));
        assertDoesNotThrow(() -> provider.ensureRequestedModelAllowed(null)); // chain uses default
        AiException whisper = assertThrows(AiException.class,
                () -> provider.ensureRequestedModelAllowed("whisper-large-v3"));
        assertTrue(whisper.getMessage().contains("not enabled"), whisper.getMessage());
        assertThrows(AiException.class,
                () -> provider.ensureRequestedModelAllowed("meta-llama/llama-prompt-guard-2-86m"));
    }

    /** An explicit ai.providers.groq.* block overrides every known default, and the block's key
     * wins over the conventional env key. */
    @Test
    void explicitGroqBlockOverridesKnownDefaults() {
        System.setProperty("ai.providers", "groq");
        System.setProperty("groq.api-key", "env-key");
        System.setProperty("ai.providers.groq.api-key", "block-key");
        System.setProperty("ai.providers.groq.base-url", "https://custom.test/v1/chat/completions");
        System.setProperty("ai.providers.groq.model", "custom-model");
        System.setProperty("ai.providers.groq.models", "model-a,model-b");
        System.setProperty("ai.providers.groq.fallback-models", "model-c");

        AiProviderRegistry registry = registry();
        NimAiProvider provider = (NimAiProvider) registry.resolve("groq");
        assertEquals("https://custom.test/v1/chat/completions", provider.baseUrl);
        assertEquals("custom-model", provider.model);
        assertEquals("block-key", provider.apiKey);
        assertEquals(List.of("model-c"), provider.fallbackModels);
        assertEquals(Set.of("model-a", "model-b"), provider.allowedModels);
        assertThrows(AiException.class, () -> provider.ensureRequestedModelAllowed("model-c"));
    }

    /** Declared-but-keyless stays hidden even when a conventional key would otherwise apply —
     * an explicit block is the admin's declaration; a key alone is the auto-enable path. */
    @Test
    void declaredGroqBlockWithEmptyKeyStaysHidden() {
        System.setProperty("ai.providers", "groq");
        System.setProperty("ai.providers.groq.api-key", "");
        System.setProperty("groq.api-key", "gsk-test");

        AiProviderRegistry registry = registry();
        assertEquals(1, registry.list().size());
        AiException ex = assertThrows(AiException.class, () -> registry.resolve("groq"));
        assertTrue(ex.getMessage().contains("not configured"), ex.getMessage());
    }
}