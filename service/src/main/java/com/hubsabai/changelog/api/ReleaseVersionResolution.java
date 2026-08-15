package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response for the release-version resolution endpoint.
 * Provides the latest semantic version found in CHANGELOG.md (or git), a suggested
 * next version, and the current branch HEAD commit SHA.
 */
public class ReleaseVersionResolution {

    /** The latest valid semantic version found in the changelog (e.g. "1.4.29"), or null if none. */
    @JsonProperty("latestVersion")
    private String latestVersion;

    /** The suggested next version derived from {@link #latestVersion} (e.g. "1.4.30"), or "0.1.0" if none. */
    @JsonProperty("suggestedNextVersion")
    private String suggestedNextVersion;

    /** The commit SHA of the current branch HEAD. */
    @JsonProperty("currentBranchSha")
    private String currentBranchSha;

    /** Whether any existing version exists in the changelog (true = version exists, false = initial). */
    @JsonProperty("changelogExists")
    private boolean changelogExists;

    /** Whether the version "1.0.0" is a reasonable starting point (true = yes, this is likely the first version). */
    @JsonProperty("requiresInitialVersion")
    private boolean requiresInitialVersion;

    public ReleaseVersionResolution() {
    }

    public ReleaseVersionResolution(String latestVersion, String suggestedNextVersion,
                                     String currentBranchSha, boolean changelogExists,
                                     boolean requiresInitialVersion) {
        this.latestVersion = latestVersion;
        this.suggestedNextVersion = suggestedNextVersion;
        this.currentBranchSha = currentBranchSha;
        this.changelogExists = changelogExists;
        this.requiresInitialVersion = requiresInitialVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public String getSuggestedNextVersion() {
        return suggestedNextVersion;
    }

    public void setSuggestedNextVersion(String suggestedNextVersion) {
        this.suggestedNextVersion = suggestedNextVersion;
    }

    public String getCurrentBranchSha() {
        return currentBranchSha;
    }

    public void setCurrentBranchSha(String currentBranchSha) {
        this.currentBranchSha = currentBranchSha;
    }

    public boolean isChangelogExists() {
        return changelogExists;
    }

    public void setChangelogExists(boolean changelogExists) {
        this.changelogExists = changelogExists;
    }

    public boolean isRequiresInitialVersion() {
        return requiresInitialVersion;
    }

    public void setRequiresInitialVersion(boolean requiresInitialVersion) {
        this.requiresInitialVersion = requiresInitialVersion;
    }
}
