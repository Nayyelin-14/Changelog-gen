package com.hubsabai.changelog.generation;

import com.hubsabai.changelog.core.model.ReleaseData;

/**
 * Provider-specific hooks the shared {@link ChangelogGenerationService} needs to rediscover change
 * data for a version. Implementations are thin adapters owned by each REST resource, wrapping their
 * own connector — so the shared fallback chain never knows which provider it's talking to.
 */
public interface ChangelogDataSource {

    /** Primary source of change items for a version range (tag compare, commit scan, ...). */
    ReleaseData fetchRepoChanges(String fromVersion, String toVersion, String branch);

    /** The body text of the changelog entry for {@code version} on the resolved branch, or null. */
    String fetchChangelogBody(String version);
}
