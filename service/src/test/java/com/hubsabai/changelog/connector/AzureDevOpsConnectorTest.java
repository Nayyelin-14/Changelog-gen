package com.hubsabai.changelog.connector;

import com.hubsabai.changelog.connector.azuredevops.WireMockAzureDevOpsResource;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(WireMockAzureDevOpsResource.class)
class AzureDevOpsConnectorTest {

    @Inject
    AzureDevOpsConnector connector;

    @BeforeEach
    void resetStubs() {
        WireMockAzureDevOpsResource.server().resetAll();
    }

    private static ConnectionConfig baseConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setProject("MyProject");
        config.setRepo("my-repo");
        config.setBranch("main");
        return config;
    }

    private static void stubPullRequest(int id, String title, String description) {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/" + id))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "pullRequestId": %d,
                                  "title": "%s",
                                  "description": "%s",
                                  "status": "completed",
                                  "createdBy": {"displayName": "Jane Doe", "uniqueName": "jane@example.com"},
                                  "url": "https://dev.azure.com/test-org/MyProject/_git/my-repo/pullrequest/%d"
                                }
                                """.formatted(id, title, description, id))));
    }

    @Test
    void shouldFetchPullRequestByExplicitPrId() {
        stubPullRequest(456, "Add auth middleware", "Adds JWT-based auth middleware.");
        ConnectionConfig config = baseConfig();
        config.setPrIds(List.of(456));

        ReleaseData data = connector.fetch(config);

        List<ChangeItem> prItems = data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .toList();
        assertEquals(1, prItems.size());
        ChangeItem item = prItems.get(0);
        assertEquals("456", item.getId());
        assertEquals("Add auth middleware", item.getTitle());
        assertEquals("Adds JWT-based auth middleware.", item.getDescription());
        assertEquals("feat", item.getCategory());
        assertEquals("Jane Doe", item.getAuthor());
    }

    @Test
    void shouldExtractMergedPullRequestIdFromRawCommitLogAndFetchIt() {
        stubPullRequest(789, "Add auth middleware", "Adds JWT-based auth middleware.");
        ConnectionConfig config = baseConfig();
        config.setRawCommitLog("=== Merged PR 789: Add auth middleware\nsrc/auth/Filter.java");

        ReleaseData data = connector.fetch(config);

        List<ChangeItem> commitItems = data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.COMMIT)
                .toList();
        List<ChangeItem> prItems = data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .toList();

        assertEquals(1, commitItems.size());
        assertEquals("Merged PR 789: Add auth middleware", commitItems.get(0).getTitle());

        assertEquals(1, prItems.size());
        assertEquals("Add auth middleware", prItems.get(0).getTitle());
        assertEquals("Adds JWT-based auth middleware.", prItems.get(0).getDescription());
    }

    @Test
    void shouldProduceFallbackChangeItemWhenPullRequestFetchFails() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/999"))
                .willReturn(aResponse().withStatus(404)));
        ConnectionConfig config = baseConfig();
        config.setPrIds(List.of(999));

        ReleaseData data = connector.fetch(config);

        List<ChangeItem> prItems = data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .toList();
        assertEquals(1, prItems.size());
        assertTrue(prItems.get(0).getTitle().contains("fetch failed"));
        assertEquals("chore", prItems.get(0).getCategory());
    }

    @Test
    void shouldDeduplicateWhenExplicitPrIdMatchesRegexExtractedId() {
        stubPullRequest(789, "Add auth middleware", "Adds JWT-based auth middleware.");
        ConnectionConfig config = baseConfig();
        config.setPrIds(List.of(789));
        config.setRawCommitLog("=== Merged PR 789: Add auth middleware\nsrc/auth/Filter.java");

        ReleaseData data = connector.fetch(config);

        List<ChangeItem> prItems = data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .toList();
        assertEquals(1, prItems.size());
        WireMockAzureDevOpsResource.server().verify(1,
                getRequestedFor(urlPathEqualTo("/test-org/_apis/git/pullrequests/789")));
    }
}
