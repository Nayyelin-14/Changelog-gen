package com.hubsabai.changelog.connector.github;

import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ProjectSummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.core.model.RepositorySummary;
import com.hubsabai.changelog.generation.RunChangeContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(WireMockGitHubOrgResource.class)
class GitHubOrgConnectorTest {

    @Inject
    GitHubOrgConnector connector;

    @BeforeEach
    void resetStubs() {
        WireMockGitHubOrgResource.server().resetAll();
        connector.clearCaches();
    }

    @Test
    void shouldListConfiguredOwnerAsTheSingleProject() {
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/users/test-owner"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"login":"test-owner","name":"Test Owner","html_url":"https://github.com/test-owner"}""")));

        List<ProjectSummary> projects = connector.listProjects();

        assertEquals(1, projects.size());
        assertEquals("test-owner", projects.get(0).id());
        assertEquals("Test Owner", projects.get(0).name());
    }

    @Test
    void shouldListRepositoriesFromOrgAndUserEndpoints() {
        // listAuthenticatedRepositories is called first - return empty so it falls through to org/user endpoints
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/user/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/orgs/test-owner/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"org-repo","default_branch":"main","owner":{"login":"test-owner"},"private":false},
                                 {"id":2,"name":"shared","default_branch":"main","owner":{"login":"test-owner"},"private":false}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/users/test-owner/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":3,"name":"user-repo","default_branch":"master","owner":{"login":"test-owner"},"private":false},
                                 {"id":2,"name":"shared","default_branch":"main","owner":{"login":"test-owner"},"private":false}]""")));

        List<RepositorySummary> repos = connector.listRepositories("test-owner");

        assertEquals(3, repos.size());
        assertEquals("main", repos.stream().filter(r -> r.name().equals("org-repo")).findFirst().orElseThrow().defaultBranch());
        assertEquals("master", repos.stream().filter(r -> r.name().equals("user-repo")).findFirst().orElseThrow().defaultBranch());
    }

    @Test
    void shouldFetchRepoChangesAsCommits() {
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"sha":"abc123","commit":{"author":{"name":"alice","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"alice","date":"2026-07-01T00:00:00Z"},
                                  "message":"feat: add widget"},"html_url":"https://github.com/test-owner/repo-one/commit/abc123"}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits/abc123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"abc123","commit":{"author":{"name":"alice","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"alice","date":"2026-07-01T00:00:00Z"},
                                  "message":"feat: add widget"},"files":[{"filename":"src/Widget.ts"}]}""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        // commitCountForVersion + default branch resolution both call these too.
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/compare/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"behind","ahead_by":1,"behind_by":0,
                                 "commits":[{"sha":"abc123","commit":{"author":{"name":"alice","date":"2026-07-01T00:00:00Z"},
                                   "committer":{"name":"alice","date":"2026-07-01T00:00:00Z"},
                                   "message":"feat: add widget"},"html_url":"https://github.com/test-owner/repo-one/commit/abc123"}]}""")));

        ReleaseData data = connector.fetchRepoChanges("test-owner", "repo-one", null, null, null);

        assertEquals("test-owner", data.getRelease().getProject());
        assertFalse(data.getItems().isEmpty());
        assertEquals("feat: add widget", data.getItems().get(0).getTitle());
        assertTrue(data.getItems().get(0).getFilePaths().contains("src/Widget.ts"));
    }

    @Test
    void shouldResolveRunCommitsAndFilesFromMergeCommitParents() {
        // Regresses a real bug: getParentSha read commit.commit().parents(), but GitHub puts the
        // parents array at the TOP level of a commit object. That made baseSha always null, so
        // every workflow run degraded to a single head commit with no diff files.
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/actions/runs/987654"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":987654,"run_number":8,"status":"completed","conclusion":"failure",
                                 "created_at":"2026-08-09T03:42:03Z","updated_at":"2026-08-09T03:43:27Z",
                                 "run_started_at":"2026-08-09T03:42:03Z","completed_at":"2026-08-09T03:43:27Z",
                                 "head_branch":"main","head_sha":"mergesha","event":"schedule",
                                 "pull_requests":[],"workflow_id":1,"name":"E2E Full Suite",
                                 "head_commit":{"id":"mergesha","message":"Merge pull request #31",
                                   "author":{"name":"bot","email":"bot@x"}}}""")));
        // getParentSha fetches the head commit — parents live at the TOP level in GitHub's payload.
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits/mergesha"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"mergesha",
                                 "commit":{"author":{"name":"bot","date":"2026-08-09T03:40:00Z"},
                                   "committer":{"name":"bot","date":"2026-08-09T03:40:00Z"},
                                   "message":"Merge pull request #31"},
                                 "parents":[{"sha":"base111"},
                                            {"sha":"feature222"}],
                                 "files":[],"tree":{"sha":"treesha"}}""")));
        // compare base...head returns the PR's commits + the diff files.
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/compare/base111...mergesha"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"ahead","ahead_by":2,"behind_by":0,
                                 "files":[{"filename":"src/app.ts","status":"modified","additions":10,"deletions":2}],
                                 "commits":[
                                   {"sha":"feature222","commit":{"author":{"name":"alice","date":"2026-08-09T03:00:00Z"},
                                     "committer":{"name":"alice","date":"2026-08-09T03:00:00Z"},
                                     "message":"feat: add widget"},"html_url":"u1","files":[{"filename":"src/app.ts"}]},
                                   {"sha":"mergesha","commit":{"author":{"name":"bot","date":"2026-08-09T03:40:00Z"},
                                     "committer":{"name":"bot","date":"2026-08-09T03:40:00Z"},
                                     "message":"Merge pull request #31"},"html_url":"u2","files":[]}]}""")));
        // fetchCommitFilePaths for the two commits.
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits/feature222"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"feature222","commit":{"author":{"name":"alice","date":"2026-08-09T03:00:00Z"},
                                  "committer":{"name":"alice","date":"2026-08-09T03:00:00Z"},
                                  "message":"feat: add widget"},"files":[{"filename":"src/app.ts"}],"tree":{"sha":"treesha"}}""")));

        RunChangeContext ctx = connector.fetchRunContext("test-owner", "repo-one", 987654);

        assertEquals("8", ctx.getRun().getRunNumber());
        assertEquals(2, ctx.getCommits().size());
        assertEquals(1, ctx.getFiles().size());
        assertEquals("src/app.ts", ctx.getFiles().get(0).getPath());
    }

    @Test
    void shouldSwapMergeCommitForPullRequest() {
        // The merged PR's merge_commit_sha matches a commit in the compare payload, so the raw
        // merge commit becomes the PR's richer ChangeItem.
        // Stub repository listing for defaultBranch -> listRepositories
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/user/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/orgs/test-owner/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"repo-one","default_branch":"main","owner":{"login":"test-owner"},"private":false}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/users/test-owner/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"repo-one","default_branch":"main","owner":{"login":"test-owner"},"private":false}]""")));
        
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"sha":"aaaa","commit":{"author":{"name":"bot","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"bot","date":"2026-07-01T00:00:00Z"},
                                  "message":"Merge pull request #42 from feature/x"},"html_url":"u1"}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits/aaaa"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"aaaa","commit":{"author":{"name":"bot","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"bot","date":"2026-07-01T00:00:00Z"},
                                  "message":"Merge pull request #42 from feature/x"},"files":[],"tree":{"sha":"treesha"}}""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        // Stub with query params for listPullRequests
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/pulls"))
                .withQueryParam("state", equalTo("closed"))
                .withQueryParam("base", equalTo("main"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"number":42,"title":"Add the widget","body":"Does it",
                                  "state":"closed","merged_at":"2026-07-01T00:00:00Z","created_at":"2026-06-30T00:00:00Z",
                                  "html_url":"https://github.com/test-owner/repo-one/pull/42",
                                  "merge_commit_sha":"aaaa","user":{"login":"alice"},
                                  "base":{"ref":"main"}}]""")));

        ReleaseData data = connector.fetchRepoChanges("test-owner", "repo-one", null, null, null);

        assertFalse(data.getItems().isEmpty());
        ChangeItem item = data.getItems().get(0);
        assertEquals(ChangeItem.ItemType.PULL_REQUEST, item.getType());
        assertEquals("Add the widget", item.getTitle());
        assertEquals("alice", item.getAuthor());
    }

    @Test
    void shouldPushChangelogAsBranchAndPullRequest() {
        // Stub owner resolution (getOrg -> 404, then getUser)
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/orgs/test-owner"))
                .willReturn(aResponse().withStatus(404)));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/users/test-owner"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"login":"test-owner","name":"Test Owner","html_url":"https://github.com/test-owner"}""")));
        // Stub repository listing for listRepositories (used by fetchChangelogFile -> defaultBranch)
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/user/repos"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/orgs/test-owner/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"repo-one","default_branch":"main","owner":{"login":"test-owner"},"private":false}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/users/test-owner/repos"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"repo-one","default_branch":"main","owner":{"login":"test-owner"},"private":false}]""")));

        // Existing CHANGELOG.md at the branch tip.
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/contents/CHANGELOG.md"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"content":"IyBSZWxlYXNlcyBcblxuIyMgdjEuMC4wIC0gMjAyNi0wNy0wMVxuXG4tIGZpcnN0IFxu","encoding":"base64"}""")));
        // Branch head commit (listCommits) + base tree (getCommit).
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"sha":"base123","commit":{"author":{"name":"a","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"a","date":"2026-07-01T00:00:00Z"},"message":"base"},"html_url":"u"}]""")));
        WireMockGitHubOrgResource.server().stubFor(get(urlPathEqualTo("/repos/test-owner/repo-one/commits/base123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"base123","commit":{"author":{"name":"a","date":"2026-07-01T00:00:00Z"},
                                  "committer":{"name":"a","date":"2026-07-01T00:00:00Z"},"message":"base",
                                  "tree":{"sha":"treeparent"}},"tree":{"sha":"treeparent"},"html_url":"u"}""")));
        // git data API write chain.
        WireMockGitHubOrgResource.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/repos/test-owner/repo-one/git/refs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/changelog/1.1.0-base123\",\"sha\":\"base123\"}")));
        WireMockGitHubOrgResource.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/repos/test-owner/repo-one/git/blobs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\":\"blobsha\"}")));
        WireMockGitHubOrgResource.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/repos/test-owner/repo-one/git/trees"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\":\"treesha\"}")));
        WireMockGitHubOrgResource.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/repos/test-owner/repo-one/git/commits"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\":\"commitsha\"}")));
        WireMockGitHubOrgResource.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.put(
                urlPathEqualTo("/repos/test-owner/repo-one/git/refs/changelog%2F1.1.0-base123"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/changelog/1.1.0-base123\",\"sha\":\"commitsha\"}")));
        WireMockGitHubOrgResource.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/repos/test-owner/repo-one/pulls"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"number\":7,\"html_url\":\"https://github.com/test-owner/repo-one/pull/7\"}")));

        String prUrl = connector.pushChangelogEdit("test-owner", "repo-one", "main", "1.1.0",
                "- fixed the thing\n- added a feature");

        assertEquals("https://github.com/test-owner/repo-one/pull/7", prUrl);
    }
}
