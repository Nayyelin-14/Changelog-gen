package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ProjectSummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.core.model.RepositorySummary;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(WireMockAzureDevOpsResource.class)
class AzureDevOpsOrgConnectorTest {

    @Inject
    AzureDevOpsOrgConnector connector;

    @BeforeEach
    void resetStubs() {
        WireMockAzureDevOpsResource.server().resetAll();
    }

    @Test
    void shouldFollowContinuationTokenAcrossProjectPages() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/projects"))
                .inScenario("projects-pagination")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("x-ms-continuationtoken", "page-2")
                        .withBody("""
                                {"count":2,"value":[
                                    {"id":"p1","name":"ProjectOne"},
                                    {"id":"p2","name":"ProjectTwo"}
                                ]}"""))
                .willSetStateTo("page-2"));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/projects"))
                .inScenario("projects-pagination")
                .whenScenarioStateIs("page-2")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[
                                    {"id":"p3","name":"ProjectThree"}
                                ]}""")));

        List<ProjectSummary> projects = connector.listProjects();

        assertEquals(3, projects.size());
        assertEquals(List.of("ProjectOne", "ProjectTwo", "ProjectThree"),
                projects.stream().map(ProjectSummary::name).toList());
    }

    @Test
    void shouldStopWhenNoContinuationTokenIsReturned() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/projects"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[{"id":"p1","name":"OnlyProject"}]}""")));

        List<ProjectSummary> projects = connector.listProjects();

        assertEquals(1, projects.size());
        assertEquals("OnlyProject", projects.get(0).name());
    }

    @Test
    void shouldListRepositoriesForAProject() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":2,"value":[
                                    {"id":"r1","name":"repo-one","defaultBranch":"refs/heads/main"},
                                    {"id":"r2","name":"repo-two","defaultBranch":"refs/heads/main"}
                                ]}""")));

        List<RepositorySummary> repos = connector.listRepositories("MyProject");

        assertEquals(2, repos.size());
        assertEquals("repo-one", repos.get(0).name());
        assertEquals("MyProject", repos.get(0).project());
    }

    @Test
    void shouldCombineCommitsAndPullRequestsIntoOneReleaseData() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[
                                    {"id":"r1","name":"repo-one","defaultBranch":"refs/heads/main"}
                                ]}""")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/commits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[
                                    {"commitId":"abc123","comment":"fix: null pointer on login","author":{"name":"j.doe"},"url":"https://example/commit/abc123"}
                                ]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[
                                    {"pullRequestId":42,"title":"feat: add avatar upload","status":"completed","createdBy":{"displayName":"a.smith"},"url":"https://example/pr/42"}
                                ]}""")));

        ReleaseData data = connector.fetchRepoChanges("MyProject", "repo-one");

        assertEquals("test-org", data.getRelease().getOrg());
        assertEquals("MyProject", data.getRelease().getProject());
        assertEquals("repo-one", data.getRelease().getRepo());
        assertEquals(2, data.getItems().size());

        ChangeItem commit = data.getItems().stream().filter(i -> i.getType() == ChangeItem.ItemType.COMMIT).findFirst().orElseThrow();
        assertEquals("fix", commit.getCategory());
        assertEquals("j.doe", commit.getAuthor());
        assertEquals("repo-one", commit.getRepo());

        ChangeItem pr = data.getItems().stream().filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST).findFirst().orElseThrow();
        assertEquals("feat", pr.getCategory());
        assertEquals("42", pr.getId());
        assertEquals("a.smith", pr.getAuthor());
    }

    @Test
    void shouldReturnEmptyWorkItemsWithoutCallingBatchEndpointWhenWiqlHasNoResults() {
        WireMockAzureDevOpsResource.server().stubFor(post(urlPathEqualTo("/test-org/MyProject/_apis/wit/wiql"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"workItems":[]}""")));

        List<ChangeItem> items = connector.fetchProjectWorkItems("MyProject");

        assertTrue(items.isEmpty());
        WireMockAzureDevOpsResource.server().verify(0,
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/test-org/MyProject/_apis/wit/workitems")));
    }

    @Test
    void shouldFetchWorkItemsBatchAfterWiqlReturnsIds() {
        WireMockAzureDevOpsResource.server().stubFor(post(urlPathEqualTo("/test-org/MyProject/_apis/wit/wiql"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"workItems":[{"id":1234,"url":"https://example/wit/1234"}]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/wit/workitems"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[
                                    {"id":1234,"fields":{"System.Title":"Fix login timeout","System.WorkItemType":"Bug"}}
                                ]}""")));

        List<ChangeItem> items = connector.fetchProjectWorkItems("MyProject");

        assertEquals(1, items.size());
        assertEquals("1234", items.get(0).getId());
        assertEquals("fix", items.get(0).getCategory());
        assertEquals(ChangeItem.ItemType.WORK_ITEM, items.get(0).getType());
    }

    @Test
    void shouldDetectAnExistingChangelogFile() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/items"))
                .withQueryParam("path", com.github.tomakehurst.wiremock.client.WireMock.equalTo("/CHANGELOG.md"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"path":"/CHANGELOG.md","content":"# Changelog\\n\\n## [1.0.0] - 2026-01-01\\n\\n- feat: initial release"}""")));

        assertTrue(connector.hasChangelogFile("MyProject", "repo-one"));
    }

    @Test
    void shouldExtractRawMarkdownFromTheGitItemsJsonEnvelope() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/items"))
                .withQueryParam("path", com.github.tomakehurst.wiremock.client.WireMock.equalTo("/CHANGELOG.md"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"objectId":"abc","path":"/CHANGELOG.md","content":"# Changelog\\n\\n## [1.0.0] - 2026-01-01\\n\\n- feat: initial release"}""")));

        String content = connector.fetchChangelogContent("MyProject", "repo-one");

        assertEquals("# Changelog\n\n## [1.0.0] - 2026-01-01\n\n- feat: initial release", content);
    }

    @Test
    void shouldFallBackToWholeFileWhenNoVersionHeaderMatches() {
        List<AzureDevOpsOrgConnector.ChangelogEntry> entries =
                connector.parseChangelogEntries("- fix: flat bullet with no version header\n- feat: another one");

        assertEquals(1, entries.size());
        assertNull(entries.get(0).version());
        assertTrue(entries.get(0).body().contains("flat bullet"));
    }

    @Test
    void shouldMatchChangelogEntryToCommitByDateWhenNoTagExists() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/refs"))
                .withQueryParam("filter", com.github.tomakehurst.wiremock.client.WireMock.equalTo("tags/"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":0,"value":[]}""")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/commits"))
                .withQueryParam("searchCriteria.itemPath", com.github.tomakehurst.wiremock.client.WireMock.equalTo("/CHANGELOG.md"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[
                            {"commitId":"abc","comment":"docs: v1.4.79","author":{"name":"j.doe","date":"2026-07-07T09:30:00Z"},"url":"https://example/commit/abc"}
                        ]}""")));

        List<AzureDevOpsOrgConnector.ChangelogEntry> entries =
                List.of(new AzureDevOpsOrgConnector.ChangelogEntry("1.4.79", "2026-07-07", "- fix: something"));

        Map<String, AzureDevOpsOrgConnector.ChangelogEnrichment> enrichment =
                connector.enrichChangelogEntries("MyProject", "repo-one", "CHANGELOG.md", entries);

        assertEquals("j.doe", enrichment.get("1.4.79").author());
    }

    @Test
    void shouldResolveRepoDefaultBranchAsShortName() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[{"id":"r1","name":"repo-one","defaultBranch":"refs/heads/main"}]}""")));

        assertEquals("main", connector.defaultBranch("MyProject", "repo-one"));
    }

    // Shared by both tests below: v1.0.0 -> "c-from", v1.1.0 -> "c-to" as real tag refs, resolved
    // via findVersionRange — fetchRepoChanges is version/tag-based, not a raw date-range
    // pass-through (there's no fromDate/toDate query param it could ever send; the previous
    // versions of these two tests asserted on a contract this method no longer has).
    private void stubRepoAndVersionTags() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"count":1,"value":[
                                    {"id":"r1","name":"repo-one","defaultBranch":"refs/heads/main"}
                                ]}""")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/refs"))
                .withQueryParam("filter", com.github.tomakehurst.wiremock.client.WireMock.equalTo("tags/"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":2,"value":[
                            {"name":"refs/tags/v1.1.0","objectId":"c-to","peeledObjectId":"c-to"},
                            {"name":"refs/tags/v1.0.0","objectId":"c-from","peeledObjectId":"c-from"}
                        ]}""")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/commits"))
                .withQueryParam("searchCriteria.toCommitId", com.github.tomakehurst.wiremock.client.WireMock.equalTo("c-to"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":2,"value":[
                            {"commitId":"c-to","comment":"feat: ship 1.1.0","author":{"name":"j.doe","date":"2025-02-01T00:00:00Z"},"url":"https://example/commit/c-to"},
                            {"commitId":"c-from","comment":"feat: ship 1.0.0","author":{"name":"j.doe","date":"2025-01-01T00:00:00Z"},"url":"https://example/commit/c-from"}
                        ]}""")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/commits/c-to"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"commitId":"c-to","comment":"feat: ship 1.1.0","author":{"name":"j.doe","date":"2025-02-01T00:00:00Z"},"url":"https://example/commit/c-to"}""")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/commits/c-from"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"commitId":"c-from","comment":"feat: ship 1.0.0","author":{"name":"j.doe","date":"2025-01-01T00:00:00Z"},"url":"https://example/commit/c-from"}""")));
    }

    @Test
    void shouldFetchOnlyCommitsBetweenTwoVersionTags() {
        stubRepoAndVersionTags();
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":0,"value":[]}""")));

        ReleaseData data = connector.fetchRepoChanges("MyProject", "repo-one", "1.0.0", "1.1.0", null);

        // The older tag (c-from) is the range's exclusive lower boundary — only what's newer than
        // it, up to and including the target tag (c-to), comes back.
        assertEquals(1, data.getItems().size());
        assertEquals("c-to", data.getItems().get(0).getId());
    }

    @Test
    void shouldStopPaginatingPullRequestsOnceOlderThanTheVersionRangeStart() {
        stubRepoAndVersionTags();
        // A full page (matching PULL_REQUEST_PAGE_SIZE=100) so the loop can only stop via the date
        // check below, not the normal "short page means last page" rule — proving early-exit works.
        StringBuilder prs = new StringBuilder("{\"count\":100,\"value\":[");
        for (int i = 0; i < 100; i++) {
            // since = c-from's date (2025-01-01T00:00:00Z) — the last PR here is older than that.
            String creationDate = i < 99 ? "2025-01-15T00:00:00Z" : "2024-06-01T00:00:00Z";
            if (i > 0) prs.append(",");
            prs.append("{\"pullRequestId\":").append(100 - i)
                    .append(",\"title\":\"feat: pr\",\"status\":\"completed\",")
                    .append("\"createdBy\":{\"displayName\":\"a.smith\"},")
                    .append("\"creationDate\":\"").append(creationDate).append("\",")
                    .append("\"url\":\"https://example/pr/").append(100 - i).append("\"}");
        }
        prs.append("]}");

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests"))
                .withQueryParam("$skip", com.github.tomakehurst.wiremock.client.WireMock.equalTo("0"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(prs.toString())));
        // No stub for $skip=100: if the connector fails to stop early, WireMock returns 404 and the call throws.

        ReleaseData data = connector.fetchRepoChanges("MyProject", "repo-one", "1.0.0", "1.1.0", null);

        // 1 commit (c-to) + 99 in-range PRs — the 100th PR predates c-from and is excluded.
        assertEquals(100, data.getItems().size());
    }

    @Test
    void shouldReturnFalseWhenNoChangelogFileExistsUnderEitherCasing() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/items"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"message":"TF401174: could not find item"}""")));

        assertFalse(connector.hasChangelogFile("MyProject", "repo-one"));
    }

    @Test
    void shouldBuildRunChangesFromBuildApiWithPrDedupAndWorkItems() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/build/builds/42"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"id":42,"sourceBranch":"refs/heads/dev","sourceVersion":"c1","triggerInfo":{}}""")));

        // c1 is a plain commit; c2 is the squash-merge commit for PR 55 — extractable via "Merged
        // PR 55", so it must NOT survive as a raw COMMIT item once PR 55 is resolved below.
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/build/builds/42/changes"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":2,"value":[
                            {"id":"c1","message":"feat: add thing","author":{"displayName":"a.dev"},"displayUri":"https://example/commit/c1"},
                            {"id":"c2","message":"Merged PR 55: fix bug","author":{"displayName":"a.dev"},"displayUri":"https://example/commit/c2"}
                        ]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/55"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"pullRequestId":55,"title":"fix bug","status":"completed","createdBy":{"displayName":"a.dev"},
                         "creationDate":"2025-01-01T00:00:00Z","url":"https://example/pr/55"}""")));

        // c1 doesn't mention a PR in its message, so it falls through to the ID-based lookup —
        // stub it as a no-match (no completed PR's lastMergeCommit is "c1") so it stays a plain commit.
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":0,"value":[]}""")));

        // PR 55 was squash-merged: its own source commit ("c3") never lands on dev's own history
        // (only the "Merged PR 55" squash commit above does), so without re-fetching it via this
        // PR-commits endpoint, PR 55 would show zero commits despite real per-commit content
        // existing — the exact bug this test now guards against.
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests/55/commits"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[
                            {"commitId":"c3","comment":"fix: the actual bug","author":{"name":"a.dev","date":"2025-01-01T00:00:00Z"},"url":"https://example/commit/c3"}
                        ]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/build/builds/42/workitems"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[{"id":7,"url":"https://example/workitems/7"}]}""")));

        // Work item 99 is linked to PR 55 itself (via its Development panel) but never mentioned
        // in any commit message, so it would NOT show up in the build's own /workitems above —
        // only fetching it through the PR's own linked-work-items endpoint finds it.
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests/55/workitems"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[{"id":99,"url":"https://example/workitems/99"}]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/wit/workitems"))
                .withQueryParam("ids", com.github.tomakehurst.wiremock.client.WireMock.equalTo("7,99"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":2,"value":[
                            {"id":7,"fields":{"System.Title":"Fix the bug","System.WorkItemType":"Bug"}},
                            {"id":99,"fields":{"System.Title":"PR-linked work item","System.WorkItemType":"Task"}}
                        ]}""")));

        ReleaseData data = connector.fetchRunChanges("MyProject", "repo-one", 42);

        assertEquals(5, data.getItems().size());
        assertEquals(2, data.getItems().stream().filter(i -> i.getType() == ChangeItem.ItemType.COMMIT).count());
        assertEquals(Set.of("c1", "c3"), data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.COMMIT).map(ChangeItem::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, data.getItems().stream().filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST).count());
        assertEquals("55", data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST).findFirst().orElseThrow().getId());
        assertEquals(2, data.getItems().stream().filter(i -> i.getType() == ChangeItem.ItemType.WORK_ITEM).count());
        assertEquals(Set.of("7", "99"), data.getItems().stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.WORK_ITEM).map(ChangeItem::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals("dev", data.getRelease().getBranch());

        // The spec asks that this mixed COMMIT/PULL_REQUEST/WORK_ITEM set render correctly
        // through PlainBullets, not just that fetchRunChanges itself returns the right items.
        String bullets = PlainBullets.plainBullets(data.getItems());
        assertTrue(bullets.contains("feat: add thing"));
        assertTrue(bullets.contains("fix bug"));
        assertTrue(bullets.contains("(PR #55)"));
        assertTrue(bullets.contains("fix: the actual bug"));
        assertTrue(bullets.contains("Fix the bug"));
        assertTrue(bullets.contains("PR-linked work item"));
    }

    @Test
    void shouldResolveRunChangesPrByMergeCommitIdWhenMessageHasNoPrReference() {
        // Simulates a repo whose PRs are squash/rebase-completed with a custom commit-message
        // template — "c9" is the commit that landed PR 91, but its message never says "Merged PR
        // 91", so PrReference's text match can't find it. The connector must still resolve PR 91
        // by matching c9 against a completed PR's own lastMergeCommit.
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/build/builds/77"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"id":77,"sourceBranch":"refs/heads/dev","sourceVersion":"c9","triggerInfo":{}}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/build/builds/77/changes"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[
                            {"id":"c9","message":"fix: tighten retry backoff","author":{"displayName":"a.dev"},"displayUri":"https://example/commit/c9"}
                        ]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/build/builds/77/workitems"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":0,"value":[]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[
                            {"pullRequestId":91,"title":"Tighten retry backoff","status":"completed","createdBy":{"displayName":"a.dev"},
                             "creationDate":"2025-02-01T00:00:00Z","url":"https://example/pr/91","lastMergeCommit":{"commitId":"c9"}}
                        ]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/91"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"pullRequestId":91,"title":"Tighten retry backoff","status":"completed","createdBy":{"displayName":"a.dev"},
                         "creationDate":"2025-02-01T00:00:00Z","url":"https://example/pr/91"}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests/91/commits"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":0,"value":[]}""")));

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/MyProject/_apis/git/repositories/repo-one/pullrequests/91/workitems"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"count":0,"value":[]}""")));

        ReleaseData data = connector.fetchRunChanges("MyProject", "repo-one", 77);

        assertEquals(1, data.getItems().size());
        ChangeItem pr = data.getItems().get(0);
        assertEquals(ChangeItem.ItemType.PULL_REQUEST, pr.getType());
        assertEquals("91", pr.getId());
    }
}
