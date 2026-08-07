package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitRef(
        @JsonProperty("name") String name,
        @JsonProperty("objectId") String objectId,
        @JsonProperty("peeledObjectId") String peeledObjectId) {

    /** The actual commit SHA this ref points to (handles annotated tags via peeledObjectId). */
    public String commitId() {
        return peeledObjectId() != null ? peeledObjectId() : objectId();
    }

    /** Tag name without the "refs/tags/" prefix. */
    public String tagName() {
        if (name == null) return null;
        return name.startsWith("refs/tags/") ? name.substring("refs/tags/".length()) : name;
    }
}
