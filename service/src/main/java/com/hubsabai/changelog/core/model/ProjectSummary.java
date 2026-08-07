package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProjectSummary(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description) {
}
