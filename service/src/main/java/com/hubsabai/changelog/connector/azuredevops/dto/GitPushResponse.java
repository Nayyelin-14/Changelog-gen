package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response body for {@code POST .../pushes} — just enough to link back to the resulting commit. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitPushResponse(@JsonProperty("commits") List<Commit> commits) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(@JsonProperty("commitId") String commitId) {
    }
}
