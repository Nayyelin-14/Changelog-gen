package com.hubsabai.changelog.ai;

import com.hubsabai.changelog.core.model.ChangeItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Duplicate collapse. The duplicates seen in real output came from the INPUT, not the model:
 * rebased/cherry-picked history carries the same change under two SHAs, so the compare range
 * hands us both and the model faithfully writes two identical bullets. Dedup therefore belongs
 * on the way into the prompt — and it must be narrow enough never to swallow a distinct change.
 */
class ReleaseNotePreparerTest {

    private static ChangeItem item(String title, String description, String author, List<String> files) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setTitle(title);
        item.setDescription(description);
        item.setAuthor(author);
        item.setFilePaths(files);
        item.setCategory("ci");
        return item;
    }

    /** The real pair observed in the 1.0.15 → 1.0.16 range: same message, same author, same file,
     *  two different SHAs and committer timestamps. */
    private static ChangeItem cherryPicked(String sha, String date) {
        ChangeItem item = item("ci: add weekly GHCR image cleanup workflow",
                "ci: add weekly GHCR image cleanup workflow",
                "Nayyelin-14", List.of(".github/workflows/cleanup-ghcr.yml"));
        item.setId(sha);
        item.setDate(date);
        return item;
    }

    @Test
    void collapsesTheIdenticalCherryPickedPairObservedInProduction() {
        List<ChangeItem> items = List.of(
                cherryPicked("82dcadc4", "2026-09-20T06:49:22Z"),
                cherryPicked("43641b60", "2026-09-20T06:48:57Z"));

        List<ReleaseNoteEntry> entries = ReleaseNotePreparer.prepare(items);

        assertEquals(1, entries.size());
        assertEquals("ci: add weekly GHCR image cleanup workflow", entries.get(0).title());
    }

    @Test
    void collapsesDuplicatesRegardlessOfFileOrder() {
        ChangeItem first = item("chore: release v1.0.15", null, "github-actions[bot]",
                List.of("CHANGELOG.md", "service/pom.xml"));
        ChangeItem second = item("chore: release v1.0.15", null, "github-actions[bot]",
                List.of("service/pom.xml", "CHANGELOG.md"));

        assertEquals(1, ReleaseNotePreparer.prepare(List.of(first, second)).size());
    }

    @Test
    void keepsCommitsThatTouchDifferentFiles() {
        ChangeItem one = item("fix typo", null, "nay", List.of("README.md"));
        ChangeItem two = item("fix typo", null, "nay", List.of("docs/INSTALL.md"));

        assertEquals(2, ReleaseNotePreparer.prepare(List.of(one, two)).size());
    }

    @Test
    void keepsCommitsByDifferentAuthors() {
        ChangeItem one = item("Bump dependencies", null, "renovate[bot]", List.of("pom.xml"));
        ChangeItem two = item("Bump dependencies", null, "nay", List.of("pom.xml"));

        assertEquals(2, ReleaseNotePreparer.prepare(List.of(one, two)).size());
    }

    @Test
    void keepsCommitsWithDifferentWording() {
        ChangeItem one = item("ci: add weekly GHCR image cleanup workflow", null, "nay",
                List.of(".github/workflows/cleanup.yml"));
        ChangeItem two = item("ci: add daily GHCR image cleanup workflow", null, "nay",
                List.of(".github/workflows/cleanup.yml"));

        assertEquals(2, ReleaseNotePreparer.prepare(List.of(one, two)).size());
    }

    @Test
    void keepsCommitsWithDifferentDescriptions() {
        ChangeItem one = item("Update config", "Switch to the new endpoint", "nay", List.of("app.conf"));
        ChangeItem two = item("Update config", "Switch to the new auth flow", "nay", List.of("app.conf"));

        assertEquals(2, ReleaseNotePreparer.prepare(List.of(one, two)).size());
    }

    @Test
    void neverCollapsesItemsWithNoFileData() {
        // Without touched files two identically-titled commits could still be different work —
        // refusing to guess is the whole point of the narrow rule.
        ChangeItem one = item("fix typo", null, "nay", List.of());
        ChangeItem two = item("fix typo", null, "nay", List.of());

        assertEquals(2, ReleaseNotePreparer.prepare(List.of(one, two)).size());
    }

    @Test
    void neverCollapsesVeryShortTitles() {
        ChangeItem one = item("wip", null, "nay", List.of("a.java"));
        ChangeItem two = item("wip", null, "nay", List.of("a.java"));

        assertEquals(2, ReleaseNotePreparer.prepare(List.of(one, two)).size());
    }

    @Test
    void preservesFirstOccurrenceOrder() {
        ChangeItem a = item("change alpha", null, "nay", List.of("a.txt"));
        ChangeItem b = item("change beta", null, "nay", List.of("b.txt"));
        ChangeItem aAgain = item("Change   Alpha", null, "NAY", List.of("a.txt"));

        List<ReleaseNoteEntry> entries = ReleaseNotePreparer.prepare(List.of(a, b, aAgain));

        assertEquals(2, entries.size());
        assertEquals("change alpha", entries.get(0).title());
        assertEquals("change beta", entries.get(1).title());
    }

    @Test
    void collapsesNothingWhenEveryItemIsDistinct() {
        List<ChangeItem> items = List.of(
                item("feat: add CSV export", null, "nay", List.of("src/export/Csv.java")),
                item("fix: correct rounding", null, "nay", List.of("src/math/Round.java")),
                item("docs: document the API", null, "nay", List.of("docs/api.md")));

        assertEquals(3, ReleaseNotePreparer.prepare(items).size());
    }
}
