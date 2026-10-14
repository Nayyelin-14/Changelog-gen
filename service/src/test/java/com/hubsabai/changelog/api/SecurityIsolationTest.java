package com.hubsabai.changelog.api;

import com.hubsabai.changelog.auth.TestSessionFixture;
import com.hubsabai.changelog.storage.ChangelogVersion;
import com.hubsabai.changelog.storage.RecordedPipelineRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The security model end to end: default-deny, per-user ownership, and the two distinct "stop using
 * GitHub" actions. These are the assertions the whole change exists to make true.
 */
@QuarkusTest
class SecurityIsolationTest {

    @Inject
    TestSessionFixture sessions;

    @Inject
    UserTransaction utx;

    /** Read from config rather than hard-coded: PipelineTestResource overrides it for its own class. */
    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pipeline.api-keys")
    String pipelineKey;

    private TestSessionFixture.SignedIn alice;
    private TestSessionFixture.SignedIn bob;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        alice = sessions.signInWithUser();
        bob = sessions.signInWithUser();
    }

    private void reset() throws Exception {
        utx.begin();
        try {
            com.hubsabai.changelog.storage.ChangelogRevision.deleteAll();
            com.hubsabai.changelog.storage.ChangelogVersion.deleteAll();
            com.hubsabai.changelog.storage.GeneratedChangelog.deleteAll();
            com.hubsabai.changelog.storage.RawRelease.deleteAll();
            com.hubsabai.changelog.storage.ReleasePr.deleteAll();
            com.hubsabai.changelog.storage.RecordedRunDraft.deleteAll();
            com.hubsabai.changelog.storage.RecordedPipelineRun.deleteAll();
            com.hubsabai.changelog.auth.AuthSession.deleteAll();
            com.hubsabai.changelog.auth.GithubUser.deleteAll();
            utx.commit();
        } catch (Exception e) {
            utx.rollback();
            throw e;
        }
    }

    // --- default deny ---

    @Test
    void anonymousCallersAreRejectedAcrossTheApi() {
        given().when().get("/api/ai/models").then().statusCode(401)
                .body("error", equalTo("not_authenticated"));
        given().queryParam("version", "1.0.0").queryParam("audience", "developer")
                .when().get("/api/projects/p/repos/r/changelog-meta")
                .then().statusCode(401);
        given().queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/pipeline/runs")
                .then().statusCode(401);
    }

    @Test
    void theSignInFlowStaysReachableWithoutASession() {
        // The OAuth dance has to start from a logged-out browser — never behind the 401.
        given().redirects().follow(false).when().get("/api/auth/github/authorize")
                .then().statusCode(302);
        given().when().get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void pipelineKeyHoldersAreServiceCallersNotUsers() {
        // A valid pipeline key is accepted without a session — that is the CI path.
        given().header("Authorization", "Bearer " + pipelineKey.split(",")[0].trim())
                .contentType("application/json")
                .body("{\"project\":\"p\",\"repo\":\"r\",\"version\":\"9.9.9\",\"rawCommitLog\":\"=== x\\nsrc/A.java\"}")
                .when().post("/api/pipeline/generate")
                .then().statusCode(200);

        // And the AI benchmark surface needs that key too — it is an AI-cost amplifier.
        given().contentType("application/json").body("{\"project\":\"p\",\"repo\":\"r\",\"version\":\"9.9.9\"}")
                .when().post("/api/ai/models/bench")
                .then().statusCode(401);
    }

    // --- ownership ---

    @Test
    void oneUsersRecordedRunsAreInvisibleToAnother() {
        // Same repository, same build, two users — each must see only their own copy. This endpoint
        // is pure DB, so the assertion is about ownership rather than about provider connectivity.
        saveRecordedRun(alice, 11L, "alice's run");
        saveRecordedRun(bob, 11L, "bob's run");

        given().cookie("cc_session", alice.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/pipeline/runs")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].buildId", equalTo(11));

        given().cookie("cc_session", bob.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/pipeline/runs")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].buildId", equalTo(11));
    }

    @Test
    void mutatingSomeoneElsesVersionIsForbiddenNotSilentlyMissing() {
        saveVersion(alice, "2.0.0");

        // Bob is authenticated, so this is a 403 (not a 401) — he just isn't the owner.
        given().cookie("cc_session", bob.cookie())
                .queryParam("version", "2.0.0").queryParam("audience", "developer")
                .contentType("application/json")
                .body("{\"text\":\"overwrite\"}")
                .when().put("/api/projects/p/repos/r/changelog-restore")
                .then().statusCode(403)
                .body("error", equalTo("forbidden"));
    }

    @Test
    void serviceOwnedRowsCarryANullOwner() {
        // A row the CI wrote has no owner: it is the service's, not any one user's. Reads fall back
        // to it only after checking the caller's own GitHub account can reach the repository.
        saveRecordedRun(null, 33L, "written by CI");

        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId("azure", "p", "r", 33L, null);
        assertNotNull(run, "the service-owned row must exist");
        assertNull(run.ownerUserId);
        RecordedPipelineRun visibleToAlice = RecordedPipelineRun.findByBuildId("azure", "p", "r", 33L, alice.userId());
        assertNotNull(visibleToAlice, "service-owned rows are readable by any owner by design");
        assertNull(visibleToAlice.ownerUserId);
    }

    // --- disconnect vs delete ---

    @Test
    void disconnectKeepsTheAccountAndItsDataButErasesTheCredential() {
        saveRecordedRun(alice, 44L, "survives a disconnect");

        given().cookie("cc_session", alice.cookie()).when().post("/api/auth/github/disconnect")
                .then().statusCode(204);

        given().cookie("cc_session", alice.cookie()).when().get("/api/auth/me")
                .then().statusCode(200)
                .body("githubConnected", equalTo(false))
                .body("id", equalTo(alice.userId().intValue()));

        given().cookie("cc_session", alice.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/pipeline/runs")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].buildId", equalTo(44));
    }

    @Test
    void disconnectingGitHubAlsoStopsTheGithubRoutesInsteadOfFetchingAnonymously() {
        given().cookie("cc_session", alice.cookie()).when().post("/api/auth/github/disconnect")
                .then().statusCode(204);

        // GitHub still serves PUBLIC repositories to an anonymous caller, so without this the
        // dashboard would keep working behind a "disconnected" warning that isn't true.
        given().cookie("cc_session", alice.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/github/projects/p/repos/r/builds")
                .then().statusCode(403)
                .body("error", equalTo("github_not_connected"));

        // A user who is still connected is not caught by the same gate...
        given().cookie("cc_session", bob.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/github/projects/p/repos/r/builds")
                .then().body(not(containsString("github_not_connected")));

        // ...and the same account keeps working everywhere that doesn't need GitHub (Azure rides
        // the shared service PAT, which a personal disconnect doesn't touch).
        given().cookie("cc_session", alice.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/pipeline/runs")
                .then().statusCode(200);
    }

    @Test
    void deletingTheAccountRemovesItsOwnedRowsAndLeavesTheOtherUserAlone() {
        saveRecordedRun(alice, 55L, "alice's, deleted with her");
        saveRecordedRun(bob, 55L, "bob's, untouched");

        given().cookie("cc_session", alice.cookie()).when().delete("/api/auth/github/account")
                .then().statusCode(204);

        assertNull(RecordedPipelineRun.findByBuildId("azure", "p", "r", 55L, alice.userId()),
                "the deleted account's rows go with it");
        assertNotNull(RecordedPipelineRun.findByBuildId("azure", "p", "r", 55L, bob.userId()),
                "another user's rows must survive");

        given().cookie("cc_session", bob.cookie())
                .queryParam("project", "p").queryParam("repo", "r")
                .when().get("/api/pipeline/runs")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].buildId", equalTo(55));
    }

    // --- helpers ---

    /** Writes a changelog_version row owned by {@code owner} (null = the service/CI). */
    private void saveVersion(TestSessionFixture.SignedIn owner, String version) {
        try {
            utx.begin();
            ChangelogVersion row = new ChangelogVersion();
            row.ownerUserId = owner == null ? null : owner.userId();
            row.provider = "azure";
            row.project = "p";
            row.repo = "r";
            row.version = version;
            row.createdAt = java.time.OffsetDateTime.now();
            row.persist();
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception ignored) {
                // already rolled back
            }
            throw new IllegalStateException(e);
        }
    }

    /** Writes a recorded pipeline run owned by {@code owner} (null = the service/CI). */
    private void saveRecordedRun(TestSessionFixture.SignedIn owner, long buildId, String marker) {
        try {
            utx.begin();
            RecordedPipelineRun run = new RecordedPipelineRun();
            run.ownerUserId = owner == null ? null : owner.userId();
            run.provider = "azure";
            run.project = "p";
            run.repo = "r";
            run.buildId = buildId;
            run.version = "1.0.0";
            run.branch = "dev";
            run.runMetadata = "{\"run\":{\"note\":\"" + marker + "\"}}";
            run.changeItems = "[]";
            run.createdAt = java.time.OffsetDateTime.now();
            run.updatedAt = java.time.OffsetDateTime.now();
            run.persist();
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception ignored) {
                // already rolled back
            }
            throw new IllegalStateException(e);
        }
    }
}
