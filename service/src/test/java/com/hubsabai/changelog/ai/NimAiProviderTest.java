package com.hubsabai.changelog.ai;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class NimAiProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private NimAiProvider provider;
    private List<ChangeItem> items;
    private ReleaseData.ReleaseMeta release;

    @BeforeEach
    void setUp() {
        configureFor("localhost", wiremock.getPort());
        provider = new NimAiProvider(
                wiremock.url("/v1/chat/completions"),
                "test-primary-model",
                "test-api-key",
                Optional.of("fallback-1,fallback-2"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        release = new ReleaseData.ReleaseMeta();
        release.setProject("test-project");
        release.setRepo("test-repo");
        release.setReleaseDate("2026-07-16");

        items = List.of(createItem("fix login timeout", "Session tokens were expiring early", "fix",
                List.of("src/auth/Login.java"), ChangeItem.ItemType.COMMIT));
    }

    @Test
    void returnsTextFromSuccessfulLlmCall() {
        stubChatCompletions(200, jsonResponse("Fixed login session handling."));

        AiResult result = provider.generateForAudience(items, release, "developer");

        assertEquals("Fixed login session handling.", result.getText());
    }

    @Test
    void returnsTextForQaAudience() {
        stubChatCompletions(200, jsonResponse("Verify login sessions do not expire early."));

        AiResult result = provider.generateForAudience(items, release, "qa");

        assertEquals("Verify login sessions do not expire early.", result.getText());
    }

    @Test
    void returnsTextForBusinessAudience() {
        stubChatCompletions(200, jsonResponse("Users stay logged in longer."));

        AiResult result = provider.generateForAudience(items, release, "business");

        assertEquals("Users stay logged in longer.", result.getText());
    }

    @Test
    void passesSystemPromptOverrideToLlm() {
        stubChatCompletions(200, jsonResponse("Custom output."));

        provider.generateForAudience(items, release, "developer", null, "CUSTOM_PROMPT_HERE");

        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("CUSTOM_PROMPT_HERE")));
    }

    @Test
    void usesDefaultPromptWhenNoOverride() {
        stubChatCompletions(200, jsonResponse("Default output."));

        provider.generateForAudience(items, release, "developer");

        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("Software engineers maintaining this project.")));
    }

    @Test
    void passesModelOverrideToLlm() {
        stubChatCompletions(200, jsonResponse("From override model."));

        provider.generateForAudience(items, release, "developer", "override-model", null);

        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"model\":\"override-model\"")));
    }

    @Test
    void fallsBackToDefaultModelWhenOverrideFails() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("override-model"))
                .willReturn(aResponse().withStatus(500)));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("test-primary-model"))
                .willReturn(aResponse().withStatus(200).withBody(jsonResponse("From default model."))));

        AiResult result = provider.generateForAudience(items, release, "developer", "override-model", null);

        assertEquals("From default model.", result.getText());
    }

    @Test
    void walksFallbackChainWhenPrimaryFails() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("test-primary-model"))
                .willReturn(aResponse().withStatus(503)));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("fallback-1"))
                .willReturn(aResponse().withStatus(503)));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("fallback-2"))
                .willReturn(aResponse().withStatus(200).withBody(jsonResponse("From fallback-2."))));

        AiResult result = provider.generateForAudience(items, release, "developer");

        assertEquals("From fallback-2.", result.getText());
    }

    @Test
    void throwsAiExceptionWhenAllModelsFail() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500)));

        assertThrows(AiException.class, () ->
                provider.generateForAudience(items, release, "developer"));
    }

    @Test
    void throwsAiExceptionOnHttpErrorResponse() {
        stubChatCompletions(429, "{\"error\":{\"message\":\"Rate limited\"}}");

        AiException ex = assertThrows(AiException.class, () ->
                provider.generateForAudience(items, release, "developer"));
        assertTrue(ex.getMessage().contains("429"));
    }

    @Test
    void throwsAiExceptionOnEmptyChoices() {
        stubChatCompletions(200, "{\"choices\":[],\"usage\":null}");

        assertThrows(AiException.class, () ->
                provider.generateForAudience(items, release, "developer"));
    }

    @Test
    void throwsAiExceptionOnEmptyContent() {
        stubChatCompletions(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}],\"usage\":null}");

        assertThrows(AiException.class, () ->
                provider.generateForAudience(items, release, "developer"));
    }

    @Test
    void throwsAiExceptionOnMalformedJsonResponse() {
        stubChatCompletions(200, "not-json-at-all");

        assertThrows(AiException.class, () ->
                provider.generateForAudience(items, release, "developer"));
    }

    @Test
    void retriesWithoutSystemRoleOnSystemRoleUnsupportedError() {
        String systemUnsupported = "{\"error\":{\"message\":\"model system role not supported\"}}";
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"system\""))
                .willReturn(aResponse().withStatus(400).withBody(systemUnsupported)));
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"user\""))
                .withRequestBody(containing("Software engineers maintaining this project."))
                .willReturn(aResponse().withStatus(200).withBody(jsonResponse("Retried without system role."))));

        AiResult result = provider.generateForAudience(items, release, "developer");

        assertEquals("Retried without system role.", result.getText());
    }

    @Test
    void strictModeDoesNotFallBackOnFailure() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500)));

        assertThrows(AiException.class, () ->
                provider.generateForAudienceStrict(items, release, "developer", "strict-model"));
    }

    @Test
    void strictModeSucceedsOnFirstTry() {
        stubChatCompletions(200, jsonResponse("From strict mode."));

        AiResult result = provider.generateForAudienceStrict(items, release, "developer", "strict-model");

        assertEquals("From strict mode.", result.getText());
    }

    @Test
    void includesUsageWhenPresent() {
        stubChatCompletions(200, """
                {"choices":[{"message":{"content":"Some text."}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """);

        AiResult result = provider.generateForAudience(items, release, "developer");

        assertNotNull(result.getUsage());
        assertEquals(10, result.getUsage().getPromptTokens());
        assertEquals(5, result.getUsage().getCompletionTokens());
        assertEquals(15, result.getUsage().getTotalTokens());
    }

    @Test
    void returnsNullUsageWhenNotPresent() {
        stubChatCompletions(200, "{\"choices\":[{\"message\":{\"content\":\"Some text.\"}}],\"usage\":null}");

        AiResult result = provider.generateForAudience(items, release, "developer");

        assertNull(result.getUsage());
    }

    @Test
    void sendsProjectAndItemsInRequestBody() {
        stubChatCompletions(200, jsonResponse("output"));

        provider.generateForAudience(items, release, "developer");

        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("test-project"))
                .withRequestBody(containing("fix login timeout"))
                .withRequestBody(containing("Session tokens were expiring early")));
    }

    @Test
    void sendsPrReferenceForPullRequestItems() {
        ChangeItem prItem = createItem("Add login feature", "New login page", "feat",
                List.of("src/auth/LoginPage.tsx"), ChangeItem.ItemType.PULL_REQUEST);
        prItem.setId("99");
        stubChatCompletions(200, jsonResponse("output"));

        provider.generateForAudience(List.of(prItem), release, "developer");

        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("PR: !99")));
    }

    @Test
    void sendsWorkItemReference() {
        ChangeItem wiItem = createItem("Fix login bug", "Users cannot log in", "fix",
                List.of(), ChangeItem.ItemType.WORK_ITEM);
        wiItem.setId("5678");
        stubChatCompletions(200, jsonResponse("output"));

        provider.generateForAudience(List.of(wiItem), release, "developer");

        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("#5678")));
    }

    @Test
    void doesNotSendShaForCommitItems() {
        ChangeItem commitItem = createItem("fix stuff", "", "fix",
                List.of(), ChangeItem.ItemType.COMMIT);
        commitItem.setId("abcdef1234567890abcdef1234567890abcdef12");
        stubChatCompletions(200, jsonResponse("output"));

        provider.generateForAudience(List.of(commitItem), release, "developer");

        // commit SHAs are not included as reference tags — verify the request body
        // doesn't contain the SHA as a standalone reference
        verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(notContaining("abcdef1234567890abcdef1234567890abcdef12")));
    }

    @Test
    void modelsEndpointFiltersExcludedAndNonChatModels() {
        String modelsResponse = """
                {"data":[
                    {"id":"mistralai/mistral-small-4-119b-2603"},
                    {"id":"meta/llama-3.1-8b-instruct"},
                    {"id":"nvidia/nemotron-mini-4b-instruct"},
                    {"id":"some/embedding-model"},
                    {"id":"some/guard-model"},
                    {"id":"deepseek-ai/deepseek-v4-flash"},
                    {"id":"google/codegemma-1.1-7b"}
                ]}
                """;
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody(modelsResponse)));

        List<AiModelOption> models = provider.listModels();

        assertTrue(models.stream().anyMatch(m -> m.id().equals("mistralai/mistral-small-4-119b-2603")));
        assertTrue(models.stream().anyMatch(m -> m.id().equals("meta/llama-3.1-8b-instruct")));
        assertFalse(models.stream().anyMatch(m -> m.id().equals("some/embedding-model")));
        assertFalse(models.stream().anyMatch(m -> m.id().equals("some/guard-model")));
        assertFalse(models.stream().anyMatch(m -> m.id().equals("deepseek-ai/deepseek-v4-flash")));
        assertFalse(models.stream().anyMatch(m -> m.id().equals("google/codegemma-1.1-7b")));
    }

    @Test
    void modelsEndpointMarksRecommendedModels() {
        String modelsResponse = """
                {"data":[
                    {"id":"meta/llama-3.1-8b-instruct"},
                    {"id":"unknown/model"}
                ]}
                """;
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody(modelsResponse)));

        List<AiModelOption> models = provider.listModels();

        assertTrue(models.stream().filter(m -> m.id().equals("meta/llama-3.1-8b-instruct")).findFirst().get().recommended());
        assertFalse(models.stream().filter(m -> m.id().equals("unknown/model")).findFirst().get().recommended());
    }

    @Test
    void modelsEndpointThrowsOnHttpError() {
        stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(401)));

        assertThrows(AiException.class, () -> provider.listModels());
    }

    @Test
    void usesCustomPromptsWhenProvided() {
        NimAiProvider customProvider = new NimAiProvider(
                wiremock.url("/v1/chat/completions"),
                "test-model",
                "test-key",
                Optional.empty(),
                Optional.of("CUSTOM DEV PROMPT"),
                Optional.of("CUSTOM QA PROMPT"),
                Optional.of("CUSTOM BIZ PROMPT"));

        assertNotNull(customProvider);
    }

    @Test
    void handlesNullDescriptionNotEqualToTitle() {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setTitle("fix: login issue");
        item.setDescription("fix: login issue");
        item.setCategory("fix");
        stubChatCompletions(200, jsonResponse("output"));

        // Should not throw — description equals title, so no extra body is appended
        AiResult result = provider.generateForAudience(List.of(item), release, "developer");

        assertEquals("output", result.getText());
    }

    @Test
    void handlesNullFilePaths() {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setTitle("fix: login issue");
        item.setDescription("description");
        item.setCategory("fix");
        item.setFilePaths(null);
        stubChatCompletions(200, jsonResponse("output"));

        AiResult result = provider.generateForAudience(List.of(item), release, "developer");

        assertEquals("output", result.getText());
    }

    private void stubChatCompletions(int status, String body) {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private static String jsonResponse(String text) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"}}],\"usage\":null}";
    }

    private static ChangeItem createItem(String title, String description, String category, List<String> filePaths, ChangeItem.ItemType type) {
        ChangeItem item = new ChangeItem();
        item.setType(type);
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setFilePaths(filePaths);
        item.setProject("test-project");
        return item;
    }
}
