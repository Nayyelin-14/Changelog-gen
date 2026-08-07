package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One result of {@code POST .../refs}. Azure DevOps returns HTTP 200 even on rejection —
 * check {@code success}, not the HTTP status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefUpdateResult(
        @JsonProperty("name") String name,
        @JsonProperty("success") boolean success,
        @JsonProperty("updateStatus") String updateStatus) {
}
