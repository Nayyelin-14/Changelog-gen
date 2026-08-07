package com.hubsabai.changelog.ai;

public class AiResult {
    private final String text;
    private final AiUsage usage;
    private final String model;

    public AiResult(String text, AiUsage usage) {
        this(text, usage, null);
    }

    /** {@code model} is whichever candidate in the fallback chain actually produced this result
     * — not necessarily the one requested, if a fallback happened. Null wherever no caller has
     * bothered to report it yet (existing blocking call sites); only the streaming chat path
     * populates it today, since that's the only place it's shown back to a user. */
    public AiResult(String text, AiUsage usage, String model) {
        this.text = text;
        this.usage = usage;
        this.model = model;
    }

    public String getText() { return text; }
    public AiUsage getUsage() { return usage; }
    public String getModel() { return model; }
}
