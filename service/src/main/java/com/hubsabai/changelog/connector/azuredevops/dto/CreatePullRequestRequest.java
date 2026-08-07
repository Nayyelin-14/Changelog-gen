package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST .../pullrequests}. */
public record CreatePullRequestRequest(
        @JsonProperty("sourceRefName") String sourceRefName,
        @JsonProperty("targetRefName") String targetRefName,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description) {
}
