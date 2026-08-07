package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

public class NimAiProvider implements AiProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Logger LOG = Logger.getLogger(NimAiProvider.class.getName());

    private final Client client;
    private final String baseUrl;
    private final String model;
    private final List<String> fallbackModels;
    private final String apiKey;
    private final String developerPromptOverride;
    private final String qaPromptOverride;
    private final String businessPromptOverride;

    public NimAiProvider(
            String baseUrl,
            String model,
            String apiKey,
            Optional<String> fallbackModels,
            Optional<String> developerPrompt,
            Optional<String> qaPrompt,
            Optional<String> businessPrompt) {
        this.client = ClientBuilder.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.fallbackModels = fallbackModels.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.apiKey = apiKey;
        this.developerPromptOverride = developerPrompt.orElse(null);
        this.qaPromptOverride = qaPrompt.orElse(null);
        this.businessPromptOverride = businessPrompt.orElse(null);
    }

    @Override
    public AiResult generateForAudience(List<ChangeItem> items, ReleaseData.ReleaseMeta release,
            String audience, String modelOverride, String systemPromptOverride) {
        if (systemPromptOverride != null && !systemPromptOverride.isBlank()) {
            String userText = buildUserText(items, release);
            return callWithFallback(modelOverride,
                    candidate -> callModel(candidate, audience, systemPromptOverride, userText), audience);
        }

        List<ReleaseNoteEntry> entries = ReleaseNotePreparer.prepare(items);
        PromptRequest prompt = PromptComposer.compose(
                release.getProject(), getVersion(release), entries, audience);

        String userText = prompt.userPrompt();
        String systemText = overrideAudiencePrompt(audience) != null
                ? overrideAudiencePrompt(audience) + "\n\n" + prompt.systemPrompt()
                : prompt.systemPrompt();

        LOG.info("Prompt version: " + prompt.promptVersion()
                + " | audience: " + audience
                + " | entries: " + entries.size());

        return callWithFallback(modelOverride,
                candidate -> callModel(candidate, audience, systemText, userText), audience);
    }

    private String overrideAudiencePrompt(String audience) {
        return switch (audience) {
            case "qa" -> qaPromptOverride;
            case "business" -> businessPromptOverride;
            default -> developerPromptOverride;
        };
    }

    private static String getVersion(ReleaseData.ReleaseMeta release) {
        return release != null ? release.getMilestone() : null;
    }

    /**
     * Walks the model chain (requested → default → fallbacks, no repeats) calling {@code attempt}
     * until one succeeds. {@link AiStreamException} with {@code anyOutputEmitted()=true} is
     * rethrown immediately (retrying would duplicate content already shown to the caller). If all
     * candidates fail, the last error is thrown.
     */
    private AiResult callWithFallback(String modelOverride, Function<String, AiResult> attempt, String context) {
        String activeModel = modelOverride != null ? modelOverride : model;
        List<String> chain = new ArrayList<>();
        chain.add(activeModel);
        if (!activeModel.equals(model)) chain.add(model);
        chain.addAll(fallbackModels);

        Exception lastError = null;
        for (int i = 0; i < chain.size(); i++) {
            String candidate = chain.get(i);
            try {
                return attempt.apply(candidate);
            } catch (AiStreamException e) {
                if (e.anyOutputEmitted()) throw e;
                lastError = e;
            } catch (Exception e) {
                lastError = e;
            }
            boolean isLast = i == chain.size() - 1;
            if (!isLast) {
                LOG.warning("Model " + candidate + " failed for " + context + " (" + lastError.getMessage()
                        + ") — " + (chain.size() - i - 1) + " fallback(s) remaining");
            }
        }
        throw lastError instanceof AiException aiException ? aiException
                : new AiException("LLM call failed (" + context + "): all " + chain.size()
                        + " models in the chain failed. Last error: " + lastError.getMessage(), lastError);
    }

    @Override
    public AiResult chatStream(List<AiMessage> messages, String modelOverride, AiProvider.StreamHandler handler) {
        boolean[] anyDeltaEmitted = {false};
        AiProvider.StreamHandler guarded = text -> {
            anyDeltaEmitted[0] = true;
            handler.onDelta(text);
        };
        return callWithFallback(modelOverride, candidate -> {
            try {
                return callModelWithMessagesStreaming(candidate, messages, guarded);
            } catch (AiStreamException already) {
                throw already;
            } catch (Exception e) {
                throw new AiStreamException(e.getMessage(), e, anyDeltaEmitted[0]);
            }
        }, "chat");
    }

    @Override
    public AiResult generateForAudienceStrict(List<ChangeItem> items, ReleaseData.ReleaseMeta release,
            String audience, String model) {
        List<ReleaseNoteEntry> entries = ReleaseNotePreparer.prepare(items);
        PromptRequest prompt = PromptComposer.compose(
                release.getProject(), getVersion(release), entries, audience);
        return callModel(model, audience, prompt.systemPrompt(), prompt.userPrompt());
    }

    private AiResult callModel(String modelId, String audience, String systemPrompt, String userText) {
        try {
            return callModelWithMessages(modelId, audience, List.of(
                    new AiMessage("system", systemPrompt),
                    new AiMessage("user", userText)
            ));
        } catch (AiException e) {
            if (isSystemRoleUnsupported(e)) {
                LOG.info("Model " + modelId + " rejects a system message — retrying with it merged into the user message");
                return callModelWithMessages(modelId, audience, List.of(
                        new AiMessage("user", systemPrompt + "\n\n" + userText)
                ));
            }
            throw e;
        }
    }

    private static boolean isSystemRoleUnsupported(AiException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("system role not supported");
    }

    private AiResult callModelWithMessages(String modelId, String audience, List<AiMessage> messages) {
        AiRequest request = new AiRequest(modelId, messages, 0.3);

        String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new AiException("Failed to serialize request for " + audience + " (model " + modelId + ")", e);
        }

        Response httpResponse;
        try {
            httpResponse = client.target(baseUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(Entity.entity(requestJson, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new AiException("LLM call failed (" + audience + ", model " + modelId + "): " + e.getMessage(), e);
        }
        try {
            String rawBody = httpResponse.readEntity(String.class);
            if (httpResponse.getStatus() >= 300) {
                throw new AiException("LLM call failed (" + audience + ", model " + modelId + "): HTTP "
                        + httpResponse.getStatus() + " — " + truncate(rawBody));
            }

            AiResponse response;
            try {
                response = MAPPER.readValue(rawBody, AiResponse.class);
            } catch (Exception e) {
                throw new AiException("LLM response for " + audience + " (model " + modelId
                        + ") wasn't the expected shape. Raw response: " + truncate(rawBody), e);
            }

            if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                String text = response.getChoices().get(0).getMessage().getContentOrReasoning();
                if (text == null || text.isBlank()) {
                    throw new AiException("LLM returned an empty message for " + audience + " (model " + modelId
                            + "). Raw response: " + truncate(rawBody));
                }
                AiUsage usage = null;
                if (response.getUsage() != null) {
                    usage = new AiUsage(
                            response.getUsage().getPromptTokens(),
                            response.getUsage().getCompletionTokens(),
                            response.getUsage().getTotalTokens());
                }
                return new AiResult(stripCodeFence(text), usage);
            }
            throw new AiException("LLM returned no choices for " + audience + " (model " + modelId
                    + "). Raw response: " + truncate(rawBody));
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("LLM call failed (" + audience + ", model " + modelId + "): " + e.getMessage(), e);
        } finally {
            httpResponse.close();
        }
    }

    private AiResult callModelWithMessagesStreaming(String modelId, List<AiMessage> messages,
            AiProvider.StreamHandler handler) {
        Map<String, Object> streamingRequest = new LinkedHashMap<>();
        streamingRequest.put("model", modelId);
        streamingRequest.put("messages", messages);
        streamingRequest.put("temperature", 0.3);
        streamingRequest.put("stream", true);

        String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(streamingRequest);
        } catch (Exception e) {
            throw new AiException("Failed to serialize chat request (model " + modelId + ")", e);
        }

        Response httpResponse;
        try {
            httpResponse = client.target(baseUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(Entity.entity(requestJson, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new AiException("Chat call failed (model " + modelId + "): " + e.getMessage(), e);
        }

        if (httpResponse.getStatus() >= 300) {
            int status = httpResponse.getStatus();
            String body = httpResponse.readEntity(String.class);
            httpResponse.close();
            throw new AiException("Chat call failed (model " + modelId + "): HTTP " + status + " — " + truncate(body));
        }

        StringBuilder accumulated = new StringBuilder();
        AiUsage usage = null;
        try (InputStream body = httpResponse.readEntity(InputStream.class);
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String payload = line.substring(6).trim();
                if (payload.equals("[DONE]") || payload.isEmpty()) {
                    if (payload.equals("[DONE]")) break;
                    continue;
                }

                AiResponse chunk;
                try {
                    chunk = MAPPER.readValue(payload, AiResponse.class);
                } catch (Exception malformed) {
                    continue;
                }
                if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) continue;

                AiResponse.Message delta = chunk.getChoices().get(0).getDelta();
                String text = delta != null ? delta.getContentOrReasoning() : null;
                if (text != null && !text.isEmpty()) {
                    accumulated.append(text);
                    handler.onDelta(text);
                }
                if (chunk.getUsage() != null) {
                    usage = new AiUsage(chunk.getUsage().getPromptTokens(),
                            chunk.getUsage().getCompletionTokens(), chunk.getUsage().getTotalTokens());
                }
            }
        } catch (AiStreamException already) {
            throw already;
        } catch (Exception e) {
            throw new AiException("Chat stream failed (model " + modelId + "): " + e.getMessage(), e);
        } finally {
            httpResponse.close();
        }

        if (accumulated.length() == 0) {
            throw new AiException("Chat returned no content (model " + modelId + ")");
        }
        return new AiResult(stripCodeFence(accumulated.toString()), usage, modelId);
    }

    // Prompt instructions ask for raw Markdown, never wrapped in a fence, but not every model
    // follows that reliably (smaller/weaker ones especially) — this is a defensive net so a
    // stray ```markdown ... ``` wrapper never ends up saved as part of the actual changelog text.
    // Only strips a fence that wraps the WHOLE response (matches start-to-end); a fence embedded
    // inside real bullet content is left alone, since that's legitimate Markdown, not a wrapper.
    private static final java.util.regex.Pattern WRAPPING_FENCE =
            java.util.regex.Pattern.compile("\\A```[^\\n]*\\r?\\n([\\s\\S]*?)\\r?\\n?```\\s*\\z");

    static String stripCodeFence(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        java.util.regex.Matcher matcher = WRAPPING_FENCE.matcher(trimmed);
        return matcher.matches() ? matcher.group(1).strip() : trimmed;
    }

    private static String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 800 ? text.substring(0, 800) + "…" : text;
    }

    @Override
    public List<AiModelOption> listModels() {
        String modelsUrl = baseUrl.replace("/chat/completions", "/models");
        try {
            Response raw = client.target(modelsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .get();
            NvidiaModelsResponse response = MAPPER.readValue(raw.readEntity(String.class), NvidiaModelsResponse.class);
            return response.dataOrEmpty().stream()
                    .map(NvidiaModelsResponse.NvidiaModel::id)
                    .filter(NimAiProvider::looksLikeChatModel)
                    .filter(id -> !EXCLUDED_MODELS.contains(id))
                    .map(id -> new AiModelOption(id, prettifyModelId(id), isRecommended(id)))
                    .sorted(Comparator.<AiModelOption, Boolean>comparing(m -> m.id().equals(model), Comparator.reverseOrder())
                            .thenComparing(AiModelOption::recommended, Comparator.reverseOrder())
                            .thenComparing(AiModelOption::label))
                    .toList();
        } catch (WebApplicationException e) {
            String body = e.getResponse().readEntity(String.class);
            throw new AiException("Failed to list models: HTTP " + e.getResponse().getStatus() + " — " + body, e);
        } catch (Exception e) {
            throw new AiException("Failed to list models: " + e.getMessage(), e);
        }
    }

    private static final Set<String> EXCLUDED_MODELS = Set.of(
        "google/codegemma-1.1-7b", "google/codegemma-7b", "meta/codellama-70b",
        "mistralai/codestral-22b-instruct-v0.1", "nvidia/cosmos-reason2-8b",
        "databricks/dbrx-instruct", "deepseek-ai/deepseek-coder-6.7b-instruct",
        "google/gemma-2b", "google/gemma-3-12b-it", "google/gemma-3-4b-it",
        "ibm/granite-3.0-3b-a800m-instruct", "ibm/granite-3.0-8b-instruct",
        "ibm/granite-34b-code-instruct", "ibm/granite-8b-code-instruct",
        "ai21labs/jamba-1.5-large-instruct", "moonshotai/kimi-k2.6",
        "nvidia/llama-3.1-nemotron-51b-instruct", "nvidia/llama-3.1-nemotron-70b-instruct",
        "nvidia/llama-3.1-nemotron-ultra-253b-v1", "meta/llama2-70b",
        "nvidia/llama3-chatqa-1.5-70b", "mistralai/mistral-7b-instruct-v0.3",
        "mistralai/mistral-large", "mistralai/mistral-large-2-instruct",
        "nv-mistralai/mistral-nemo-12b-instruct", "nvidia/mistral-nemo-minitron-8b-8k-instruct",
        "mistralai/mixtral-8x22b-v0.1", "nvidia/nemotron-4-340b-instruct",
        "nvidia/nemotron-nano-3-30b-a3b", "writer/palmyra-creative-122b",
        "writer/palmyra-fin-70b-32k", "writer/palmyra-med-70b", "writer/palmyra-med-70b-32k",
        "microsoft/phi-3-vision-128k-instruct", "microsoft/phi-3.5-moe-instruct",
        "google/recurrentgemma-2b", "aisingapore/sea-lion-7b-instruct",
        "bigcode/starcoder2-15b", "01-ai/yi-large", "zyphra/zamba2-7b-instruct",
        "deepseek-ai/deepseek-v4-flash", "mistralai/mistral-nemotron",
        "microsoft/phi-4-multimodal-instruct", "minimaxai/minimax-m3",
        "z-ai/glm-5.2", "qwen/qwen3.5-397b-a17b", "google/gemma-3n-e2b-it",
        "google/gemma-3n-e4b-it", "nvidia/llama-3.1-nemotron-nano-8b-v1",
        "meta/llama-3.2-1b-instruct", "meta/llama-3.2-3b-instruct",
        "meta/llama-3.3-70b-instruct", "meta/llama-4-maverick-17b-128e-instruct",
        "microsoft/phi-4-mini-instruct", "bytedance/seed-oss-36b-instruct",
        "deepseek-ai/deepseek-v4-pro", "mistralai/ministral-14b-instruct-2512",
        "qwen/qwen3-next-80b-a3b-instruct", "stepfun-ai/step-3.7-flash"
    );

    private static final Set<String> RECOMMENDED_MODELS = Set.of(
        "meta/llama-3.1-8b-instruct", "google/gemma-2-2b-it",
        "mistralai/mistral-small-4-119b-2603", "poolside/laguna-xs-2.1",
        "nvidia/nemotron-mini-4b-instruct"
    );

    private static boolean isRecommended(String id) {
        return RECOMMENDED_MODELS.contains(id);
    }

    private static boolean looksLikeChatModel(String id) {
        String lower = id.toLowerCase();
        if (lower.contains("embed") || lower.contains("guard") || lower.contains("safety")
                || lower.contains("translate") || lower.contains("clip") || lower.contains("kosmos")
                || lower.contains("fuyu") || lower.contains("deplot") || lower.contains("gliner")
                || lower.contains("detector") || lower.contains("parse") || lower.contains("reward")
                || lower.contains("vila") || lower.contains("neva") || lower.contains("bge")
                || lower.contains("diffusion")) {
            return false;
        }
        return true;
    }

    private static String prettifyModelId(String id) {
        String name = id.contains("/") ? id.substring(id.indexOf('/') + 1) : id;
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("[-_]")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // Legacy — kept for backward compatibility of the buildUserText method signature
    // used directly by some callers (tests). New code goes through ReleaseNotePreparer + PromptComposer.
    private String buildUserText(List<ChangeItem> items, ReleaseData.ReleaseMeta release) {
        List<ReleaseNoteEntry> entries = ReleaseNotePreparer.prepare(items);
        PromptRequest prompt = PromptComposer.compose(
                release.getProject(), getVersion(release), entries, "developer");
        return prompt.userPrompt();
    }
}
