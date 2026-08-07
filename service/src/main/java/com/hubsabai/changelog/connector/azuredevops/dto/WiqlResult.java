package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WiqlResult(@JsonProperty("workItems") List<WorkItemReference> workItems) {

    public List<WorkItemReference> workItemsOrEmpty() {
        return workItems != null ? workItems : List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkItemReference(@JsonProperty("id") int id, @JsonProperty("url") String url) {
    }
}
