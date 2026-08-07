package com.hubsabai.changelog.storage;

import com.hubsabai.changelog.ai.ReleaseNoteEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InputHashTest {

    @Test
    void sameEntriesProduceIdenticalHash() {
        ReleaseNoteEntry a = entry("fix", "auth", "fix login", "session timeout fix", null, null);
        ReleaseNoteEntry b = entry("fix", "auth", "fix login", "session timeout fix", null, null);

        assertEquals(InputHash.of(List.of(a)), InputHash.of(List.of(b)));
    }

    @Test
    void differentTitleProducesDifferentHash() {
        ReleaseNoteEntry a = entry("fix", "auth", "fix login", "session timeout fix", null, null);
        ReleaseNoteEntry b = entry("fix", "auth", "fix logout", "session timeout fix", null, null);

        assertNotEquals(InputHash.of(List.of(a)), InputHash.of(List.of(b)));
    }

    @Test
    void differentScopeProducesDifferentHash() {
        ReleaseNoteEntry a = entry("fix", "auth", "fix login", "session timeout fix", null, null);
        ReleaseNoteEntry b = entry("fix", "billing", "fix login", "session timeout fix", null, null);

        assertNotEquals(InputHash.of(List.of(a)), InputHash.of(List.of(b)));
    }

    @Test
    void differentTypeProducesDifferentHash() {
        ReleaseNoteEntry a = entry("fix", "auth", "fix login", "session timeout fix", null, null);
        ReleaseNoteEntry b = entry("feat", "auth", "fix login", "session timeout fix", null, null);

        assertNotEquals(InputHash.of(List.of(a)), InputHash.of(List.of(b)));
    }

    @Test
    void nullFieldsAreHandled() {
        ReleaseNoteEntry entry = new ReleaseNoteEntry(null, null, null, null, null, null);

        String hash = InputHash.of(List.of(entry));
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void orderOfItemsMatters() {
        ReleaseNoteEntry a = entry("fix", "auth", "fix login", "", null, null);
        ReleaseNoteEntry b = entry("fix", "billing", "fix logout", "", null, null);

        assertNotEquals(InputHash.of(List.of(a, b)), InputHash.of(List.of(b, a)));
    }

    @Test
    void emptyItemsListProducesConsistentHash() {
        assertEquals(InputHash.of(List.of()), InputHash.of(List.of()));
    }

    @Test
    void hashIs64HexCharacters() {
        ReleaseNoteEntry entry = entry("fix", "auth", "fix login", "description", null, null);
        String hash = InputHash.of(List.of(entry));
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    private static ReleaseNoteEntry entry(String type, String scope, String title, String description,
            Integer prNumber, List<String> workItems) {
        return new ReleaseNoteEntry(type, scope, title, description, prNumber, workItems);
    }
}
