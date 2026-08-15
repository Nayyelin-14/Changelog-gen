package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Contents API response — the file body is base64 in {@code content} unless encoding==raw. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubFileContent(
        @JsonProperty("content") String content,
        @JsonProperty("encoding") String encoding,
        @JsonProperty("sha") String sha,
        @JsonProperty("name") String name) {}