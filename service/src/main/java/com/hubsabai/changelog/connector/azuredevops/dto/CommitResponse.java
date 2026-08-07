package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitResponse(
        @JsonProperty("commitId") String commitId,
        @JsonProperty("author") CommitIdentity author,
        @JsonProperty("comment") String comment,
        @JsonProperty("url") String url) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitIdentity(
            @JsonProperty("name") String name,
            @JsonProperty("email") String email,
            @JsonProperty("date") String date) {
    }
}
