package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AiResponse {
    private List<Choice> choices;
    private Usage usage;

    public AiResponse() {}

    @JsonProperty("choices")
    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }

    @JsonProperty("usage")
    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    public static class Choice {
        private Message message;
        // Only present on streaming ("stream": true) chunks — a non-streaming response only ever
        // sets `message`, so this stays null there and never affects the 28 existing tests.
        private Message delta;

        @JsonProperty("message")
        public Message getMessage() { return message; }
        public void setMessage(Message message) { this.message = message; }

        @JsonProperty("delta")
        public Message getDelta() { return delta; }
        public void setDelta(Message delta) { this.delta = delta; }
    }

    public static class Message {
        private String content;
        private String reasoning;
        private String reasoningContent;

        @JsonProperty("content")
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        @JsonProperty("reasoning")
        public String getReasoning() { return reasoning; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }

        @JsonProperty("reasoning_content")
        public String getReasoningContent() { return reasoningContent; }
        public void setReasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; }

        public String getContentOrReasoning() {
            if (content != null && !content.isBlank()) return content;
            if (reasoning != null && !reasoning.isBlank()) return reasoning;
            if (reasoningContent != null && !reasoningContent.isBlank()) return reasoningContent;
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        @JsonProperty("prompt_tokens")
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

        @JsonProperty("completion_tokens")
        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

        @JsonProperty("total_tokens")
        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    }
}
