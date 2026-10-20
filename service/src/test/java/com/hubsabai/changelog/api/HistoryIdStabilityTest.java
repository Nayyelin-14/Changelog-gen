package com.hubsabai.changelog.api;

import com.hubsabai.changelog.auth.TestSessionFixture;
import com.hubsabai.changelog.connector.github.ChangelogMarkdown.ChangelogEntry;
import com.hubsabai.changelog.connector.github.ChangelogMarkdown.ChangelogFile;
import com.hubsabai.changelog.connector.github.GitHubOrgConnector;
import com.hubsabai.changelog.storage.ChangelogRevision;
import com.hubsabai.changelog.storage.ChangelogVersion;
import com.hubsabai.changelog.storage.GeneratedChangelog;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The version history list is fetched twice for every click: once for the dashboard table, once
 * for the detail view that opens when a row is clicked — and the detail view can only show the row
 * if the id it navigated with still exists in the response it just fetched.
 *
 * <p>Those two calls used to be able to answer from two different sources with two different id
 * schemes: the CHANGELOG.md parse built {@code repo-<index>-<version>-<date>} ids, while DB rows
 * answered {@code db-<index>-<version>}. The dashboard's link then pointed at an id that no longer
 * existed — the row rendered as "That version couldn't be found." Both sources now share
 * {@link HistoryEntry#versionId(String)}.
 */
@QuarkusTest
class HistoryIdStabilityTest {

    private static final String PROJECT = "history-ids-project";
    private static final String REPO = "history-ids-repo";

    @Inject
    TestSessionFixture sessions;

    @Inject
    UserTransaction utx;

    @InjectMock
    GitHubOrgConnector github;

    private String session;

    @BeforeEach
    void setUp() throws Exception {
        resetHistoryTables();
        session = sessions.signIn();
        // The markdown source, stubbed so the test never reaches api.github.com: two versions,
        // newest first once the endpoint sorts them.
        when(github.defaultBranch(anyString(), anyString())).thenReturn("main");
        when(github.fetchChangelogFileCached(any(), any(), any()))
                .thenReturn(new ChangelogFile("CHANGELOG.md", "# Changelog"));
        when(github.parseChangelogEntries(anyString()))
                .thenReturn(List.of(
                        new ChangelogEntry("1.0.9", "2026-07-23", "- fix: first"),
                        new ChangelogEntry("1.0.8", "2026-07-22", "- fix: second")));
        when(github.enrichChangelogEntries(any(), any(), any(), any())).thenReturn(Map.of());
        // Ungenerated-PR probing is a best-effort GitHub call the endpoint already treats as
        // optional — throwing here keeps the test's row list exactly the two versions above.
        when(github.fetchChangesSinceLastRelease(any(), any(), any()))
                .thenThrow(new IllegalStateException("no GitHub in this test"));
    }

    @Test
    void theSameVersionKeepsTheSameIdAcrossRepeatedHistoryLoads() {
        List<String> fromMarkdown = historyIds(20);
        assertEquals(
                List.of(HistoryEntry.versionId("1.0.9"), HistoryEntry.versionId("1.0.8")),
                fromMarkdown);

        // The first call also persists its parse; repeated loads must still agree on ids.
        assertEquals(fromMarkdown, historyIds(20));

        // …and the dashboard's shape (one unpaginated page) must agree with the detail view's.
        assertEquals(fromMarkdown, historyIds(9999));
    }

    @Test
    void explicitRefreshShowsAVersionAddedToTheRepoAfterTheDbWasPopulated() {
        // First load imports 1.0.9/1.0.8 and persists them, which used to make every later call
        // DB-only — hiding a release commit made outside the app.
        assertEquals(
                List.of(HistoryEntry.versionId("1.0.9"), HistoryEntry.versionId("1.0.8")),
                historyIds(20));

        when(github.fetchChangelogFileFresh(any(), any(), any()))
                .thenReturn(new ChangelogFile("CHANGELOG.md", "# Changelog"));
        when(github.parseChangelogEntries(anyString()))
                .thenReturn(List.of(
                        new ChangelogEntry("1.0.10", "2026-07-24", "- fix: newly released"),
                        new ChangelogEntry("1.0.9", "2026-07-23", "- fix: first"),
                        new ChangelogEntry("1.0.8", "2026-07-22", "- fix: second")));

        assertEquals(
                List.of(
                        HistoryEntry.versionId("1.0.10"),
                        HistoryEntry.versionId("1.0.9"),
                        HistoryEntry.versionId("1.0.8")),
                historyIds(20, true));
    }

    private List<String> historyIds(int limit) {
        return historyIds(limit, false);
    }

    private List<String> historyIds(int limit, boolean refresh) {
        return given().cookie("cc_session", session)
                .queryParam("branch", "main")
                .queryParam("page", 0)
                .queryParam("limit", limit)
                .queryParam("refresh", refresh)
                .when().get("/api/github/projects/{project}/repos/{repo}/history", PROJECT, REPO)
                .then().statusCode(200)
                .extract().jsonPath().getList("entries.id", String.class);
    }

    private void resetHistoryTables() throws Exception {
        utx.begin();
        try {
            ChangelogRevision.deleteAll();
            ChangelogVersion.deleteAll();
            GeneratedChangelog.deleteAll();
            utx.commit();
        } catch (Exception e) {
            utx.rollback();
            throw e;
        }
    }
}
