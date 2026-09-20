package com.hubsabai.changelog.storage;

import com.hubsabai.changelog.ai.AiException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** The version-free draft save/restore flow for a recorded pipeline run — what makes regen/edit
 * on a run-keyed entry survive a refresh and stay browsable/restorable like a version's revisions. */
@QuarkusTest
class RecordedRunServiceDraftTest {

    @Inject
    RecordedRunService service;

    @Inject
    EntityManager entityManager;

    static final String PROJ = "testproj";
    static final String REPO = "testrepo";

    @BeforeEach
    @Transactional
    void cleanUp() {
        TestDatabaseGuard.assertNotProductionDatabase(entityManager);
        entityManager.createNativeQuery("DELETE FROM recorded_run_draft").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM recorded_pipeline_run").executeUpdate();
        entityManager.createNativeQuery("ALTER TABLE recorded_pipeline_run ALTER COLUMN id RESTART WITH 1").executeUpdate();
    }

    private void seedRun(long buildId) {
        RecordedPipelineRun run = new RecordedPipelineRun();
        run.provider = "github";
        run.project = PROJ;
        run.repo = REPO;
        run.buildId = buildId;
        run.createdAt = OffsetDateTime.now();
        run.updatedAt = OffsetDateTime.now();
        run.persist();
    }

    @Test
    @Transactional
    void saveAiDraftAccumulatesHistoryWithCurrentLast() {
        seedRun(1001L);
        service.saveAiDraft("github", PROJ, REPO, 1001L, "developer", "ai", null, "m1", "first", 10, 100);
        service.saveAiDraft("github", PROJ, REPO, 1001L, "developer", "edit", "alice", null, "second", null, null);

        var revisions = service.listDraftRevisions("github", PROJ, REPO, 1001L);
        assertEquals(2, revisions.size());
        assertEquals("first", revisions.get(0).text());
        assertEquals("ai", revisions.get(0).source());
        assertEquals("second", revisions.get(1).text());
        assertEquals("edit", revisions.get(1).source());
        assertEquals("alice", revisions.get(1).editedBy());
        // Sequences are strictly ascending and unique.
        assertTrue(revisions.get(0).sequence() < revisions.get(1).sequence());
    }

    @Test
    @Transactional
    void restoreBumpsCurrentIntoHistoryAndMakesTargetLive() {
        seedRun(1002L);
        service.saveAiDraft("github", PROJ, REPO, 1002L, "developer", "ai", null, "m1", "first", 10, 100);
        service.saveAiDraft("github", PROJ, REPO, 1002L, "developer", "edit", "alice", null, "second", null, null);

        String restored = service.restoreAiDraftRevision("github", PROJ, REPO, 1002L, 1L);
        assertEquals("first", restored);

        var after = service.listDraftRevisions("github", PROJ, REPO, 1002L);
        assertEquals(3, after.size());
        var live = after.get(after.size() - 1);
        assertEquals("first", live.text());
        assertEquals("restore", live.source());
        // "second" now lives in history rather than being lost.
        assertTrue(after.stream().anyMatch(r -> "second".equals(r.text())));
    }

    @Test
    @Transactional
    void saveAiDraftWithIdenticalTextDoesNotAddHistory() {
        seedRun(1003L);
        service.saveAiDraft("github", PROJ, REPO, 1003L, "developer", "ai", null, "m1", "same", 10, 100);
        service.saveAiDraft("github", PROJ, REPO, 1003L, "developer", "edit", "alice", null, "same", null, null);

        assertEquals(1, service.listDraftRevisions("github", PROJ, REPO, 1003L).size());
    }

    @Test
    @Transactional
    void restoreUnknownRevisionThrows() {
        seedRun(1004L);
        service.saveAiDraft("github", PROJ, REPO, 1004L, "developer", "ai", null, "m1", "only", 10, 100);

        assertThrows(AiException.class,
                () -> service.restoreAiDraftRevision("github", PROJ, REPO, 1004L, 99L));
    }
}
