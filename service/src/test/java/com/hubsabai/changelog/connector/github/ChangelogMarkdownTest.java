package com.hubsabai.changelog.connector.github;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogMarkdownTest {

    private static final String CHANGELOG = """
            # Releases

            ## v1.2.0 — 2026-10-20

            - added the thing
            - fixed the other thing

            ## v1.1.0 — 2026-10-01

            - older change
            """;

    @Test
    void findsTheBodyForAnExistingVersion() {
        Optional<String> body = ChangelogMarkdown.findChangelogEntryBody(CHANGELOG, "1.2.0");

        assertTrue(body.isPresent());
        assertEquals("- added the thing\n- fixed the other thing", body.get());
    }

    @Test
    void returnsEmptyForAVersionThatIsNotPresent() {
        assertFalse(ChangelogMarkdown.findChangelogEntryBody(CHANGELOG, "9.9.9").isPresent());
    }

    @Test
    void findsTheFirstEntryWhenTheSameVersionAppearsTwice() {
        String duplicated = """
                ## v1.0.0 — 2026-10-20

                - newest duplicate

                ## v1.0.0 — 2026-10-01

                - older duplicate
                """;

        assertEquals("- newest duplicate",
                ChangelogMarkdown.findChangelogEntryBody(duplicated, "1.0.0").orElseThrow());
    }

    @Test
    void normalizesLineEndingsAndSurroundingWhitespaceForComparison() {
        assertEquals("same text", ChangelogMarkdown.normalizeEntryBody("\r\n  same text \r\n"));
        assertEquals(ChangelogMarkdown.normalizeEntryBody("a\nb"),
                ChangelogMarkdown.normalizeEntryBody("a\r\nb\r"));
    }
}
