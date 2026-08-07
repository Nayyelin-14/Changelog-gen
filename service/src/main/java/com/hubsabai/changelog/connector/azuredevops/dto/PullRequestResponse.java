package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestResponse(
        @JsonProperty("pullRequestId") int pullRequestId,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("createdBy") IdentityRef createdBy,
        @JsonProperty("creationDate") String creationDate,
        @JsonProperty("closedDate") String closedDate,
        @JsonProperty("sourceRefName") String sourceRefName,
        @JsonProperty("targetRefName") String targetRefName,
        @JsonProperty("url") String url,
        @JsonProperty("lastMergeCommit") CommitRef lastMergeCommit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentityRef(
            @JsonProperty("displayName") String displayName,
            @JsonProperty("uniqueName") String uniqueName) {
    }

    /** The commit landed on the target branch when this PR completed — present regardless of
     * merge strategy (merge/squash/rebase), unlike a "Merged PR N" mention in the commit message
     * (which a squash/rebase completion, or a custom commit-message template, may not contain). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitRef(@JsonProperty("commitId") String commitId) {
    }
}
