package com.hubsabai.changelog.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Body of a {@code POST .../changelog-chat/stream} call — a new question plus whatever prior
 * turns the browser still has (see {@link ChatTurn}). No server-side chat state exists for this
 * to build on, so every request carries its own full working context. */
public class ChangelogChatRequest {
    private String question;
    private List<ChatTurn> history;

    public ChangelogChatRequest() {}

    @JsonProperty("question")
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    @JsonProperty("history")
    public List<ChatTurn> getHistory() { return history; }
    public void setHistory(List<ChatTurn> history) { this.history = history; }
}
