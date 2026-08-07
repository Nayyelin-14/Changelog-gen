package com.hubsabai.changelog.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class AiMessage {
    private String role;
    private String content;

    public AiMessage() {}

    public AiMessage(String role, String content) {
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
