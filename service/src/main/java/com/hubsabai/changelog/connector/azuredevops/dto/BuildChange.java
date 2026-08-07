package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One commit associated with a build run ({@code GET .../builds/{buildId}/changes}) — already
 * scoped by Azure DevOps to "since the previous build of this pipeline definition", so no tag or
 * semver reconstruction is needed to know which commits belong to this run. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuildChange(
        @JsonProperty("id") String id,
        @JsonProperty("message") String message,
        @JsonProperty("author") Author author,
        @JsonProperty("displayUri") String displayUri) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(@JsonProperty("displayName") String displayName) {
    }
}
