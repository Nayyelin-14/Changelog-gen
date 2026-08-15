package com.hubsabai.changelog.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;

@QuarkusTest
class AzureDevOpsResourceTest {

    @Test
    void shouldListFreeAiModelsForTheDropdown() {
        given()
                .when().get("/api/ai/models")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].id", org.hamcrest.Matchers.notNullValue())
                .body("[0].label", org.hamcrest.Matchers.notNullValue());
    }

    // version is required unconditionally unless manualText or a buildId identifies the data —
    // a version-free manual-text preview is a deliberate dashboard flow (version is filled in at
    // push time), but with no data source at all the request must fail before any AI/API call.

    @Test
    void generateRejectsAMissingVersion() {
        given()
                .queryParam("model", "some-model")
                .when().post("/api/projects/proj/repos/repo/generate")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version"));
    }

    @Test
    void generateStreamRejectsAMissingVersion() {
        given()
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
        given()
                .when().post("/api/projects/proj/repos/repo/generate-stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version"));
    }

    @Test
    void savingAnEditIsReflectedInChangelogMeta() {
        given()
                .contentType("application/json")
                .body("""
                        {"version":"9.9.9","audience":"qa","text":"hand-edited QA notes","editedBy":"qa"}
                        """)
                .when().put("/api/projects/metaproj/repos/metarepo/changelog-edit")
                .then()
                .statusCode(200);

        given()
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
        given()
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
        given()
                .queryParam("version", "1.0.0")
                .when().post("/api/projects/proj/repos/repo/generate")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("model must be selected"));
    }

    @Test
    void changelogTextRejectsInvalidAudience() {
        given()
                .queryParam("version", "1.0.0")
                .queryParam("audience", "invalid")
                .when().get("/api/projects/proj/repos/repo/changelog-text")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogTextRejectsMissingVersion() {
        given()
                .queryParam("audience", "developer")
                .when().get("/api/projects/proj/repos/repo/changelog-text")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void changelogMetaRejectsInvalidAudience() {
        given()
                .queryParam("version", "1.0.0")
                .queryParam("audience", "nobody")
                .when().get("/api/projects/proj/repos/repo/changelog-meta")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogMetaRejectsMissingVersion() {
        given()
                .queryParam("audience", "developer")
                .when().get("/api/projects/proj/repos/repo/changelog-meta")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void changelogPushRejectsNonDeveloperAudience() {
        given()
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
        given()
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
    void changelogPushRejectsMissingBranch() {
        given()
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
        given()
                .queryParam("audience", "developer")
                .when().get("/api/projects/proj/repos/repo/changelog-preview")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogPreviewRejectsInvalidAudience() {
        given()
                .queryParam("audience", "invalid")
                .when().get("/api/projects/proj/repos/repo/changelog-preview")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }

    @Test
    void changelogChatStreamRejectsInvalidAudience() {
        given()
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
        given()
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
        given()
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
        given()
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
        given()
                .contentType("application/json")
                .body("{\"version\":\"1.0.0\"}")
                .when().post("/api/projects/proj/repos/repo/generate-stream")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("model must be selected"));
    }

    @Test
    void saveEditRejectsBlankText() {
        given()
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
        given()
                .contentType("application/json")
                .body("""
                        {"audience":"developer","text":"some text","editedBy":"me"}
                        """)
                .when().put("/api/projects/proj/repos/repo/changelog-edit")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void saveEditRejectsInvalidAudience() {
        given()
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
        given()
                .queryParam("audience", "developer")
                .when().put("/api/projects/proj/repos/repo/changelog-restore")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("version is required"));
    }

    @Test
    void restoreRejectsInvalidAudience() {
        given()
                .queryParam("version", "1.0.0")
                .queryParam("audience", "invalid")
                .when().put("/api/projects/proj/repos/repo/changelog-restore")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("audience must be"));
    }
}
