package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubWorkflowRunsResponse(
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("workflow_runs") List<GitHubWorkflowRun> workflowRuns) {}