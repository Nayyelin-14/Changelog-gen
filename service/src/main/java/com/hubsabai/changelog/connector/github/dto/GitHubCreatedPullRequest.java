package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response to creating a PR — {@code html_url} and {@code number} for the dashboard to link to. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCreatedPullRequest(
        @JsonProperty("number") long number,
        @JsonProperty("html_url") String htmlUrl) {}