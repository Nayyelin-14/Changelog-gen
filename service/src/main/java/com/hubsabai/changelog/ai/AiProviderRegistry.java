package com.hubsabai.changelog.ai;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that can generate a changelog, keyed by id. A provider is an OpenAI-compatible
 * {@code /chat/completions} endpoint plus its own model fallback chain and audience-prompt
 * overrides — which is exactly what {@link NimAiProvider} speaks, so every provider here is the
 * same class configured differently.
 *
 * <p>{@code nvidia} is always registered, backed by the legacy {@code ai.api-key}/{@code ai.base-url}/
 * {@code ai.model}/{@code ai.fallback-models}/{@code ai.prompt.*} properties. Extra providers are
 * declared in {@code ai.providers}&lbrace;id&rbrace;.* blocks:
 * <pre>
 * ai.providers=gemini,groq,openrouter
 * ai.providers.gemini.base-url=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
 * ai.providers.gemini.api-key=...
 * ai.providers.gemini.model=gemini-2.5-flash
 * ai.providers.gemini.fallback-models=gemini-2.5-pro
 * ai.providers.gemini.prompt.developer=...
 * </pre>
 * A provider whose block has no {@code api-key} is {@link #list() disabled} and hidden from the
 * UI — configuring a key later makes it appear without a restart of anything else.
 */
@ApplicationScoped
public class AiProviderRegistry {

    public static final String DEFAULT_PROVIDER = "nvidia";

    static final Map<String, String> LABELS = Map.of(
            DEFAULT_PROVIDER, "NVIDIA NIM",
            "gemini", "Google Gemini",
            "groq", "Groq",
            "openrouter", "OpenRouter",
            "together", "Together AI",
            "deepseek", "DeepSeek",
            "zai", "Z.AI (GLM)");

    /**
     * Providers that can be turned on by supplying only their conventional API-key config key
     * (e.g. env {@code GROQ_API_KEY} → {@code groq.api-key}), with an OpenAI-compatible endpoint,
     * default model, default fallback and a default model allow-list baked in so a bare key is
     * enough to enable them and every generation request is validated against a known-good set.
     * Each default is overridable through the usual {@code ai.providers.<id>.*} keys.
     */
    private record KnownDefaults(String baseUrl, String model, String fallbackModels, String allowedModels) {
    }

    private static final Map<String, KnownDefaults> KNOWN_DEFAULTS = knownDefaults();

    private static Map<String, KnownDefaults> knownDefaults() {
        Map<String, KnownDefaults> defaults = new LinkedHashMap<>();
        // Chat-capable models verified live against the account's /models catalog; the catalog
        // also advertises non-chat entries (whisper, prompt-guard) that the allow-list keeps out.
        defaults.put("groq", new KnownDefaults(
                "https://api.groq.com/openai/v1/chat/completions",
                "qwen/qwen3.8-27b",
                "allam-2-7b",
                "qwen/qwen3.8-27b,allam-2-7b,openai/gpt-oss-120b,openai/gpt-oss-20b"));
        return defaults;
    }

    private final Map<String, ProviderEntry> providers = new LinkedHashMap<>();

    private record ProviderEntry(String id, String label, boolean enabled, AiProvider provider) {
    }

    /** Reads config and builds every registered provider. Called once at startup; cheap — no
     * network happens here, only object construction. */
    @PostConstruct
    void init() {
        Config config = ConfigProvider.getConfig();
        providers.clear();
        providers.put(DEFAULT_PROVIDER, buildProvider(config, DEFAULT_PROVIDER));
        for (String id : extraProviderIds(config)) {
            providers.put(id, buildProvider(config, id));
        }
    }

    private static List<String> extraProviderIds(Config config) {
        List<String> ids = new ArrayList<>();
        Optional<String> declared = value(config, "ai.providers");
        if (declared.isPresent()) {
            for (String id : declared.get().split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty() && !DEFAULT_PROVIDER.equals(trimmed)) {
                    ids.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
        }
        // Auto-enable: a well-known provider whose conventional API key is configured but that
        // nobody listed in ai.providers — e.g. GROQ_API_KEY alone turns Groq on. A key is
        // required either way (buildProvider still marks keyless providers disabled).
        for (String id : KNOWN_DEFAULTS.keySet()) {
            if (!ids.contains(id) && apiKeyValue(config, id).filter(k -> !k.isBlank()).isPresent()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** The provider's API key from its own block first ({@code ai.providers.<id>.api-key}),
     * then its conventional config key ({@code groq.api-key} ← env {@code GROQ_API_KEY}). */
    private static Optional<String> apiKeyValue(Config config, String id) {
        return value(config, "ai.providers." + id + ".api-key").or(() -> value(config, id + ".api-key"));
    }

    /** Builds either the legacy nvidia provider or one from an {@code ai.providers.<id>.*} block. */
    private static ProviderEntry buildProvider(Config config, String id) {
        boolean legacy = DEFAULT_PROVIDER.equals(id);
        String prefix = legacy ? "ai." : "ai.providers." + id + ".";
        KnownDefaults known = KNOWN_DEFAULTS.get(id);

        String baseUrl = value(config, prefix + "base-url")
                .or(() -> known != null ? Optional.of(known.baseUrl()) : Optional.empty())
                .orElse(legacy ? "https://integrate.api.nvidia.com/v1/chat/completions" : "");
        String apiKey = legacy
                ? value(config, prefix + "api-key").orElse("")
                : apiKeyValue(config, id).orElse("");
        String model = value(config, prefix + "model")
                .or(() -> known != null ? Optional.of(known.model()) : Optional.empty())
                .orElse(legacy ? "meta/llama-3.2-11b-vision-instruct" : "");
        Optional<String> fallbackModels = legacy
                ? value(config, prefix + "fallback-models")
                        .or(() -> Optional.of("nvidia/nemotron-3-super-120b-a12b,"
                                + "meta/muse-glimmer-30b,"
                                + "nvidia/nemotron-3.5-lightning-30b-a3b,"
                                + "openai/gpt-oss-20b"))
                : value(config, prefix + "fallback-models")
                        .or(() -> known != null ? Optional.of(known.fallbackModels()) : Optional.empty());
        Optional<String> allowedModels = value(config, prefix + "models")
                .or(() -> known != null ? Optional.of(known.allowedModels()) : Optional.empty());
        Optional<String> developerPrompt = value(config, prefix + "prompt.developer");
        Optional<String> qaPrompt = value(config, prefix + "prompt.qa");
        Optional<String> businessPrompt = value(config, prefix + "prompt.business");

        if (baseUrl.isBlank()) {
            baseUrl = "https://integrate.api.nvidia.com/v1/chat/completions";
        }

        boolean enabled = !apiKey.isBlank();
        AiProvider provider = null;
        if (enabled) {
            if ("zai".equals(id)) {
                provider = new ZaiAiProvider(baseUrl, model, apiKey, fallbackModels, allowedModels, developerPrompt, qaPrompt, businessPrompt);
            } else {
                provider = new NimAiProvider(baseUrl, model, apiKey, fallbackModels, allowedModels, developerPrompt, qaPrompt, businessPrompt);
            }
        }
        return new ProviderEntry(id, label(id), enabled, provider);
    }

    private static String label(String id) {
        return LABELS.getOrDefault(id, id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1));
    }

    /** System properties first so the unit tests can pin provider config regardless of the ambient
     * {@code .env}/environment (dotenv would otherwise outrank their {@code System.setProperty}).
     * Production never sets these system properties, so this is inert there. */
    private static Optional<String> value(Config config, String key) {
        String sys = System.getProperty(key);
        if (sys != null) return Optional.of(sys);
        return config.getOptionalValue(key, String.class);
    }

    /** The provider behind {@code providerId}, or the default when blank. Throws for an unknown or
     * (configured-but-keyless) provider so callers never silently hit the wrong endpoint. */
    public synchronized AiProvider resolve(String providerId) {
        if (providers.isEmpty()) {
            init();
        }
        return resolveProvider(providerId);
    }

    /** Every provider users may actually pick right now (has an API key configured). */
    public synchronized List<AiProviderInfo> list() {
        if (providers.isEmpty()) {
            init();
        }
        List<AiProviderInfo> out = new ArrayList<>();
        for (ProviderEntry entry : providers.values()) {
            if (entry.enabled) {
                out.add(new AiProviderInfo(entry.id, entry.label, true));
            }
        }
        return out;
    }

    private AiProvider resolveProvider(String providerId) {
        String id = normalize(providerId);
        ProviderEntry entry = providers.get(id);
        if (entry == null) {
            throw new AiException("Unknown AI provider: " + (providerId == null ? "" : providerId)
                    + " (available: " + String.join(", ", providers.keySet()) + ")");
        }
        if (!entry.enabled || entry.provider == null) {
            throw new AiException("AI provider '" + id + "' is not configured"
                    + " (no " + "ai.providers." + id + ".api-key set by the admin).");
        }
        return entry.provider;
    }

    public String defaultId() {
        return DEFAULT_PROVIDER;
    }

    private static String normalize(String providerId) {
        if (providerId == null || providerId.isBlank()) return DEFAULT_PROVIDER;
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}