package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Shape shared by every Azure DevOps "list" endpoint: {@code { count, value: [...] } }. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureDevOpsListResponse<T>(
        @JsonProperty("count") int count,
        @JsonProperty("value") List<T> value) {

    public List<T> valueOrEmpty() {
        return value != null ? value : List.of();
    }
}
