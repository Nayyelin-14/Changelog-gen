package com.hubsabai.changelog.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the leakage actually observed in production output: the model copied
 * few-shot example bullets verbatim into a changelog whose real input had nothing to do with
 * them. The strings below are the exact bullets that shipped.
 */
class ExampleLeakGuardTest {

    // --- exact bullets that leaked into real production output -----------------------------

    /** Developer example, copied into a changelog whose commits were all CI workflow changes. */
    private static final String LEAKED_DEV_AUTH =
            "- **fix(auth)**: Prevented duplicate session refresh requests when multiple API calls expire simultaneously.";
    private static final String LEAKED_DEV_UPLOAD =
            "- **feat(upload)**: Added resumable upload support for large file transfers.";
    /** Business example pair, copied into the "business" audience output. */
    private static final String LEAKED_BUSINESS_AUTH =
            "- Improved session reliability during periods of heavy activity.";
    private static final String LEAKED_BUSINESS_UPLOAD =
            "- Large file uploads can now continue automatically after a temporary network interruption.";
    /** QA example. */
    private static final String LEAKED_QA =
            "- Verify only one session refresh occurs when multiple requests receive an expired-session response at the same time.";

    private static final String REAL_BULLET =
            "- **ci(.github)**: Add weekly GHCR image cleanup workflow.";

    private static ReleaseNoteEntry entry(String title) {
        return new ReleaseNoteEntry("chore", null, title, null, null, null, List.of(), 0, 0);
    }

    private static final List<ReleaseNoteEntry> UNRELATED_INPUT = List.of(
            entry("changelog: add v1.0.16 (via dashboard)"),
            entry("ci: add weekly GHCR image cleanup workflow"));

    @Test
    void dropsTheExactDeveloperLeakThatShipped() {
        String output = REAL_BULLET + "\n" + LEAKED_DEV_AUTH + "\n" + LEAKED_DEV_UPLOAD;

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(output, UNRELATED_INPUT);

        assertTrue(cleaned.startsWith(REAL_BULLET), "real bullet must survive: " + cleaned);
        assertFalse(cleaned.contains("session refresh"), "developer auth leak must be dropped");
        assertFalse(cleaned.contains("resumable upload"), "developer upload leak must be dropped");
    }

    @Test
    void dropsTheExactBusinessLeaksThatShipped() {
        String output = "## What's New\n"
                + LEAKED_BUSINESS_UPLOAD + "\n"
                + "\n"
                + "## Fixes & Improvements\n"
                + LEAKED_BUSINESS_AUTH;

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(output, UNRELATED_INPUT);

        assertFalse(cleaned.contains("Improved session reliability"), "business auth leak dropped");
        assertFalse(cleaned.contains("Large file uploads can now continue"), "business upload leak dropped");
        // Both headings lost their only bullet, so neither may survive as an empty heading.
        assertFalse(cleaned.contains("## What's New"), "orphan heading removed: " + cleaned);
        assertFalse(cleaned.contains("## Fixes & Improvements"), "orphan heading removed: " + cleaned);
    }

    @Test
    void dropsTheQaExampleBullet() {
        String output = "## Regression Checks\n" + LEAKED_QA;

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(output, UNRELATED_INPUT);

        assertFalse(cleaned.contains("session refresh"), "qa leak must be dropped: " + cleaned);
        assertFalse(cleaned.contains("## Regression Checks"), "orphan heading removed: " + cleaned);
    }

    @Test
    void dropsAnExampleBulletTheModelWrappedAcrossLines() {
        String output = REAL_BULLET + "\n"
                + "- **fix(auth)**: Prevented duplicate session refresh requests when multiple API calls\n"
                + "  expire simultaneously.";

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(output, UNRELATED_INPUT);

        assertEquals(REAL_BULLET, cleaned.trim(), "wrapped example must be dropped: " + cleaned);
    }

    // --- the "unless it is supported by the real input" half of the rule ------------------

    @Test
    void keepsTheSameBulletWhenTheInputReallyContainsThatChange() {
        // A release that genuinely contains the example's change: copying is then reporting, not
        // inventing, so the guard must stand aside.
        List<ReleaseNoteEntry> input = List.of(entry("Prevent duplicate session refresh"));

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(LEAKED_DEV_AUTH, input);

        assertEquals(LEAKED_DEV_AUTH, cleaned);
    }

    @Test
    void keepsTheUploadExampleWhenTheInputContainsThatChange() {
        List<ReleaseNoteEntry> input = List.of(entry("Support resumable uploads"));

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(LEAKED_DEV_UPLOAD, input);

        assertEquals(LEAKED_DEV_UPLOAD, cleaned);
    }

    // --- false-positive protection: never delete legitimate work --------------------------

    @Test
    void neverTouchesBulletsThatAreNotExampleCopies() {
        String output = "## Fixes & Improvements\n"
                + "- **fix(auth)**: Rotate the signing key on every deploy.\n"
                + "\n"
                + "## What's New\n"
                + "- Users can now export a release as CSV.";

        assertEquals(output, ExampleLeakGuard.stripUnsupportedExamples(output, UNRELATED_INPUT));
    }

    @Test
    void keepsABulletThatOnlyResemblesAnExample() {
        // Shares type/scope and two words with the auth example, but it is its own change.
        String similar = "- **fix(auth)**: Prevent duplicate session refresh on token reuse.";

        assertEquals(similar, ExampleLeakGuard.stripUnsupportedExamples(similar, UNRELATED_INPUT));
    }

    @Test
    void keepsABulletWhoseTitleMatchesOnlyOneGenericWordOfAnExample() {
        // A two-word title must not certify a bullet on the strength of the word "fix" alone.
        String output = LEAKED_DEV_AUTH;
        List<ReleaseNoteEntry> input = List.of(entry("fix login"));

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(output, input);

        assertFalse(cleaned.contains("session refresh"), "one-word overlap must not rescue the leak");
    }

    // --- pass-through cases -------------------------------------------------------------

    @Test
    void passesThroughPlainTextWithoutBullets() {
        String plain = "_No user-facing changes in this release._";
        assertEquals(plain, ExampleLeakGuard.stripUnsupportedExamples(plain, UNRELATED_INPUT));
    }

    @Test
    void passesThroughNullAndEmpty() {
        assertEquals(null, ExampleLeakGuard.stripUnsupportedExamples(null, UNRELATED_INPUT));
        assertEquals("", ExampleLeakGuard.stripUnsupportedExamples("", UNRELATED_INPUT));
    }

    @Test
    void stripsExampleCopiesWhenTheInputIsEmpty() {
        String output = REAL_BULLET + "\n" + LEAKED_DEV_AUTH;

        String cleaned = ExampleLeakGuard.stripUnsupportedExamples(output, List.of());

        assertEquals(REAL_BULLET, cleaned.trim());
    }

    @Test
    void leavesLineBreaksOfUntouchedOutputIntact() {
        String output = "## What's New\n- Users can now export a release as CSV.\n";

        assertEquals(output, ExampleLeakGuard.stripUnsupportedExamples(output, UNRELATED_INPUT));
    }
}
