package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepositorySummary(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("project") String project,
        @JsonProperty("defaultBranch") String defaultBranch,
        @JsonProperty("visibility") String visibility) {

    public RepositorySummary(String id, String name, String project, String defaultBranch) {
        this(id, name, project, defaultBranch, null);
    }
}
