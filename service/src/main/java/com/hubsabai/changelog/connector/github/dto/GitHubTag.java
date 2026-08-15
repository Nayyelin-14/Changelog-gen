package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTag(
        @JsonProperty("name") String name,
        @JsonProperty("commit") GitHubTagCommit commit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubTagCommit(@JsonProperty("sha") String sha) {}
}