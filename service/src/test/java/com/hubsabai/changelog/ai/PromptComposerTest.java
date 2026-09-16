package com.hubsabai.changelog.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}