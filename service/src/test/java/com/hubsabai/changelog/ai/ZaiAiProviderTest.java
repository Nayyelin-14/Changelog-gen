package com.hubsabai.changelog.ai;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hubsabai.changelog.core.model.ReleaseData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class ZaiAiProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ZaiAiProvider provider;

    @BeforeEach
    void setUp() {
        configureFor("localhost", wiremock.getPort());
        NimAiProvider.resetModelBlacklist();
        provider = new ZaiAiProvider(
                wiremock.url("/v1/chat/completions"),
                "glm-4.7-flash",
                "test-api-key",
                Optional.of("glm-4.5-flash,glm-4.6v-flash"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void modelDiscoveryReturnsCuratedFreeModelsWhenLiveDiscoveryEmpty() {
        // Live /models returns empty list
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        List<AiModelOption> models = provider.listModels();

        assertEquals(3, models.size());
        assertTrue(models.stream().anyMatch(m -> m.id().equals("glm-4.7-flash")));
        assertTrue(models.stream().anyMatch(m -> m.id().equals("glm-4.5-flash")));
        assertTrue(models.stream().anyMatch(m -> m.id().equals("glm-4.6v-flash")));
    }

    @Test
    void modelDiscoveryReturnsCuratedFreeModelsWhenLiveDiscoveryFails() {
        // Live /models returns 500
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(500)));

        List<AiModelOption> models = provider.listModels();

        assertEquals(3, models.size());
        assertTrue(models.stream().anyMatch(m -> m.id().equals("glm-4.7-flash")));
    }

    @Test
    void modelDiscoveryReturnsLiveModelsWhenAvailable() {
        // Live /models returns some models
        String modelsResponse = """
                {"data":[
                    {"id":"glm-4.5"},
                    {"id":"glm-4.5-air"},
                    {"id":"glm-4.7-flash"},
                    {"id":"glm-5.2"}
                ]}
                """;
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody(modelsResponse)));
        // All live models pass probe
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")));

        List<AiModelOption> models = provider.listModels();

        // Should include live models that pass probe
        assertTrue(models.stream().anyMatch(m -> m.id().equals("glm-4.7-flash")));
        // Curated free models should also appear if they pass probe
        assertTrue(models.size() >= 1);
    }

    @Test
    void generationSucceedsWithFirstModel() {
        stubChatCompletions(200, jsonResponse("Generated output."));

        ReleaseData.ReleaseMeta release = new ReleaseData.ReleaseMeta();
        release.setProject("test-project");
        release.setRepo("test-repo");
        release.setReleaseDate("2026-07-16");

        AiResult result = provider.generateForAudience(List.of(), release, "developer");

        assertEquals("Generated output.", result.getText());
    }

    @Test
    void generationHandles429AndFallsBack() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("glm-4.7-flash"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"code\":\"1305\",\"message\":\"overloaded\"}}")));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("glm-4.5-flash"))
                .willReturn(aResponse().withStatus(200).withBody(jsonResponse("From fallback"))));

        ReleaseData.ReleaseMeta release = new ReleaseData.ReleaseMeta();
        release.setProject("test-project");
        release.setRepo("test-repo");
        release.setReleaseDate("2026-07-16");

        AiResult result = provider.generateForAudience(List.of(), release, "developer");

        assertEquals("From fallback", result.getText());
    }

    @Test
    void streamingHandles429AndFallsBack() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("glm-4.7-flash"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"code\":\"1305\",\"message\":\"overloaded\"}}")));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("glm-4.5-flash"))
                .willReturn(aResponse().withStatus(200).withBody(streamBody("From fallback"))));

        List<String> deltas = new java.util.ArrayList<>();
        AiResult result = provider.chatStream(List.of(new AiMessage("user", "hi")), null, deltas::add);

        assertEquals("From fallback", result.getText());
    }

    @Test
    void streamingThrowsOn429WhenNoFallback() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"code\":\"1305\",\"message\":\"overloaded\"}}")));

        AiStreamException ex = assertThrows(AiStreamException.class, () ->
                provider.chatStream(List.of(new AiMessage("user", "hi")), null, text -> {}));

        assertFalse(ex.anyOutputEmitted());
    }

    @Test
    void modelDiscoveryThrowsOnAuthFailure() {
        // Auth failure on /models should trigger curated fallback, not throw
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

        List<AiModelOption> models = provider.listModels();

        // Should fall back to curated free models
        assertEquals(3, models.size());
    }

    private void stubChatCompletions(int status, String body) {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private static String jsonResponse(String text) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"}}],\"usage\":null}";
    }

    private static String streamBody(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(chunk).append("\"}}]}\n\n");
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }
}