package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.generation.RunChangeContext;

/**
 * Combined result of a single pipeline run fetch — both the normalized {@link ReleaseData}
 * (for changelog generation) and the {@link RunChangeContext} (for dashboard run context).
 * Obtained from one coordinated fetch to avoid duplicate provider API calls.
 */
public record RunFetchResult(ReleaseData releaseData, RunChangeContext runContext) {}