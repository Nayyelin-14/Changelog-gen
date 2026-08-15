package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubOrgUser(
        @JsonProperty("login") String login,
        @JsonProperty("name") String name,
        @JsonProperty("html_url") String htmlUrl) {}
