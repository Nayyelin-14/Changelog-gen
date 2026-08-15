package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequest(
        @JsonProperty("number") long number,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("state") String state,
        @JsonProperty("merged_at") String mergedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("merge_commit_sha") String mergeCommitSha,
        @JsonProperty("user") GitHubOrgUser user,
        @JsonProperty("base") GitHubPrBranch base,
        @JsonProperty("merged_by") GitHubOrgUser mergedBy) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubPrBranch(@JsonProperty("ref") String ref) {}
}