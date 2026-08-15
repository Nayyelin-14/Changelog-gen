package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubBranch(
        @JsonProperty("name") String name,
        @JsonProperty("commit") GitHubBranchCommit commit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubBranchCommit(@JsonProperty("sha") String sha) {}
}
