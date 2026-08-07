package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST .../_apis/wit/wiql}. */
public record WiqlQuery(@JsonProperty("query") String query) {
}
