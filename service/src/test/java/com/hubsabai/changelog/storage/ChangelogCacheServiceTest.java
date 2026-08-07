package com.hubsabai.changelog.storage;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ChangelogCacheServiceTest {

    @Inject
    ChangelogCacheService cacheService;

    @Inject
    EntityManager entityManager;

    static final String PROJ = "testproj";
    static final String REPO = "testrepo";
    static final String VER = "1.0.0";
    static final String AUD = "developer";
    static final String HASH = "abc123hash";

    @BeforeEach
    @Transactional
    void cleanUp() {
        TestDatabaseGuard.assertNotProductionDatabase(entityManager);
        entityManager.createNativeQuery("TRUNCATE TABLE generated_changelog RESTART IDENTITY").executeUpdate();
    }

    @Test
    void getCurrentReturnsEmptyWhenNoEntryExists() {
        Optional<String> result = cacheService.getCurrent(PROJ, REPO, VER, AUD, HASH);
        assertTrue(result.isEmpty());
    }

    @Test
    void putThenGetCurrentReturnsTextWhenHashMatches() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "AI generated text", HASH);
        Optional<String> result = cacheService.getCurrent(PROJ, REPO, VER, AUD, HASH);
        assertTrue(result.isPresent());
        assertEquals("AI generated text", result.get());
    }

    @Test
    void getCurrentReturnsEmptyWhenHashDiffersAndSourceIsAi() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "AI generated text", HASH);
        Optional<String> result = cacheService.getCurrent(PROJ, REPO, VER, AUD, "different-hash");
        assertTrue(result.isEmpty());
    }

    @Test
    void getCurrentReturnsEditRegardlessOfHash() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "AI generated text", HASH);
        cacheService.saveEdit(PROJ, REPO, VER, AUD, "Human edited text", "qa-user");
        Optional<String> result = cacheService.getCurrent(PROJ, REPO, VER, AUD, "different-hash");
        assertTrue(result.isPresent());
        assertEquals("Human edited text", result.get());
    }

    @Test
    void saveEditOverwritesAndCanBeReadBack() {
        cacheService.saveEdit(PROJ, REPO, VER, AUD, "Edited content", "tester");
        Optional<String> result = cacheService.getEditedText(PROJ, REPO, VER, AUD);
        assertTrue(result.isPresent());
        assertEquals("Edited content", result.get());
    }

    @Test
    void getEditedTextReturnsEmptyWhenNoEditExists() {
        Optional<String> result = cacheService.getEditedText(PROJ, REPO, VER, AUD);
        assertTrue(result.isEmpty());
    }

    @Test
    void getEditedTextReturnsEmptyWhenOnlyAiGenerationExists() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "AI generated", HASH);
        Optional<String> result = cacheService.getEditedText(PROJ, REPO, VER, AUD);
        assertTrue(result.isEmpty());
    }

    @Test
    void getCurrentTextReturnsLatestRegardlessOfSource() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "AI text", HASH);
        Optional<String> result = cacheService.getCurrentText(PROJ, REPO, VER, AUD);
        assertTrue(result.isPresent());
        assertEquals("AI text", result.get());
    }

    @Test
    void getCurrentEntryReturnsFullEntry() {
        cacheService.put(PROJ, REPO, VER, AUD, "claude-3", "Claude output", HASH);
        Optional<GeneratedChangelog> entry = cacheService.getCurrentEntry(PROJ, REPO, VER, AUD);
        assertTrue(entry.isPresent());
        assertEquals("claude-3", entry.get().currentModelId);
        assertEquals("ai", entry.get().currentSource);
    }

    @Test
    void restorePreviousReturnsEmptyWhenNoPrevious() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "first gen", HASH);
        Optional<String> restored = cacheService.restorePrevious(PROJ, REPO, VER, AUD);
        assertTrue(restored.isEmpty());
    }

    @Test
    void restorePreviousSwapsCurrentAndPrevious() {
        cacheService.put(PROJ, REPO, VER, AUD, "gpt-4", "first gen", "hash1");
        cacheService.put(PROJ, REPO, VER, AUD, "claude-3", "second gen", "hash2");

        Optional<String> restored = cacheService.restorePrevious(PROJ, REPO, VER, AUD);
        assertTrue(restored.isPresent());
        assertEquals("first gen", restored.get());

        Optional<String> current = cacheService.getCurrentText(PROJ, REPO, VER, AUD);
        assertTrue(current.isPresent());
        assertEquals("first gen", current.get());
    }

    @Test
    void getCurrentTextsByVersionReturnsAllVersions() {
        cacheService.put(PROJ, REPO, "2.0.0", AUD, "gpt-4", "v2 text", "hash2");
        cacheService.put(PROJ, REPO, "3.0.0", AUD, "claude-3", "v3 text", "hash3");

        var map = cacheService.getCurrentTextsByVersion(PROJ, REPO, AUD);
        assertEquals(2, map.size());
        assertEquals("v2 text", map.get("2.0.0"));
        assertEquals("v3 text", map.get("3.0.0"));
    }
}
