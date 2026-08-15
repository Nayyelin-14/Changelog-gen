package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChangelogRevisionDto(
        @JsonProperty("sequence") int sequence,
        @JsonProperty("source") String source,
        @JsonProperty("model") String model,
        @JsonProperty("tokens") Integer tokens,
        @JsonProperty("durationMs") Integer durationMs,
        @JsonProperty("editedBy") String editedBy,
        @JsonProperty("text") String text,
        @JsonProperty("createdAt") String createdAt) {

    public int getSequence() { return sequence; }
    public String getSource() { return source; }
    public String getModel() { return model; }
    public Integer getTokens() { return tokens; }
    public Integer getDurationMs() { return durationMs; }
    public String getEditedBy() { return editedBy; }
    public String getText() { return text; }
    public String getCreatedAt() { return createdAt; }
}