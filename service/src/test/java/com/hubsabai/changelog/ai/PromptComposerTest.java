package com.hubsabai.changelog.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptComposerTest {

    private static final ReleaseNoteEntry ENTRY = new ReleaseNoteEntry(
            "feat", "upload", "Support resumable uploads", "Large transfers reconnect seamlessly.",
            12, List.of(), List.of("src/upload/Transfer.java", "src/upload/Chunk.java"), 128, 4);

    @Test
    void rendersTouchedFilesForEachChange() {
        PromptRequest prompt = PromptComposer.compose("test-project", "1.2.0", List.of(ENTRY), "developer");

        assertTrue(prompt.userPrompt().contains("Files: src/upload/Transfer.java, src/upload/Chunk.java"));
    }

    @Test
    void rendersDiffOperationsWhenKnown() {
        PromptRequest prompt = PromptComposer.compose("test-project", "1.2.0", List.of(ENTRY), "developer");

        assertTrue(prompt.userPrompt().contains("Diff: +128/-4"));
    }

    @Test
    void skipsFilesAndDiffWhenAbsent() {
        ReleaseNoteEntry bare = new ReleaseNoteEntry(
                "chore", null, "Bump deps", null, null, null, List.of(), 0, 0);

        PromptRequest prompt = PromptComposer.compose("test-project", "1.2.0", List.of(bare), "developer");

        assertTrue(prompt.userPrompt().contains("Title: Bump deps"));
        assertTrue(!prompt.userPrompt().contains("Files:"));
        assertTrue(!prompt.userPrompt().contains("Diff:"));
    }

    // Regression: the system prompt used to present examples with nothing separating them from
    // the data, and small models pasted those example bullets into real releases. The prompt now
    // states the boundary explicitly — if that text disappears, the guard has lost its first
    // line of defence.

    @Test
    void systemPromptDrawsAnExplicitLineBetweenInstructionsAndData() {
        PromptRequest prompt = PromptComposer.compose("test-project", "1.2.0", List.of(ENTRY), "developer");
        String system = prompt.systemPrompt();

        assertTrue(system.contains("not part of the"),
                "examples must be declared as instructions, not data:\n" + system);
        assertTrue(system.contains("Never copy example output text"),
                "verbatim copying of example output must be forbidden:\n" + system);
        assertTrue(system.contains("exactly one bullet per change that actually appears"),
                "one-bullet-per-real-change must be stated:\n" + system);
    }

    @Test
    void examplesAreLabelledAsFormatDemonstrationsOnly() {
        PromptRequest prompt = PromptComposer.compose("test-project", "1.2.0", List.of(ENTRY), "developer");

        assertTrue(prompt.systemPrompt().contains("Format demonstrations only"),
                "the Examples block must be labelled:\n" + prompt.systemPrompt());
    }

    @Test
    void everyAudiencePromptRendersTheExactExampleOutputsTheGuardHuntsFor() {
        // Single source of truth: what the prompt shows is what ExampleLeakGuard scans for. If a
        // prompt edit ever stops embedding these strings, this fails before a leak can ship.
        var byAudience = Map.of(
                "developer", List.of(PromptComposer.EXAMPLE_OUT_DEV_AUTH, PromptComposer.EXAMPLE_OUT_DEV_UPLOAD),
                "qa", List.of(PromptComposer.EXAMPLE_OUT_QA_AUTH, PromptComposer.EXAMPLE_OUT_QA_UPLOAD),
                "business", List.of(PromptComposer.EXAMPLE_OUT_BUSINESS_AUTH,
                        PromptComposer.EXAMPLE_OUT_BUSINESS_UPLOAD));
        assertEquals(PromptComposer.EXAMPLE_OUTPUTS.size(), 6, "six example bullets expected");

        for (var expected : byAudience.entrySet()) {
            PromptRequest prompt = PromptComposer.compose("test-project", "1.2.0", List.of(ENTRY), expected.getKey());
            for (String example : expected.getValue()) {
                assertTrue(prompt.systemPrompt().contains(example),
                        "audience '" + expected.getKey() + "' must render example output: " + example);
            }
        }
    }

    @Test
    void promptVersionIsBumpedWhenThePromptTextChanges() {
        assertEquals("developer-v3", PromptComposer.compose("p", "1", List.of(ENTRY), "developer").promptVersion());
        assertEquals("qa-v3", PromptComposer.compose("p", "1", List.of(ENTRY), "qa").promptVersion());
        assertEquals("business-v3", PromptComposer.compose("p", "1", List.of(ENTRY), "business").promptVersion());
    }
}