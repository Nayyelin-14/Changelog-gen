package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitChangesResponse(
        @JsonProperty("changes") List<Change> changes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(
            @JsonProperty("item") Item item,
            @JsonProperty("changeType") String changeType) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(@JsonProperty("path") String path) {
        }
    }
}
