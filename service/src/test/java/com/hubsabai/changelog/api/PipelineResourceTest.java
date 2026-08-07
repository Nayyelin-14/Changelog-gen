package com.hubsabai.changelog.api;

import com.hubsabai.changelog.connector.azuredevops.WireMockAzureDevOpsResource;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PipelineTestResource.class)
@QuarkusTestResource(WireMockAzureDevOpsResource.class)
class PipelineResourceTest {

    // Matches %test.pipeline.api-keys in application.properties — a test-only fixture value,
    // never a real credential.
    private static final String TEST_KEY = "test-only-fixture-key";

    @Inject
    ChangelogCacheService cacheService;

    @BeforeEach
    void resetWireMock() {
        WireMockAzureDevOpsResource.server().resetAll();
    }

    @Test
    void rejectsAMissingAuthorizationHeader() {
        given()
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(401);
    }

    @Test
    void rejectsAnUnknownKey() {
        given()
                .header("Authorization", "Bearer not-a-real-key")
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(401);
    }

    @Test
    void softNoOpsWhenVersionIsMissingEvenWithAValidKey() {
        // Without a version there's nothing to key raw_release/generated_changelog on, but that's
        // never a reason to fail the calling pipeline's build — same soft no-op as an empty
        // commit/PR list, not a 400.
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("prCount", org.hamcrest.Matchers.is(0))
                .body("changelog", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void softNoOpsWhenVersionIsBlank() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"version\":\"  \",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("prCount", org.hamcrest.Matchers.is(0))
                .body("changelog", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void rejectsAMissingProject() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"repo\":\"repo\",\"version\":\"1.0.0\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsAMissingRepo() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"version\":\"1.0.0\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(400);
    }

    @Test
    void softNoOpsWhenRawCommitLogYieldsNoItems() {
        // Ingest is best-effort: the calling pipeline's build must never fail just because this
        // lookup came up empty (see PipelineResource's handling of an empty ReleaseData).
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"stage\":\"release\",\"rawCommitLog\":\"\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("prCount", org.hamcrest.Matchers.is(0))
                .body("changelog", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void defaultsToReleaseStageWhenStageIsMissing() {
        // A caller that omits stage must never have its build fail over it — defaults to
        // "release" instead of rejecting the call.
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj-nostage\",\"repo\":\"repo-nostage\",\"version\":\"1.0.0\","
                        + "\"rawCommitLog\":\"=== Merged PR 7171: no stage sent\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("stage", org.hamcrest.Matchers.equalTo("release"));
    }

    @Test
    void defaultsToReleaseStageWhenStageIsInvalid() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj-badstage\",\"repo\":\"repo-badstage\",\"version\":\"1.0.0\",\"stage\":\"final\","
                        + "\"rawCommitLog\":\"=== Merged PR 7272: bad stage sent\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("stage", org.hamcrest.Matchers.equalTo("release"));
    }

    @Test
    void ingestsRawCommitLogAndIndexesItsPr() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj-ingest\",\"repo\":\"repo-ingest\",\"version\":\"2.0.0\",\"stage\":\"prerelease\","
                        + "\"rawCommitLog\":\"=== Merged PR 4242: fix things\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("project", org.hamcrest.Matchers.equalTo("proj-ingest"))
                .body("repo", org.hamcrest.Matchers.equalTo("repo-ingest"))
                .body("version", org.hamcrest.Matchers.equalTo("2.0.0"))
                .body("stage", org.hamcrest.Matchers.equalTo("prerelease"))
                .body("prCount", org.hamcrest.Matchers.equalTo(1));

        // The PR should now resolve via the read-only lookup endpoint too — not just accepted.
        given()
                .when().get("/api/projects/proj-ingest/repos/repo-ingest/pull-requests/4242/changelog-location")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("prerelease"))
                .body("version", org.hamcrest.Matchers.equalTo("2.0.0"));
    }

    @Test
    void bundledIngestionReturnsAndStoresAPlainChangelog() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"proj-plain\",\"repo\":\"repo-plain\",\"version\":\"4.0.0\",\"stage\":\"release\","
                        + "\"rawCommitLog\":\"=== Merged PR 6161: add the widget\\nsrc/Widget.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("changelog", containsString("add the widget"))
                .body("changelog", containsString("PR #6161"));

        String text = cacheService.getCurrentText("proj-plain", "repo-plain", "4.0.0", "developer").orElse(null);
        assertTrue(text != null, "the bundled ingestion should also save a raw developer draft");
        assertTrue(text.contains("add the widget"));

        String source = cacheService.getCurrentEntry("proj-plain", "repo-plain", "4.0.0", "developer")
                .map(e -> e.currentSource).orElse(null);
        assertEquals("raw", source, "stored source must be non-AI");
    }

    @Test
    void neverRegressesAReleasedPrBackToPrerelease() {
        String body = "{\"project\":\"proj-upgrade\",\"repo\":\"repo-upgrade\",\"version\":\"3.0.0\",\"stage\":\"%s\","
                + "\"rawCommitLog\":\"=== Merged PR 5150: ship it\\nsrc/Bar.java\"}";

        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body(String.format(body, "release"))
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200);

        // A later prerelease report for the same PR must not undo the release.
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body(String.format(body, "prerelease"))
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200);

        given()
                .when().get("/api/projects/proj-upgrade/repos/repo-upgrade/pull-requests/5150/changelog-location")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("released"))
                .body("version", org.hamcrest.Matchers.equalTo("3.0.0"));
    }

    @Test
    void rejectsAnInvalidBearerTokenFormat() {
        given()
                .header("Authorization", "Basic not-bearer")
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(401);
    }

    @Test
    void rejectsAnEmptyBearerToken() {
        given()
                .header("Authorization", "Bearer ")
                .contentType("application/json")
                .body("{\"project\":\"proj\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"rawCommitLog\":\"=== test\\nsrc/Foo.java\"}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(401);
    }

    @Test
    void changelogLocationReportsNotFoundForAnUnknownPr() {
        given()
                .when().get("/api/projects/some-proj/repos/some-repo/pull-requests/999999/changelog-location")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("not_found"))
                .body("version", org.hamcrest.Matchers.nullValue())
                .body("stage", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void rawInitRequiresAPullRequestId() {
        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"raw-proj\",\"repo\":\"raw-repo\",\"version\":\"9.9.0\",\"raw\":true}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(400)
                .body("error", containsString("pullRequestId"));
    }

    @Test
    void rawInitFetchesPrDataAndStoresAPlainDeveloperChangelog() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/4321"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"pullRequestId":4321,"title":"Fix the thing","description":"Does the fix.",
                         "createdBy":{"displayName":"Jane Dev","uniqueName":"jane@x.com"},
                         "sourceRefName":"refs/heads/fix/thing","targetRefName":"refs/heads/dev",
                         "url":"https://dev.azure.com/test-org/raw-proj/_apis/git/repositories/raw-repo/pullRequests/4321"}
                        """)));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo(
                "/test-org/raw-proj/_apis/git/repositories/raw-repo/pullrequests/4321/commits"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[{"commitId":"abc123",
                         "author":{"name":"Jane Dev","email":"jane@x.com","date":"2026-01-01T00:00:00Z"},
                         "comment":"fix: the actual bug","url":"https://example.test/commit/abc123"}]}
                        """)));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo(
                "/test-org/raw-proj/_apis/git/repositories/raw-repo/pullrequests/4321/workitems"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[{"id":555,"url":"https://example.test/wit/555"}]}
                        """)));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/raw-proj/_apis/wit/workitems"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"count":1,"value":[{"id":555,"fields":{"System.Title":"Ticket title","System.WorkItemType":"Bug"}}]}
                        """)));

        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"raw-proj\",\"repo\":\"raw-repo\",\"version\":\"9.9.1\",\"raw\":true,\"pullRequestId\":4321}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200)
                .body("project", org.hamcrest.Matchers.equalTo("raw-proj"))
                .body("prCount", org.hamcrest.Matchers.equalTo(1));

        String text = cacheService.getCurrentText("raw-proj", "raw-repo", "9.9.1", "developer").orElse(null);
        assertTrue(text != null, "raw draft should have been saved for the developer audience");
        assertTrue(text.contains("Fix the thing"), "should include the PR title");
        assertTrue(text.contains("PR #4321"), "should reference the PR number");
        assertTrue(text.contains("fix: the actual bug"), "should include the commit message");
        assertTrue(text.contains("Bug #555") && text.contains("Ticket title"), "should include the linked work item");

        String source = cacheService.getCurrentEntry("raw-proj", "raw-repo", "9.9.1", "developer")
                .map(e -> e.currentSource).orElse(null);
        assertEquals("raw", source, "stored source must be non-AI");
    }

    @Test
    void rawInitNeverOverwritesAnAiGeneratedEntryForTheSameVersion() {
        cacheService.put("raw-proj-ai", "raw-repo-ai", "9.9.2", "developer", "test-model", "Real AI-written text", "hash123");

        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/4322"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"pullRequestId\":4322,\"title\":\"Another PR\",\"description\":\"desc\"}")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo(
                "/test-org/raw-proj-ai/_apis/git/repositories/raw-repo-ai/pullrequests/4322/commits"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"count\":0,\"value\":[]}")));
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo(
                "/test-org/raw-proj-ai/_apis/git/repositories/raw-repo-ai/pullrequests/4322/workitems"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"count\":0,\"value\":[]}")));

        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"raw-proj-ai\",\"repo\":\"raw-repo-ai\",\"version\":\"9.9.2\",\"raw\":true,\"pullRequestId\":4322}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(200);

        assertEquals("Real AI-written text",
                cacheService.getCurrentText("raw-proj-ai", "raw-repo-ai", "9.9.2", "developer").orElse(null),
                "a re-run of raw init must never clobber an existing AI-generated entry");
    }

    @Test
    void rawInitReturnsAClear4xxWhenThePrIsNotFound() {
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo("/test-org/_apis/git/pullrequests/999888"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"not found\"}")));

        given()
                .header("Authorization", "Bearer " + TEST_KEY)
                .contentType("application/json")
                .body("{\"project\":\"raw-proj\",\"repo\":\"raw-repo\",\"version\":\"9.9.3\",\"raw\":true,\"pullRequestId\":999888}")
                .when().post("/api/pipeline/generate")
                .then()
                .statusCode(400)
                .body("error", containsString("999888"));
    }
}
