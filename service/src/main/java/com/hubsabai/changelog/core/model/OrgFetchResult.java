package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The result of walking every project and every repo in the org — see README §3. */
public record OrgFetchResult(
        @JsonProperty("org") String org,
        @JsonProperty("projects") List<ProjectFetchResult> projects) {
}
