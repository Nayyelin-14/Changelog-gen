package com.hubsabai.changelog.ai;

public record PromptRequest(
    String promptVersion,
    String systemPrompt,
    String userPrompt
) {}
