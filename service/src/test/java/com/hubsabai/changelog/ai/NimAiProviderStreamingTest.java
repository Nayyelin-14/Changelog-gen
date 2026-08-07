package com.hubsabai.changelog.ai;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Covers {@link NimAiProvider#chatStream} — separate from {@link NimAiProviderTest} since it
 * exercises a different code path (SSE-chunk parsing, the non-retryable-after-partial-output
 * fallback rule) rather than the blocking generation flow. */
class NimAiProviderStreamingTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private NimAiProvider provider;

    @BeforeEach
    void setUp() {
        configureFor("localhost", wiremock.getPort());
        provider = new NimAiProvider(
                wiremock.url("/v1/chat/completions"),
                "test-primary-model",
                "test-api-key",
                Optional.of("fallback-1"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void accumulatesDeltasAndCallsHandlerForEach() {
        stubStream(200, streamBody("Hello", " world"));

        List<String> deltas = new ArrayList<>();
        AiResult result = provider.chatStream(List.of(new AiMessage("user", "hi")), null, deltas::add);

        assertEquals(List.of("Hello", " world"), deltas);
        assertEquals("Hello world", result.getText());
        assertEquals("test-primary-model", result.getModel());
    }

    @Test
    void reportsTheFallbackModelThatActuallyAnswered() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("test-primary-model"))
                .willReturn(aResponse().withStatus(503)));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("fallback-1"))
                .willReturn(aResponse().withStatus(200).withBody(streamBody("From fallback"))));

        AiResult result = provider.chatStream(List.of(new AiMessage("user", "hi")), null, text -> {});

        // Confirms the UI-facing "which model answered" reflects the model that actually
        // succeeded — not whatever was originally requested — since that's exactly the case a
        // fallback exists to handle.
        assertEquals("fallback-1", result.getModel());
    }

    @Test
    void fallsBackToNextModelWhenFirstModelNeverEmitsAnything() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("test-primary-model"))
                .willReturn(aResponse().withStatus(503)));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("fallback-1"))
                .willReturn(aResponse().withStatus(200).withBody(streamBody("From fallback"))));

        List<String> deltas = new ArrayList<>();
        AiResult result = provider.chatStream(List.of(new AiMessage("user", "hi")), null, deltas::add);

        assertEquals("From fallback", result.getText());
    }

    @Test
    void doesNotFallBackOnceOutputHasAlreadyBeenEmitted() {
        stubStream(200, streamBody("partial"));

        List<String> deltas = new ArrayList<>();
        // The handler itself fails after the first delta reaches it, simulating a disconnect
        // mid-stream — this must surface as a non-retryable failure, not a silent model switch.
        AiProvider.StreamHandler failingAfterFirstDelta = text -> {
            deltas.add(text);
            throw new RuntimeException("client disconnected");
        };

        AiStreamException ex = assertThrows(AiStreamException.class, () ->
                provider.chatStream(List.of(new AiMessage("user", "hi")), null, failingAfterFirstDelta));

        assertTrue(ex.anyOutputEmitted());
        assertEquals(1, deltas.size());
    }

    @Test
    void throwsAiStreamExceptionWhenAllModelsFail() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)));

        AiStreamException ex = assertThrows(AiStreamException.class, () ->
                provider.chatStream(List.of(new AiMessage("user", "hi")), null, text -> {}));

        assertFalse(ex.anyOutputEmitted());
    }

    private void stubStream(int status, String body) {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /** One {@code data:} line per chunk, terminated by {@code [DONE]} — the shape an
     * OpenAI-compatible {@code stream: true} response actually sends. */
    private static String streamBody(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(chunk).append("\"}}]}\n\n");
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }
}
