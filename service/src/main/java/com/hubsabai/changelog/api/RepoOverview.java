package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One repo's status at a glance, for the Dev dashboard's repo table — the latest real version
 * (if any) and how many recently merged PRs still have no changelog generated for them yet. */
public record RepoOverview(
        @JsonProperty("name") String name,
        @JsonProperty("defaultBranch") String defaultBranch,
        @JsonProperty("latestVersion") String latestVersion,
        @JsonProperty("latestVersionAt") String latestVersionAt,
        // Capped at whatever AzureDevOpsResource#buildUngeneratedEntries returns (currently the
        // 10 most recently merged, un-generated PRs) — a repo with more than that shows this cap,
        // not the true total.
        @JsonProperty("needsReviewCount") int needsReviewCount) {
}
