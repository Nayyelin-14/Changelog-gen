package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ChangelogRevisionDto {

    private final int sequence;
    private final String source;
    private final String model;
    private final Integer tokens;
    private final Integer durationMs;
    private final String editedBy;
    private final String text;
    private final String createdAt;

    public ChangelogRevisionDto(
            @JsonProperty("sequence") int sequence,
            @JsonProperty("source") String source,
            @JsonProperty("model") String model,
            @JsonProperty("tokens") Integer tokens,
            @JsonProperty("durationMs") Integer durationMs,
            @JsonProperty("editedBy") String editedBy,
            @JsonProperty("text") String text,
            @JsonProperty("createdAt") String createdAt) {
        this.sequence = sequence;
        this.source = source;
        this.model = model;
        this.tokens = tokens;
        this.durationMs = durationMs;
        this.editedBy = editedBy;
        this.text = text;
        this.createdAt = createdAt;
    }

    @JsonProperty("sequence") public int getSequence() { return sequence; }
    @JsonProperty("source") public String getSource() { return source; }
    @JsonProperty("model") public String getModel() { return model; }
    @JsonProperty("tokens") public Integer getTokens() { return tokens; }
    @JsonProperty("durationMs") public Integer getDurationMs() { return durationMs; }
    @JsonProperty("editedBy") public String getEditedBy() { return editedBy; }
    @JsonProperty("text") public String getText() { return text; }
    @JsonProperty("createdAt") public String getCreatedAt() { return createdAt; }
}
