package com.hubsabai.changelog.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One turn of a changelog Q&A conversation ("user"/"assistant"), as sent by the browser on
 * every request — the browser is the only place a conversation is kept (see
 * CHATBOT-PLAN.md's localStorage design), so the server holds no chat state of its own. */
public class ChatTurn {
    private String role;
    private String content;

    public ChatTurn() {}

    public ChatTurn(String role, String content) {
        this.role = role;
        this.content = content;
    }

    @JsonProperty("role")
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    @JsonProperty("content")
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
