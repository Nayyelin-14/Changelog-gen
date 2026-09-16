package com.hubsabai.changelog.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                "ai.providers.groq.base-url", "ai.providers.groq.api-key"}) {
            System.clearProperty(key);
        }
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
}