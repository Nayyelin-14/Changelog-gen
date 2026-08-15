package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubWorkflowRun(
        @JsonProperty("id") long id,
        @JsonProperty("run_number") int runNumber,
        @JsonProperty("run_attempt") int runAttempt,
        @JsonProperty("status") String status,
        @JsonProperty("conclusion") String conclusion,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("run_started_at") String runStartedAt,
        @JsonProperty("completed_at") String completedAt,
        @JsonProperty("head_branch") String headBranch,
        @JsonProperty("head_sha") String headSha,
        @JsonProperty("head_commit") HeadCommit headCommit,
        @JsonProperty("workflow_id") long workflowId,
        @JsonProperty("event") String event,
        @JsonProperty("pull_requests") java.util.List<PullRequestRef> pullRequests,
        @JsonProperty("name") String workflowName) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeadCommit(
            @JsonProperty("id") String id,
            @JsonProperty("message") String message,
            @JsonProperty("author") CommitAuthor author) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitAuthor(
            @JsonProperty("name") String name,
            @JsonProperty("email") String email) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestRef(
            @JsonProperty("number") int number,
            @JsonProperty("head") BranchRef head,
            @JsonProperty("base") BranchRef base) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchRef(
            @JsonProperty("ref") String ref,
            @JsonProperty("sha") String sha) {}
}