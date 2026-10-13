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

            The only data you may describe is the "Changes" document in the user message.

            The Examples section further below is part of these instructions, not part of the
            data. Its example changes and example outputs exist only to show the required shape
            of an answer — they are NOT changes in this release.
            - Emit exactly one bullet per change that actually appears in the Changes document,
              no more and no fewer.
            - Never emit a bullet about a change that is absent from that document, even when it
              looks similar to an example.
            - Never copy example output text into your answer unless that very same change is
              really present in the Changes document.

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

    // Single source of truth for the example BULLETS. The audience prompts below render exactly
    // these strings, and ExampleLeakGuard scans generated text for exactly these strings — a
    // prompt edit can therefore never leave the guard hunting for text that is no longer (or no
    // longer only) in the prompt. Kept on one line on purpose: the guard normalizes whitespace,
    // but a copy that is byte-identical to what the model was shown is the case it must catch.
    static final String EXAMPLE_OUT_DEV_AUTH =
            "- **fix(auth)**: Prevented duplicate session refresh requests when multiple API calls expire simultaneously.";
    static final String EXAMPLE_OUT_DEV_UPLOAD =
            "- **feat(upload)**: Added resumable upload support for large file transfers.";
    static final String EXAMPLE_OUT_QA_AUTH =
            "- Verify only one session refresh occurs when multiple requests receive an expired-session response at the same time.";
    static final String EXAMPLE_OUT_QA_UPLOAD =
            "- Verify an interrupted upload resumes successfully after reconnecting, without restarting from the beginning.";
    static final String EXAMPLE_OUT_BUSINESS_AUTH =
            "- Improved session reliability during periods of heavy activity.";
    static final String EXAMPLE_OUT_BUSINESS_UPLOAD =
            "- Large file uploads can now continue automatically after a temporary network interruption.";

    /** Every example bullet the prompts show, for {@link ExampleLeakGuard}. Headings that sit
     *  above a bullet in the examples are generic formatting and deliberately not listed — they
     *  are legitimate in real output. */
    static final List<String> EXAMPLE_OUTPUTS = List.of(
            EXAMPLE_OUT_DEV_AUTH, EXAMPLE_OUT_DEV_UPLOAD,
            EXAMPLE_OUT_QA_AUTH, EXAMPLE_OUT_QA_UPLOAD,
            EXAMPLE_OUT_BUSINESS_AUTH, EXAMPLE_OUT_BUSINESS_UPLOAD);

    private static final String EXAMPLES_CAVEAT = """
            Examples
            --------
            Format demonstrations only. The changes shown in these examples are NOT part of the
            release being written, and their outputs must not be repeated in your answer unless
            the real Changes document contains the very same change.
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

            """ + EXAMPLES_CAVEAT + """

            """ + EXAMPLE_INPUT_AUTH + """

            Output
            ------
            """ + EXAMPLE_OUT_DEV_AUTH + """

            """ + EXAMPLE_INPUT_UPLOAD + """

            Output
            ------
            """ + EXAMPLE_OUT_DEV_UPLOAD + """
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

            """ + EXAMPLES_CAVEAT + """

            """ + EXAMPLE_INPUT_AUTH + """

            Output
            ------
            ## Regression Checks
            """ + EXAMPLE_OUT_QA_AUTH + """

            """ + EXAMPLE_INPUT_UPLOAD + """

            Output
            ------
            ## New Areas to Test
            """ + EXAMPLE_OUT_QA_UPLOAD + """
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

            """ + EXAMPLES_CAVEAT + """

            """ + EXAMPLE_INPUT_AUTH + """

            Output
            ------
            ## Fixes & Improvements
            """ + EXAMPLE_OUT_BUSINESS_AUTH + """

            """ + EXAMPLE_INPUT_UPLOAD + """

            Output
            ------
            ## What's New
            """ + EXAMPLE_OUT_BUSINESS_UPLOAD + """
            """;

    static final String PROMPT_VERSION_DEVELOPER = "developer-v3";
    static final String PROMPT_VERSION_QA = "qa-v3";
    static final String PROMPT_VERSION_BUSINESS = "business-v3";

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
            if (entry.filePaths() != null && !entry.filePaths().isEmpty()) {
                sb.append("Files: ").append(String.join(", ", entry.filePaths())).append("\n");
            }
            if (entry.additions() > 0 || entry.deletions() > 0) {
                sb.append("Diff: +").append(entry.additions()).append("/-").append(entry.deletions()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
