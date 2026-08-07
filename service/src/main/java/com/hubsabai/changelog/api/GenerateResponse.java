package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hubsabai.changelog.ai.AiUsage;

import java.util.List;

/** All three audience changelogs for one repo, with usage and timing. */
public class GenerateResponse {
    private final String developer;
    private final String qa;
    private final String business;
    private final List<AiUsage> usage;
    private final long durationMs;
    private final boolean saved;

    public GenerateResponse(
            @JsonProperty("developer") String developer,
            @JsonProperty("qa") String qa,
            @JsonProperty("business") String business,
            @JsonProperty("usage") List<AiUsage> usage,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("saved") boolean saved) {
        this.developer = developer;
        this.qa = qa;
        this.business = business;
        this.usage = usage;
        this.durationMs = durationMs;
        this.saved = saved;
    }

    @JsonProperty("developer")
    public String getDeveloper() { return developer; }

    @JsonProperty("qa")
    public String getQa() { return qa; }

    @JsonProperty("business")
    public String getBusiness() { return business; }

    @JsonProperty("usage")
    public List<AiUsage> getUsage() { return usage; }

    @JsonProperty("durationMs")
    public long getDurationMs() { return durationMs; }

    @JsonProperty("saved")
    public boolean isSaved() { return saved; }
}
