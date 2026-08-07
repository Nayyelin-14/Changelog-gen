package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Everything fetched for a single project: repos (commits + PRs per repo) plus
 * project-level work items (not yet attributed to a specific repo).
 */
public record ProjectFetchResult(
        @JsonProperty("project") ProjectSummary project,
        @JsonProperty("workItems") List<ChangeItem> workItems,
        @JsonProperty("repositories") List<ReleaseData> repositories) {
}
