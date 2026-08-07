package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AiUsage {
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public AiUsage() {}

    public AiUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    @JsonProperty("promptTokens")
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    @JsonProperty("completionTokens")
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    @JsonProperty("totalTokens")
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
}
