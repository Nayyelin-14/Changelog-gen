package com.hubsabai.changelog.chat;

import com.hubsabai.changelog.core.model.ChangeItem;
import java.util.List;

/**
 * Builds the system prompt for the changelog Q&A chat (see CHATBOT-PLAN.md). Grounds the model
 * in the audience's already-generated summary plus the full list of changes behind the version
 * — not just the one paragraph the page shows, so a real follow-up question has more to draw on
 * than a condensed summary can carry. The rules below constrain the OUTPUT, not just the input:
 * regardless of how technical the material below reads, QA/Business must only ever see plain,
 * non-technical language back.
 */
public final class ChangelogChatPromptBuilder {

    private ChangelogChatPromptBuilder() {}

    public static String build(String repo, String version, String audience, String summaryText, List<ChangeItem> items) {
        StringBuilder prompt = new StringBuilder();
        // Stated explicitly rather than left implicit — without this, a plain "what version is
        // this?" has no fact anywhere in the prompt to answer from, and the model correctly (but
        // unhelpfully) says it has no idea.
        prompt.append("This conversation is about version ").append(version).append(" of \"").append(repo)
              .append("\" (the ").append(audience).append(" summary of it). Stay scoped to this changelog: a plain greeting is fine to answer briefly, but redirect anything else unrelated back to this version's changes.\n\n");
        prompt.append(GROUNDING_RULES);
        prompt.append("business".equals(audience) ? BUSINESS_FRAMING : QA_FRAMING);

        prompt.append("\n\n--- Summary already shown to the user ---\n").append(summaryText);

        if (items != null && !items.isEmpty()) {
            prompt.append("\n\n--- Individual changes behind this version ---\n");
            for (ChangeItem item : items) {
                prompt.append("- [").append(orEmpty(item.getCategory())).append("] ").append(orEmpty(item.getTitle()));
                if (item.getDescription() != null && !item.getDescription().equals(item.getTitle())) {
                    prompt.append(": ").append(item.getDescription());
                }
                prompt.append("\n");
            }
        }
        return prompt.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final String GROUNDING_RULES = """
            Answer only using the material below — never invent, assume, or guess anything not present in it. Never mention code, files, diffs, PR numbers, work-item numbers, or any other implementation detail, even if the material below contains them: translate everything into plain, non-technical language.
            """;

    private static final String QA_FRAMING = """
            Describe only user-visible behavior and how to test it, as direct guidance (Verify/Confirm/Check ...). If nothing below shows a visible behavior change for what's being asked, say plainly that no testable change is noted for that — never invent one.
            """;

    private static final String BUSINESS_FRAMING = """
            Describe impact and rationale in plain business language, phrased as intent ("this change is designed to...", "the aim is to...") rather than a confirmed outcome — nobody has verified the change behaves as intended in production, only that this was the goal. If nothing below shows a customer-facing effect for what's being asked, say plainly that no customer-facing impact is noted — this is an internal technical change — never invent one.
            """;
}
