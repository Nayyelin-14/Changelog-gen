package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One selectable entry in the model dropdown — {@code id} is the exact string the NIM API expects. */
public record AiModelOption(
        @JsonProperty("id") String id,
        @JsonProperty("label") String label,
        @JsonProperty("recommended") boolean recommended,
        @JsonProperty("status") String status) {

    public static AiModelOption of(String id, String label) {
        return new AiModelOption(id, label, false, null);
    }

    public static AiModelOption available(String id, String label, boolean recommended) {
        return new AiModelOption(id, label, recommended, "available");
    }

    public static AiModelOption checking(String id, String label, boolean recommended) {
        return new AiModelOption(id, label, recommended, "checking");
    }
}