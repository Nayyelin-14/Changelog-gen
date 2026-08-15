package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Shape of the OpenAI-compatible {@code GET /v1/models} response NVIDIA NIM serves. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NvidiaModelsResponse(
        @JsonProperty("data") List<NvidiaModel> data) {

    public List<NvidiaModel> dataOrEmpty() {
        return data != null ? data : List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NvidiaModel(@JsonProperty("id") String id) {
        public String getId() { return id; }
    }
}