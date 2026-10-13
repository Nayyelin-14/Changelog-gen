package com.hubsabai.changelog.generation;

import com.hubsabai.changelog.ai.AiModelOption;
import com.hubsabai.changelog.ai.AiMessage;
import com.hubsabai.changelog.ai.AiProvider;
import com.hubsabai.changelog.ai.AiProviderRegistry;
import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.ai.ReleaseNoteEntry;
import com.hubsabai.changelog.ai.ReleaseNotePreparer;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import com.hubsabai.changelog.storage.ChangelogService;
import com.hubsabai.changelog.storage.InputHash;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write gate itself. {@link ChangelogGenerationService#ensureAudience} only persists when
 * {@code commit} is true; the REST layer's job (covered by GenerateCommitFlagTest) is to hand
 * the right flag over. This test pins the other half: with commit=false nothing at all reaches
 * the database, and with commit=true both the cache row and the snapshot are written.
 *
 * <p>The service under test is built directly (it has no lifecycle callbacks) so its package-private
 * {@code registry} field can be swapped for a fake — no network, no model — while the cache and
 * changelog collaborators stay the real CDI beans backed by the test database.
 */
@QuarkusTest
class ChangelogGenerationServiceCommitGateTest {

    @Inject
    ChangelogCacheService cacheService;

    @Inject
    ChangelogService changelogService;

    private ChangelogGenerationService service;

    /** Stands in for a real AI backend — no network, no model, just a deterministic string.
     *  {@link #CALLS} counts invocations so cache hits are distinguishable from fresh calls. */
    static final class FakeProvider implements AiProvider {
        static final java.util.concurrent.atomic.AtomicInteger CALLS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public AiResult generateForAudience(List<ChangeItem> items, ReleaseData.ReleaseMeta release,
                String audience, String model, String systemPromptOverride) {
            CALLS.incrementAndGet();
            return new AiResult("generated " + audience, null);
        }

        @Override
        public AiResult generateForAudienceStrict(List<ChangeItem> items, ReleaseData.ReleaseMeta release,
                String audience, String model) {
            CALLS.incrementAndGet();
            return new AiResult("generated " + audience, null);
        }

        @Override
        public List<AiModelOption> listModels() {
            return List.of();
        }

        @Override
        public AiResult chatStream(List<AiMessage> messages, String modelOverride, StreamHandler handler) {
            return new AiResult("", null);
        }
    }

    static final class FakeRegistry extends AiProviderRegistry {
        @Override
        public AiProvider resolve(String providerId) {
            return new FakeProvider();
        }
    }

    /** A plain instance with the AI registry faked out and the real (CDI) storage collaborators
     *  wired in — no network, and the DB writes still go through the actual beans. */
    @BeforeEach
    void newService() {
        service = new ChangelogGenerationService();
        service.registry = new FakeRegistry();
        service.cacheService = cacheService;
        service.changelogService = changelogService;
        FakeProvider.CALLS.set(0);
    }

    // ---- fixture -----------------------------------------------------------------------

    private static String uniqueId() {
        return "commitgate-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ReleaseData data() {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setTitle("fix: rotate the signing key on every deploy");
        item.setAuthor("nay");
        item.setFilePaths(List.of("service/pom.xml"));

        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("org");
        meta.setProject("proj");
        meta.setRepo("repo");
        meta.setBranch("main");

        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(List.of(item));
        return data;
    }

    private static String hashOf(ReleaseData data) {
        List<ReleaseNoteEntry> entries = ReleaseNotePreparer.prepare(data.getItems());
        return InputHash.of(entries);
    }

    private AiResult commit(String project, String repo, String version, boolean commit) {
        ReleaseData data = data();
        return service.ensureAudience(project, repo, version, null, "developer", "test-model",
                true, data, hashOf(data), true, commit, new HashMap<>());
    }

    private Map<String, String> tracking(boolean commit) {
        String project = uniqueId();
        String repo = "repo";
        String version = "1.0.0-" + UUID.randomUUID().toString().substring(0, 8);
        AiResult result = commit(project, repo, version, commit);

        Map<String, String> out = new HashMap<>();
        out.put("text", result.getText());
        out.put("project", project);
        out.put("repo", repo);
        out.put("version", version);
        out.put("cached", String.valueOf(cacheService
                .getCurrent(project, repo, version, "developer", hashOf(data())).isPresent()));
        out.put("versionRows", String.valueOf(changelogService.countVersions(project, repo)));
        return out;
    }

    // ---- the gate ----------------------------------------------------------------------

    @Test
    void commitFalseWritesNeitherCacheNorVersion() {
        Map<String, String> observed = tracking(false);

        assertEquals("generated developer", observed.get("text"), "the AI result is still returned");
        assertEquals("false", observed.get("cached"), "no cache row may be written");
        assertEquals("0", observed.get("versionRows"), "no changelog_version row may be created");
    }

    @Test
    void commitTrueWritesCacheAndVersion() {
        Map<String, String> observed = tracking(true);

        assertEquals("generated developer", observed.get("text"));
        assertEquals("true", observed.get("cached"), "cache row must be written when committing");
        assertEquals("1", observed.get("versionRows"), "changelog_version must be created when committing");
    }

    @Test
    void commitFalseStillReturnsUsableTextForAPreview() {
        ReleaseData data = data();
        AiResult result = service.ensureAudience(uniqueId(), "repo", "1.0.0-preview", null, "developer",
                "test-model", true, data, hashOf(data), true, false, new HashMap<>());

        assertFalse(result.getText().isBlank());
        assertEquals("generated developer", result.getText());
    }

    @Test
    void commitFalseLeavesNoCacheEntryLaterReadersCanPickUp() {
        String project = uniqueId();
        ReleaseData data = data();
        String hash = hashOf(data);

        service.ensureAudience(project, "repo", "2.0.0", null, "developer", "test-model",
                true, data, hash, true, false, new HashMap<>());

        Optional<String> readBack = cacheService.getCurrent(project, "repo", "2.0.0", "developer", hash);
        assertTrue(readBack.isEmpty(), "a preview must not be readable as a saved generation");
    }

    @Test
    void forcedRegenerationWithCommitFalseOverwritesNothing() {
        String project = uniqueId();
        ReleaseData data = data();
        String hash = hashOf(data);

        // First, a real commit.
        service.ensureAudience(project, "repo", "3.0.0", null, "developer", "test-model",
                true, data, hash, true, true, new HashMap<>());
        Optional<String> saved = cacheService.getCurrent(project, "repo", "3.0.0", "developer", hash);
        assertTrue(saved.isPresent());
        assertEquals("generated developer", saved.get());

        // Then a forced preview of the same key: it must not be persisted — but since our fake
        // returns the same text, assert on the version-row count instead, which only the
        // commit=true path may ever touch.
        long rowsBefore = changelogService.countVersions(project, "repo");
        service.ensureAudience(project, "repo", "3.0.0", null, "developer", "test-model",
                true, data, hash, true, false, new HashMap<>());
        assertEquals(rowsBefore, changelogService.countVersions(project, "repo"),
                "a preview must not create additional version rows");
    }

    // ---- force ("Regenerate") ----------------------------------------------------------
    //
    // The REST layer used to hardcode force=false on its multi-audience branch, so Regenerate for
    // all audiences came back with the cached text. These pin the service half of the contract:
    // force must reach the cache lookup and win over it.

    private static final List<String> ALL_AUDIENCES = List.of("developer", "qa", "business");

    @Test
    void forceTrueCallsTheAIAgainEvenWhenACacheRowExists() {
        String project = uniqueId();
        ReleaseData data = data();
        String hash = hashOf(data);

        service.ensureAudience(project, "repo", "4.0.0", null, "developer", "test-model",
                true, data, hash, true, true, new HashMap<>());
        assertEquals(1, FakeProvider.CALLS.get());

        service.ensureAudience(project, "repo", "4.0.0", null, "developer", "test-model",
                true, data, hash, true, false, new HashMap<>());
        assertEquals(2, FakeProvider.CALLS.get(), "force=true must call the AI again despite the cache row");
    }

    @Test
    void forceFalseReusesTheCachedRow() {
        String project = uniqueId();
        ReleaseData data = data();
        String hash = hashOf(data);

        service.ensureAudience(project, "repo", "4.1.0", null, "developer", "test-model",
                true, data, hash, true, true, new HashMap<>());
        assertEquals(1, FakeProvider.CALLS.get());

        service.ensureAudience(project, "repo", "4.1.0", null, "developer", "test-model",
                true, data, hash, false, false, new HashMap<>());
        assertEquals(1, FakeProvider.CALLS.get(), "force=false must serve the cache, not the model");
    }

    @Test
    void aWarmCacheDoesNotSurviveAForcedRegenerationOfAllThreeAudiences() {
        String project = uniqueId();
        ReleaseData data = data();
        String hash = hashOf(data);

        Map<String, AiResult> first = new HashMap<>();
        for (String audience : ALL_AUDIENCES) {
            service.ensureAudience(project, "repo", "5.0.0", null, audience, "test-model",
                    true, data, hash, true, true, first);
        }
        assertEquals(3, FakeProvider.CALLS.get());

        // "Regenerate" for all audiences: force on each call, one shared computed map — exactly
        // what the resource's multi-audience branch now does.
        Map<String, AiResult> second = new HashMap<>();
        for (String audience : ALL_AUDIENCES) {
            service.ensureAudience(project, "repo", "5.0.0", null, audience, "test-model",
                    true, data, hash, true, false, second);
        }
        assertEquals(6, FakeProvider.CALLS.get(),
                "a forced regeneration must call the AI for every audience, cache row or not");
    }

    @Test
    void aWarmCacheIsReusedForAllThreeAudiencesWhenNotForcing() {
        String project = uniqueId();
        ReleaseData data = data();
        String hash = hashOf(data);

        Map<String, AiResult> first = new HashMap<>();
        for (String audience : ALL_AUDIENCES) {
            service.ensureAudience(project, "repo", "5.1.0", null, audience, "test-model",
                    true, data, hash, true, true, first);
        }
        assertEquals(3, FakeProvider.CALLS.get());

        Map<String, AiResult> second = new HashMap<>();
        for (String audience : ALL_AUDIENCES) {
            service.ensureAudience(project, "repo", "5.1.0", null, audience, "test-model",
                    true, data, hash, false, false, second);
        }
        assertEquals(3, FakeProvider.CALLS.get(),
                "an ordinary generate must serve every audience from the cache");
    }
}
