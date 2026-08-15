package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One selectable entry in the model dropdown — {@code id} is the exact string the NIM API expects. */
public record AiModelOption(
        @JsonProperty("id") String id,
        @JsonProperty("label") String label,
        @JsonProperty("recommended") boolean recommended) {

    public static AiModelOption of(String id, String label) {
        return new AiModelOption(id, label, false);
    }
}