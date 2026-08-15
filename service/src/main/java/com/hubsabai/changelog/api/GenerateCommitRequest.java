package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GenerateCommitRequest {

    private String version;
    private String branch;
    private String audience;
    private String model;
    private String text;
    private Integer tokens;
    private Integer durationMs;
    private Long buildId;

    public GenerateCommitRequest() {}

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    /** When {@code version} is absent (a manual dashboard generation), the draft is persisted
     * keyed on the pipeline run this text came from instead — {@code version} is then chosen by
     * the human in the push modal and written into the row at push time. */
    @JsonProperty("buildId")
    public Long getBuildId() { return buildId; }
    public void setBuildId(Long buildId) { this.buildId = buildId; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("audience")
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    @JsonProperty("model")
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @JsonProperty("text")
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    /** From the preview call's own usage — this endpoint only ever persists an already-computed
     * candidate, never calls the AI itself, so real tokens/duration have to be carried over from
     * whoever generated the preview rather than recomputed here. */
    @JsonProperty("tokens")
    public Integer getTokens() { return tokens; }
    public void setTokens(Integer tokens) { this.tokens = tokens; }

    @JsonProperty("durationMs")
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
}
