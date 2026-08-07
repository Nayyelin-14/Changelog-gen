package com.hubsabai.changelog.ai;

import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import java.util.List;

public interface AiProvider {

    AiResult generateForAudience(List<ChangeItem> items, ReleaseData.ReleaseMeta release, String audience, String model, String systemPromptOverride);

    default AiResult generateForAudience(List<ChangeItem> items, ReleaseData.ReleaseMeta release, String audience, String model) {
        return generateForAudience(items, release, audience, model, null);
    }

    default AiResult generateForAudience(List<ChangeItem> items, ReleaseData.ReleaseMeta release, String audience) {
        return generateForAudience(items, release, audience, null, null);
    }

    /**
     * Like {@link #generateForAudience(List, ReleaseData.ReleaseMeta, String, String)} but never
     * silently falls back to the configured default model on failure — for benchmarking, where a
     * fallback would make a broken model look like it succeeded.
     */
    AiResult generateForAudienceStrict(List<ChangeItem> items, ReleaseData.ReleaseMeta release, String audience, String model);

    /** The models this account can currently call, fetched live from the provider — never a hardcoded list. */
    List<AiModelOption> listModels();

    /** Receives each token/chunk as it arrives during {@link #chatStream}. */
    interface StreamHandler {
        void onDelta(String text);
    }

    /**
     * Freeform chat completion streamed via {@code handler}. Falls back across the model chain
     * until real output has been emitted — thereafter a failure is thrown (retrying would
     * duplicate/corrupt content already shown). Returns the full accumulated text and usage once
     * the model finishes.
     */
    AiResult chatStream(List<AiMessage> messages, String modelOverride, StreamHandler handler);
}
