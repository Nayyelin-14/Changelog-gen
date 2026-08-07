package com.hubsabai.changelog.ai;

import java.util.List;

/**
 * Assembles the final prompt from components: a small universal system prompt plus one
 * self-contained per-audience block (audience/goal framing, that audience's own mechanical
 * format, its content-filtering rules, and its examples). Provider-agnostic — the same composer
 * can feed any LLM provider.
 *
 * <p>Two concerns are deliberately kept separate: audience framing (who's reading, what they
 * care about, what to ignore) steers content and tone; the explicit format rule in each
 * audience's own block keeps output mechanically parseable. Persona framing alone doesn't
 * reliably fix format compliance on the smaller/free models this app also targets — the
 * production failure that motivated the explicit rule was a real model skipping the required
 * "tag: description" separator despite the format being shown in an example.
 */
public final class PromptComposer {

    private static final String SYSTEM = """
            You generate release notes for Hubsabai.

            Use only the supplied information.
            Preserve the order of the supplied changes.
            Do not invent, infer, or speculate about missing details.
            If information is ambiguous, omit it rather than guessing.

            Return only the requested Markdown. Do not include a title, introduction, summary,
            closing remark, or code fence — the response is inserted as-is, so nothing outside
            the actual bullets belongs in it.
            """;

    // Shared across all three audience examples below, so every prompt teaches the SAME
    // transformation from the SAME input — only the expected output differs per audience.
    // Keeping the input in one place (not copy-pasted three times) means an example update can
    // never let the three audiences drift out of sync with each other.
    private static final String EXAMPLE_INPUT_AUTH = """
            Input
            -----
            Change 1
            --------
            Type: fix
            Scope: auth
            Title: Prevent duplicate session refresh
            Description: Multiple refresh requests could occur when several API calls expired simultaneously.
            """;

    private static final String EXAMPLE_INPUT_UPLOAD = """
            Input
            -----
            Change 1
            --------
            Type: feat
            Scope: upload
            Title: Support resumable uploads
            """;

    private static final String DEVELOPER_PROMPT = """
            Audience
            --------
            Software engineers maintaining this project.

            Goal
            ----
            Help another developer quickly understand what changed in this release, without
            reading the underlying commits, PRs, or source code.

            Assume the reader is technically competent — do not explain basic software concepts,
            and keep precise technical terminology intact.

            For each change:
            - State what actually changed and, when known, where (scope).
            - Add enough context that another developer understands the change's purpose without
              opening the commit or PR.
            - Do not invent a reason, impact, or implementation detail beyond what's supplied.

            Format
            ------
            Exactly one bullet per change, in this shape, with no exceptions:
            "- **type(scope)**: Description."
            The bold type(scope) tag and the description are always separated by a colon and a
            space — never run them together (e.g. "fix(auth)Prevented..." is wrong).

            Examples
            --------

            """ + EXAMPLE_INPUT_AUTH + """

            Output
            ------
            - **fix(auth)**: Prevented duplicate session refresh requests when multiple API calls
              expire simultaneously.

            """ + EXAMPLE_INPUT_UPLOAD + """

            Output
            ------
            - **feat(upload)**: Added resumable upload support for large file transfers.
            """;

    private static final String QA_PROMPT = """
            Audience
            --------
            QA engineers preparing manual and regression testing after this deployment.

            Goal
            ----
            Help testers decide what to validate after this release.

            Focus on observable behaviour, not implementation. Ignore purely internal changes —
            refactoring, dependency updates, CI/build changes, documentation, code cleanup —
            unless the supplied information shows they could actually affect testable behaviour.

            For each relevant change:
            - Describe the observable behaviour that changed.
            - State what should be verified.
            - Mention regression risk only when it follows naturally from the change — never
              invent one.

            Format
            ------
            Group bullets under whichever of these headings apply; omit any heading with nothing
            under it:
            ## New Areas to Test
            ## Regression Checks
            ## Performance Checks

            If every supplied change is purely internal (nothing testable changed for a user),
            output exactly:
            _No user-facing changes in this release._

            Examples
            --------

            """ + EXAMPLE_INPUT_AUTH + """

            Output
            ------
            ## Regression Checks
            - Verify only one session refresh occurs when multiple requests receive an
              expired-session response at the same time.

            """ + EXAMPLE_INPUT_UPLOAD + """

            Output
            ------
            ## New Areas to Test
            - Verify an interrupted upload resumes successfully after reconnecting, without
              restarting from the beginning.
            """;

    private static final String BUSINESS_PROMPT = """
            Audience
            --------
            Product owners, project managers, and business stakeholders tracking release progress.

            Goal
            ----
            Help stakeholders understand what was delivered, in plain language.

            Assume the reader has little or no technical background. Avoid technical terminology
            whenever a simpler explanation is possible. Ignore purely internal engineering work —
            refactoring, dependency updates, build/CI changes, documentation, code cleanup —
            unless the supplied information clearly shows a user-visible outcome.

            For each relevant change, describe the outcome for the user — not the implementation.

            Format
            ------
            Group bullets under whichever of these headings apply; omit any heading with nothing
            under it:
            ## What's New
            ## Fixes & Improvements

            If every supplied change is purely internal (nothing user-visible), output exactly:
            _No user-facing changes in this release._

            Examples
            --------

            """ + EXAMPLE_INPUT_AUTH + """

            Output
            ------
            ## Fixes & Improvements
            - Improved session reliability during periods of heavy activity.

            """ + EXAMPLE_INPUT_UPLOAD + """

            Output
            ------
            ## What's New
            - Large file uploads can now continue automatically after a temporary network
              interruption.
            """;

    static final String PROMPT_VERSION_DEVELOPER = "developer-v2";
    static final String PROMPT_VERSION_QA = "qa-v2";
    static final String PROMPT_VERSION_BUSINESS = "business-v2";

    private PromptComposer() {}

    public static PromptRequest compose(
            String project,
            String version,
            List<ReleaseNoteEntry> changes,
            String audience
    ) {
        String promptVersion = promptVersion(audience);
        String userPrompt = renderDocument(project, version, changes);
        String systemPrompt = SYSTEM + "\n" + audiencePrompt(audience);

        return new PromptRequest(promptVersion, systemPrompt, userPrompt);
    }

    private static String audiencePrompt(String audience) {
        return switch (audience) {
            case "qa" -> QA_PROMPT;
            case "business" -> BUSINESS_PROMPT;
            default -> DEVELOPER_PROMPT;
        };
    }

    private static String promptVersion(String audience) {
        return switch (audience) {
            case "qa" -> PROMPT_VERSION_QA;
            case "business" -> PROMPT_VERSION_BUSINESS;
            default -> PROMPT_VERSION_DEVELOPER;
        };
    }

    private static String renderDocument(String project, String version, List<ReleaseNoteEntry> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Release Information\n");
        sb.append("===================\n");
        sb.append("Project: ").append(project != null ? project : "?").append("\n");
        sb.append("Version: ").append(version != null ? version : "?").append("\n");

        if (changes.isEmpty()) {
            sb.append("\nNo changes.\n");
            return sb.toString();
        }

        sb.append("\nChanges\n");
        sb.append("=======\n\n");

        for (int i = 0; i < changes.size(); i++) {
            ReleaseNoteEntry entry = changes.get(i);
            sb.append("Change ").append(i + 1).append("\n");
            sb.append("--------\n");
            if (entry.type() != null) {
                sb.append("Type: ").append(entry.type()).append("\n");
            }
            if (entry.scope() != null) {
                sb.append("Scope: ").append(entry.scope()).append("\n");
            }
            sb.append("Title: ").append(entry.title()).append("\n");
            if (entry.description() != null) {
                sb.append("Description: ").append(entry.description()).append("\n");
            }
            if (entry.prNumber() != null) {
                sb.append("PR: !").append(entry.prNumber()).append("\n");
            }
            if (entry.workItems() != null && !entry.workItems().isEmpty()) {
                sb.append("Work Items: ");
                for (int j = 0; j < entry.workItems().size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append("#").append(entry.workItems().get(j));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
