package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.generation.ChangelogGenerationService;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the two request flags on the generate path.
 *
 * <p>{@code POST .../generate} without an {@code audience} used to ignore both of them:
 * <ul>
 *   <li>{@code commit=false} (the dashboard's preview flow) still wrote generated_changelog rows
 *       and cache entries for a release nobody had confirmed, because a hardcoded {@code true}
 *       was passed into {@link ChangelogGenerationService#ensureAudience} for all three audiences;</li>
 *   <li>{@code force=true} ("Regenerate") was replaced by a hardcoded {@code false}, so the cached
 *       text was served straight back and the button appeared to do nothing.</li>
 * </ul>
 * The single-audience branch has always forwarded both flags; the multi-audience branch must
 * match it.
 *
 * <p>The generation service is stubbed out here so the assertions are exactly "what did the
 * resource hand over", without touching the database or an AI provider.
 */
class GenerateCommitFlagTest {

    /** Records every flag the resource forwards, instead of doing any work. */
    static final class RecordingGenerationService extends ChangelogGenerationService {
        final List<String> audiences = new ArrayList<>();
        final List<Boolean> commits = new ArrayList<>();
        final List<Boolean> forces = new ArrayList<>();
        final List<String> versions = new ArrayList<>();

        @Override
        public AiResult ensureAudience(String project, String repo, String version, String provider,
                String audience, String model, boolean strict, ReleaseData data, String inputHash,
                boolean force, boolean commit, Map<String, AiResult> computed) {
            audiences.add(audience);
            commits.add(commit);
            forces.add(force);
            versions.add(version);
            AiResult result = new AiResult("generated for " + audience, null);
            computed.put(audience, result);
            return result;
        }
    }

    /** Reports every audience as cached, so the resource's "was this a cache hit?" flag is
     *  observable without a database. Flip {@link #cached} to control it. */
    static final class StubCacheService extends ChangelogCacheService {
        boolean cached = true;

        @Override
        public Optional<String> getCurrent(String project, String repo, String version,
                String audience, String inputHash) {
            return cached ? Optional.of("cached " + audience) : Optional.empty();
        }
    }

    private static final String MANUAL_TEXT =
            "=== 82dcadc\n"
                    + "ci: add weekly GHCR image cleanup workflow\n"
                    + "\n"
                    + "=== 43641b6\n"
                    + "fix: rotate the signing key on every deploy\n";

    private static GitHubResource github() {
        GitHubResource resource = new GitHubResource();
        resource.generationService = new RecordingGenerationService();
        resource.cacheService = new StubCacheService();
        return resource;
    }

    private static AzureDevOpsResource azure() {
        AzureDevOpsResource resource = new AzureDevOpsResource();
        resource.generationService = new RecordingGenerationService();
        resource.cacheService = new StubCacheService();
        return resource;
    }

    private static StubCacheService stubCache(GitHubResource resource) {
        return (StubCacheService) resource.cacheService;
    }

    private static StubCacheService stubCache(AzureDevOpsResource resource) {
        return (StubCacheService) resource.cacheService;
    }

    // ---- GitHub ------------------------------------------------------------------------

    @Test
    void githubMultiAudienceStopsWritingWhenCommitIsFalse() {
        GitHubResource resource = github();

        GenerateResponse response = resource.generate(
                "proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, false);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of("developer", "qa", "business"), svc.audiences);
        assertEquals(List.of(false, false, false), svc.commits,
                "multi-audience generate must honour commit=false");
        assertEquals("generated for developer", response.getDeveloper());
        assertEquals("generated for qa", response.getQa());
        assertEquals("generated for business", response.getBusiness());
        assertFalse(response.isSaved(), "nothing was written, so the response is not 'saved'");
    }

    @Test
    void githubMultiAudienceStillWritesWhenCommitIsTrue() {
        GitHubResource resource = github();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(true, true, true), svc.commits);
    }

    @Test
    void githubSingleAudienceHonoursCommitFalse() {
        GitHubResource resource = github();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, "developer", true, 0, false);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of("developer"), svc.audiences);
        assertEquals(List.of(false), svc.commits);
    }

    @Test
    void githubRefusesToWriteWhenTheVersionIsBlankEvenIfCommitIsTrue() {
        // generated_changelog.version is NOT NULL — a blank version can never be committed.
        GitHubResource resource = github();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, null, null,
                MANUAL_TEXT, null, true, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(false, false, false), svc.commits);
        assertNull(svc.versions.get(0));
    }

    // ---- Azure DevOps ------------------------------------------------------------------

    @Test
    void azureMultiAudienceStopsWritingWhenCommitIsFalse() {
        AzureDevOpsResource resource = azure();

        GenerateResponse response = resource.generate(
                "proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, false);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of("developer", "qa", "business"), svc.audiences);
        assertEquals(List.of(false, false, false), svc.commits,
                "multi-audience generate must honour commit=false");
        assertEquals("generated for business", response.getBusiness());
    }

    @Test
    void azureMultiAudienceStillWritesWhenCommitIsTrue() {
        AzureDevOpsResource resource = azure();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(true, true, true), svc.commits);
    }

    @Test
    void azureSingleAudienceHonoursCommitFalse() {
        AzureDevOpsResource resource = azure();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, "qa", true, 0, false);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of("qa"), svc.audiences);
        assertEquals(List.of(false), svc.commits);
    }

    // ---- force ("Regenerate") -----------------------------------------------------------

    // force was hardcoded to false on the multi-audience branch, so Regenerate for all audiences
    // served the cached text back untouched. The single-audience branch has always forwarded it.

    @Test
    void githubMultiAudienceForcesWhenRegenerateIsRequested() {
        GitHubResource resource = github();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(true, true, true), svc.forces,
                "Regenerate must ask the service to bypass the cache for every audience");
    }

    @Test
    void githubMultiAudienceDoesNotForceOnAnOrdinaryGenerate() {
        GitHubResource resource = github();
        stubCache(resource).cached = false;

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, false, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(false, false, false), svc.forces);
    }

    @Test
    void azureMultiAudienceForcesWhenRegenerateIsRequested() {
        AzureDevOpsResource resource = azure();

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(true, true, true), svc.forces);
    }

    @Test
    void azureMultiAudienceDoesNotForceOnAnOrdinaryGenerate() {
        AzureDevOpsResource resource = azure();
        stubCache(resource).cached = false;

        resource.generate("proj", "repo", "gpt-4o-mini", null, null, "1.2.0", null,
                MANUAL_TEXT, null, false, 0, true);

        RecordingGenerationService svc = (RecordingGenerationService) resource.generationService;
        assertEquals(List.of(false, false, false), svc.forces);
    }

    @Test
    void githubSingleAudienceForwardsForceToo() {
        GitHubResource forced = github();
        forced.generate("proj", "repo", "m", null, null, "1.2.0", null, MANUAL_TEXT, "developer", true, 0, true);
        assertEquals(List.of(true), ((RecordingGenerationService) forced.generationService).forces);

        GitHubResource unforced = github();
        stubCache(unforced).cached = false;
        unforced.generate("proj", "repo", "m", null, null, "1.2.0", null, MANUAL_TEXT, "developer", false, 0, true);
        assertEquals(List.of(false), ((RecordingGenerationService) unforced.generationService).forces);
    }

    @Test
    void multiAudienceReportsACacheHitOnlyWhenEveryAudienceWasAlreadyCached() {
        GitHubResource resource = github();
        stubCache(resource).cached = true;

        GenerateResponse allCached = resource.generate("proj", "repo", "m", null, null, "1.2.0", null,
                MANUAL_TEXT, null, false, 0, true);
        assertTrue(allCached.isSaved(), "three cache hits must be reported as a cache hit");

        stubCache(resource).cached = false;
        GitHubResource fresh = github();
        stubCache(fresh).cached = false;
        GenerateResponse noneCached = fresh.generate("proj", "repo", "m", null, null, "1.2.0", null,
                MANUAL_TEXT, null, false, 0, true);
        assertFalse(noneCached.isSaved(), "no cache hit must not be reported as one");
    }

    @Test
    void multiAudienceNeverReportsACacheHitWhenForcing() {
        GitHubResource resource = github();
        stubCache(resource).cached = true;

        GenerateResponse response = resource.generate("proj", "repo", "m", null, null, "1.2.0", null,
                MANUAL_TEXT, null, true, 0, true);

        assertFalse(response.isSaved(), "a forced run bypasses the cache, so it cannot be a hit");
    }

    // ---- shared contract ---------------------------------------------------------------

    @Test
    void bothResourcesForwardTheSameFlagForTheSameRequest() {
        GitHubResource gh = github();
        gh.generate("proj", "repo", "m", null, null, "1.2.0", null, MANUAL_TEXT, null, false, 0, false);
        AzureDevOpsResource az = azure();
        az.generate("proj", "repo", "m", null, null, "1.2.0", null, MANUAL_TEXT, null, false, 0, false);

        assertEquals(((RecordingGenerationService) gh.generationService).commits,
                ((RecordingGenerationService) az.generationService).commits,
                "the two provider resources must not drift on the commit contract");
        assertEquals(((RecordingGenerationService) gh.generationService).forces,
                ((RecordingGenerationService) az.generationService).forces,
                "the two provider resources must not drift on the force contract");
    }

    @Test
    void defaultCommitValueRemainsTrueWhenTheParameterIsOmitted() {
        // @DefaultValue("true") on the JAX-RS binding — mirror it by passing true explicitly and
        // documenting the contract the dashboard relies on.
        GitHubResource resource = github();
        resource.generate("proj", "repo", "m", null, null, "1.2.0", null, MANUAL_TEXT, null, true, 0, true);

        assertTrue(((RecordingGenerationService) resource.generationService).commits.stream().allMatch(b -> b));
    }
}
