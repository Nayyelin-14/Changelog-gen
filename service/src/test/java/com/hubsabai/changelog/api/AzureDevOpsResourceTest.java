package com.hubsabai.changelog.api;

import com.hubsabai.changelog.auth.TestSessionFixture;
import com.hubsabai.changelog.connector.azuredevops.WireMockAzureDevOpsResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@QuarkusTestResource(WireMockAzureDevOpsResource.class)
class AzureDevOpsResourceTest {

    /**
     * Default-deny is the point of the security work under test: every one of these endpoints now
     * requires a real session cookie, so each test signs in as a real user rather than stubbing
     * auth out. A test that drops back to bare {@code given()} is asserting the wrong thing.
     */
    @Inject
    TestSessionFixture sessions;

    private String session;

    @BeforeEach
    void signIn() {
        session = sessions.signIn();
    }

    private RequestSpecification authenticated() {
        assertNotNull(session, "every test must have a session — the API is default-deny");
        return given().cookie("cc_session", session);
    }

    @Test
    void shouldListFreeAiModelsForTheDropdown() {
        authenticated()
                .when().get("/api/ai/models")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].id", org.hamcrest.Matchers.notNullValue())
                .body("[0].label", org.hamcrest.Matchers.notNullValue());
    }

    // The model listing is provider-scoped: a provider that cannot list its own models must
    // surface an error instead of silently presenting NVIDIA's curated FREE_MODELS — that masked
    // a broken provider (e.g. a geo-blocked Gemini/Groq) as a healthy NVIDIA picker with HTTP 200.

    @Test
    void aiModelsForAnUnknownProviderIsAnErrorNotFreeModels() {
        // Previously returned HTTP 200 + the 5 NVIDIA FREE_MODELS for ANY provider — the masking bug.
        authenticated()
                .queryParam("provider", "does-not-exist")
                .when().get("/api/ai/models")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("Unknown AI provider"));
    }

    @Test
    void aiModelsForTheDefaultProviderStillFallsBackToCuratedFreeModels() {
        // NVIDIA (the default) is the only provider allowed the curated fallback when its live
        // list is unreachable — tests pin ai.base-url to a refused port, so this exercises the
        // legitimate same-provider fallback path.
        authenticated()
                .queryParam("provider", "nvidia")
                .when().get("/api/ai/models")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    // version is required unconditionally unless manualText or a buildId identifies the data —
    // a version-free manual-text preview is a deliberate dashboard flow (version is filled in at
    // push time), but with no data source at all the request must fail before any AI/API call.

    @Test
    void generateRejectsAMissingVersion() {
        authenticated()
                .queryParam("model", "some-model")
                .when().post("/api/projects/proj/repos/repo/generate")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version"));
    }

    @Test
    void generateStreamRejectsAMissingVersion() {
        authenticated()
                .contentType("application/json")
                .body("{\"model\":\"some-model\"}")
                .when().post("/api/projects/proj/repos/repo/generate-stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version"));
    }

    @Test
    void generateStreamRejectsAMissingBodyEntirely() {
        // No body at all deserializes to null, not an empty object — must still 400 cleanly with
        // the same validation message, not 500 on a null dereference.
        authenticated()
                .when().post("/api/projects/proj/repos/repo/generate-stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version"));
    }

    @Test
    void savingAnEditIsReflectedInChangelogMeta() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"9.9.9","audience":"qa","text":"hand-edited QA notes","editedBy":"qa"}
                        """)
                .when().put("/api/projects/metaproj/repos/metarepo/changelog-edit")
                .then()
                .statusCode(200);

        authenticated()
                .queryParam("version", "9.9.9")
                .queryParam("audience", "qa")
                .when().get("/api/projects/metaproj/repos/metarepo/changelog-meta")
                .then()
                .statusCode(200)
                .body("source", org.hamcrest.Matchers.equalTo("edit"))
                .body("editedBy", org.hamcrest.Matchers.equalTo("qa"))
                .body("at", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void changelogMetaIsAllNullWhenNothingSavedYet() {
        authenticated()
                .queryParam("version", "0.0.1-never-saved")
                .queryParam("audience", "business")
                .when().get("/api/projects/metaproj/repos/metarepo/changelog-meta")
                .then()
                .statusCode(200)
                .body("source", org.hamcrest.Matchers.nullValue())
                .body("editedBy", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void generateRejectsMissingModel() {
        authenticated()
                .queryParam("version", "1.0.0")
                .when().post("/api/projects/proj/repos/repo/generate")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("model must be selected"));
    }

    @Test
    void changelogTextRejectsInvalidAudience() {
        authenticated()
                .queryParam("version", "1.0.0")
                .queryParam("audience", "invalid")
                .when().get("/api/projects/proj/repos/repo/changelog-text")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogTextRejectsMissingVersion() {
        authenticated()
                .queryParam("audience", "developer")
                .when().get("/api/projects/proj/repos/repo/changelog-text")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version"));
    }

    @Test
    void changelogMetaRejectsInvalidAudience() {
        authenticated()
                .queryParam("version", "1.0.0")
                .queryParam("audience", "nobody")
                .when().get("/api/projects/proj/repos/repo/changelog-meta")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogMetaRejectsMissingVersion() {
        authenticated()
                .queryParam("audience", "developer")
                .when().get("/api/projects/proj/repos/repo/changelog-meta")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("pipeline build ID is required"));
    }

    @Test
    void changelogPushRejectsNonDeveloperAudience() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"1.0.0","branch":"main","audience":"qa"}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-push")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("developer"));
    }

    @Test
    void changelogPushRejectsMissingVersion() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"branch":"main","audience":"developer"}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-push")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void changelogPushReportsAlreadyUpToDateWithoutCreatingACommit() {
        String markdown = """
                # Changelog

                ## [1.1.0] - 2026-07-01

                - fixed the thing
                - added a feature
                """;
        WireMockAzureDevOpsResource.server().resetAll();
        WireMockAzureDevOpsResource.server().stubFor(get(urlPathEqualTo(
                        "/test-org/proj/_apis/git/repositories/repo-noop/items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"path\":\"/CHANGELOG.md\",\"content\":\""
                                + markdown.replace("\n", "\\n") + "\"}")));

        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"v1.1.0","branch":"main","audience":"developer",
                         "text":"- fixed the thing\\n- added a feature"}
                        """)
                .when().post("/api/projects/proj/repos/repo-noop/changelog-push")
                .then()
                .statusCode(200)
                .body("alreadyUpToDate", is(true))
                .body("commitUrl", nullValue());

        WireMockAzureDevOpsResource.server().verify(0, postRequestedFor(urlPathEqualTo(
                "/test-org/proj/_apis/git/repositories/repo-noop/pushes")));
    }

    @Test
    void changelogPushRejectsMissingBranch() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"1.0.0","audience":"developer"}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-push")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("branch is required"));
    }

    @Test
    void changelogPreviewRejectsDeveloperAudience() {
        authenticated()
                .queryParam("audience", "developer")
                .when().get("/api/projects/proj/repos/repo/changelog-preview")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogPreviewRejectsInvalidAudience() {
        authenticated()
                .queryParam("audience", "invalid")
                .when().get("/api/projects/proj/repos/repo/changelog-preview")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogChatStreamRejectsInvalidAudience() {
        authenticated()
                .contentType("application/json")
                .queryParam("audience", "developer")
                .queryParam("version", "1.0.0")
                .body("""
                        {"question":"how did login change?"}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-chat/stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogChatStreamRejectsMissingVersion() {
        authenticated()
                .contentType("application/json")
                .queryParam("audience", "qa")
                .body("""
                        {"question":"how did login change?"}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-chat/stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void changelogChatStreamRejectsBlankQuestion() {
        authenticated()
                .contentType("application/json")
                .queryParam("audience", "qa")
                .queryParam("version", "1.0.0")
                .body("""
                        {"question":"   "}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-chat/stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("question is required"));
    }

    @Test
    void changelogChatStreamRejectsWhenNoChangelogGeneratedYet() {
        authenticated()
                .contentType("application/json")
                .queryParam("audience", "qa")
                .queryParam("version", "999.999.999")
                .body("""
                        {"question":"how did login change?"}
                        """)
                .when().post("/api/projects/proj/repos/repo/changelog-chat/stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("No qa changelog"));
    }

    @Test
    void generateStreamRejectsMissingModel() {
        authenticated()
                .contentType("application/json")
                .body("{\"version\":\"1.0.0\"}")
                .when().post("/api/projects/proj/repos/repo/generate-stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("model must be selected"));
    }

    @Test
    void saveEditRejectsBlankText() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"1.0.0","audience":"developer","text":"  ","editedBy":"me"}
                        """)
                .when().put("/api/projects/proj/repos/repo/changelog-edit")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("blank"));
    }

    @Test
    void saveEditRejectsMissingVersion() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"audience":"developer","text":"some text","editedBy":"me"}
                        """)
                .when().put("/api/projects/proj/repos/repo/changelog-edit")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("pipeline build ID is required"));
    }

    @Test
    void saveEditRejectsInvalidAudience() {
        authenticated()
                .contentType("application/json")
                .body("""
                        {"version":"1.0.0","audience":"invalid","text":"some text","editedBy":"me"}
                        """)
                .when().put("/api/projects/proj/repos/repo/changelog-edit")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void restoreRejectsMissingVersion() {
        authenticated()
                .queryParam("audience", "developer")
                .when().put("/api/projects/proj/repos/repo/changelog-restore")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void restoreRejectsInvalidAudience() {
        authenticated()
                .queryParam("version", "1.0.0")
                .queryParam("audience", "invalid")
                .when().put("/api/projects/proj/repos/repo/changelog-restore")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }
}
