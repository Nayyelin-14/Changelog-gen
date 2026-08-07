package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A single field off the Git Items API response — {@code content} holds the file's raw text when fetched with {@code includeContent=true}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitItemResponse(@JsonProperty("content") String content) {
}
