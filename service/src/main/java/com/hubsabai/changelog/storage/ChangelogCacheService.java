package com.hubsabai.changelog.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChangelogCacheService {

    /**
     * The current text if it's still valid to reuse without a fresh AI call: a human edit always
     * qualifies (it's a deliberate override, not something a changed input hash should discard),
     * an AI generation only qualifies if the commits it was based on still match {@code inputHash}.
     * Empty otherwise, meaning the caller should generate.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> getCurrent(String project, String repo, String version, String audience, String inputHash) {
        return getCurrent("azure", project, repo, version, audience, inputHash);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> getCurrent(String provider, String project, String repo, String version, String audience, String inputHash) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        if (entry == null) {
            return Optional.empty();
        }
        if ("edit".equals(entry.currentSource) || inputHash.equals(entry.currentInputHash)) {
            return Optional.of(entry.currentText);
        }
        return Optional.empty();
    }

    /** Only a human-made edit, never an AI generation — used where an edit must be distinguished
     * from a generation (e.g. deciding whether a downstream view already carries its own override
     * and so must be left alone by a cascade). */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> getEditedText(String project, String repo, String version, String audience) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry("azure", project, repo, version, audience);
        return entry != null && "edit".equals(entry.currentSource) ? Optional.of(entry.currentText) : Optional.empty();
    }

    /** Whatever's currently shown for this version+audience, with no staleness check at all —
     * used by read paths (like /history) that must never trigger an Azure DevOps round-trip or an
     * AI call just to render a list. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> getCurrentText(String project, String repo, String version, String audience) {
        return getCurrentText("azure", project, repo, version, audience);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> getCurrentText(String provider, String project, String repo, String version, String audience) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        // Optional.of would NPE if the row exists but currentText is null (e.g. every revision for
        // this version+audience has been deleted) — a real, reachable state, not a bug elsewhere.
        return entry != null ? Optional.ofNullable(entry.currentText) : Optional.empty();
    }

    /** The raw entry backing {@link #getCurrentText}, for callers that need more than the text —
     * e.g. whether the current text is an AI generation or a human edit, and by/with what. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<GeneratedChangelog> getCurrentEntry(String project, String repo, String version, String audience) {
        return Optional.ofNullable(GeneratedChangelog.findEntry("azure", project, repo, version, audience));
    }

    public Optional<GeneratedChangelog> getCurrentEntry(String provider, String project, String repo, String version, String audience) {
        return Optional.ofNullable(GeneratedChangelog.findEntry(provider, project, repo, version, audience));
    }

    /** Same as {@link #getCurrentText}, batched over every version of one project/repo/audience —
     * one query instead of one per version. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String, String> getCurrentTextsByVersion(String project, String repo, String audience) {
        return getCurrentTextsByVersion("azure", project, repo, audience);
    }

    public Map<String, String> getCurrentTextsByVersion(String provider, String project, String repo, String audience) {
        List<GeneratedChangelog> entries = GeneratedChangelog.findAllForAudience(provider, project, repo, audience);
        return entries.stream().collect(Collectors.toMap(e -> e.version, e -> e.currentText, (a, b) -> a));
    }

    /** Same batching as {@link #getCurrentTextsByVersion}, but {@code currentSource} instead of
     * text — lets the history list show whether a version is still a raw pipeline draft ("raw"),
     * AI-generated ("ai"), human-edited ("edit"), or a straight CHANGELOG.md import ("import"). */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String, String> getCurrentSourcesByVersion(String project, String repo, String audience) {
        return getCurrentSourcesByVersion("azure", project, repo, audience);
    }

    public Map<String, String> getCurrentSourcesByVersion(String provider, String project, String repo, String audience) {
        List<GeneratedChangelog> entries = GeneratedChangelog.findAllForAudience(provider, project, repo, audience);
        return entries.stream().collect(Collectors.toMap(e -> e.version, e -> e.currentSource, (a, b) -> a));
    }

    /** Every version this project/repo/audience has a row for, DB-native — independent of
     * whatever CHANGELOG.md happens to contain on whichever branch is currently selected. This is
     * what lets /history show a version that a pipeline reported via /api/pipeline/generate even
     * when the repo never committed (or committed on a different branch than the one being
     * viewed) a matching CHANGELOG.md entry. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GeneratedChangelog> getDeveloperEntries(String project, String repo) {
        return getDeveloperEntries("azure", project, repo);
    }

    public List<GeneratedChangelog> getDeveloperEntries(String provider, String project, String repo) {
        return GeneratedChangelog.findAllForAudience(provider, project, repo, "developer");
    }

    /** Saves a human edit as the new current text, rolling whatever was current into history. */
    @Transactional
    public void saveEdit(String project, String repo, String version, String audience, String editedText, String editedBy) {
        setCurrent("azure", project, repo, version, audience, "edit", null, null, editedBy, editedText);
    }

    public void saveEdit(String provider, String project, String repo, String version, String audience, String editedText, String editedBy) {
        setCurrent(provider, project, repo, version, audience, "edit", null, null, editedBy, editedText);
    }

    /** Saves a fresh AI generation as the new current text, rolling whatever was current — AI or
     * a human edit alike — into history. A regeneration always wins; it is a deliberate action,
     * just like an edit. */
    @Transactional
    public void put(String project, String repo, String version, String audience,
            String modelId, String generatedText, String inputHash) {
        put("azure", project, repo, version, audience, modelId, generatedText, inputHash);
    }

    @Transactional
    public void put(String provider, String project, String repo, String version, String audience,
            String modelId, String generatedText, String inputHash) {
        setCurrent(provider, project, repo, version, audience, "ai", modelId, inputHash, null, generatedText);
    }

    /**
     * Saves a raw pipeline draft only if no existing AI/edit/import entry exists — pipeline
     * retries are safe to repeat without clobbering deliberate content.
     *
     * @return true if written, false if existing ai/edit/import entry was left alone
     */
    @Transactional
    public boolean saveRawInitIfAbsent(String project, String repo, String version, String audience, String rawText) {
        return saveRawInitIfAbsent("azure", project, repo, version, audience, rawText);
    }

    @Transactional
    public boolean saveRawInitIfAbsent(String provider, String project, String repo, String version, String audience, String rawText) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        if (entry != null && ("ai".equals(entry.currentSource) || "edit".equals(entry.currentSource) || "import".equals(entry.currentSource))) {
            return false;
        }
        setCurrent(provider, project, repo, version, audience, "raw", null, null, null, rawText);
        return true;
    }

    /**
     * Swaps the previous text back to current using that row's own metadata (source/model/hash),
     * so restored AI stays attributed to the original model. Reversible: restoring twice flips
     * back (current becomes new previous, just like any other write).
     *
     * @return the restored text, or empty if no previous entry exists
     */
    @Transactional
    public Optional<String> restorePrevious(String project, String repo, String version, String audience) {
        return restorePrevious("azure", project, repo, version, audience);
    }

    @Transactional
    public Optional<String> restorePrevious(String provider, String project, String repo, String version, String audience) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        if (entry == null || entry.previousText == null) {
            return Optional.empty();
        }
        String restoredText = entry.previousText;
        setCurrent(provider, project, repo, version, audience,
                entry.previousSource, entry.previousModelId, entry.previousInputHash, entry.previousEditedBy, restoredText);
        return Optional.of(restoredText);
    }

    /** Records a successful push. The row must already exist (can't push without current text).
     * {@code pushed_pull_request_url} is a legacy column name (push used to open a PR) — it now
     * holds a direct-commit link because Azure DevOps pushes use a direct commit; the GitHub push
     * flow opens a PR instead, so it also holds the PR link. */
    @Transactional
    public void markPushed(String project, String repo, String version, String audience, String text, String commitUrl) {
        markPushed("azure", project, repo, version, audience, text, commitUrl);
    }

    @Transactional
    public void markPushed(String provider, String project, String repo, String version, String audience, String text, String commitUrl) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        if (entry != null) {
            entry.pushedText = text;
            entry.pushedAt = OffsetDateTime.now();
            entry.pushedPullRequestUrl = commitUrl;
            entry.persist();
        }
    }

    /**
     * Makes an arbitrary past revision current again — the general form of {@link
     * #restorePrevious} (always exactly one step back) and {@link #restoreToPushed} (always the
     * last push): rolls back to whichever specific revision the caller picked from the full edit
     * history, preserving that revision's own source/model/editedBy attribution the same way
     * {@link #restorePrevious} does. {@code inputHash} is deliberately left null — a revision
     * picked from history has no live item list to hash against, and {@code getCurrent}'s
     * hash-freshness check is skipped entirely for {@code source == "edit"} anyway; for a
     * restored "ai" revision, this only means a later preview won't treat it as a cache hit and
     * will regenerate instead, never a correctness problem (same tradeoff already accepted in
     * {@code generate-commit}, see {@code AzureDevOpsResource#generateCommit}).
     */
    @Transactional
    public void restoreToRevision(String project, String repo, String version, String audience,
            String source, String modelId, String editedBy, String text) {
        setCurrent("azure", project, repo, version, audience, source, modelId, null, editedBy, text);
    }

    public void restoreToRevision(String provider, String project, String repo, String version, String audience,
            String source, String modelId, String editedBy, String text) {
        setCurrent(provider, project, repo, version, audience, source, modelId, null, editedBy, text);
    }

    /**
     * Rolls back to the last pushed text (distinct from {@link #restorePrevious} which undoes the
     * last edit/regeneration). Uses the same shift mechanism, so this is itself undoable via the
     * ordinary "Restore previous" action.
     *
     * @return the restored text, or empty if nothing has been pushed yet
     */
    @Transactional
    public Optional<String> restoreToPushed(String project, String repo, String version, String audience) {
        return restoreToPushed("azure", project, repo, version, audience);
    }

    @Transactional
    public Optional<String> restoreToPushed(String provider, String project, String repo, String version, String audience) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        if (entry == null || entry.pushedText == null) {
            return Optional.empty();
        }
        setCurrent(provider, project, repo, version, audience, "edit", null, null, null, entry.pushedText);
        return Optional.of(entry.pushedText);
    }

    /**
     * Saves the raw CHANGELOG.md body for a version as the developer text — but only if nothing
     * more deliberate already exists (ai, edit, raw, or a prior import). Called by the history
     * endpoint on its first load for a project/repo to seed the DB cache so subsequent loads
     * can skip the Azure DevOps API call entirely.
     *
     * @param versionDate the version's date from CHANGELOG.md (e.g. "2026-06-24"), stored in
     *                    current_at so the DB cache path returns the correct version timestamp
     */
    @Transactional
    public void saveHistoryEntryIfAbsent(String project, String repo, String version, String versionDate, String body) {
        saveHistoryEntryIfAbsent("azure", project, repo, version, versionDate, body);
    }

    @Transactional
    public void saveHistoryEntryIfAbsent(String provider, String project, String repo, String version, String versionDate, String body) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, "developer");
        if (entry != null) {
            return;
        }
        OffsetDateTime date = parseVersionDate(versionDate);

        // Old table
        GeneratedChangelog gc = new GeneratedChangelog();
        gc.provider = provider;
        gc.project = project;
        gc.repo = repo;
        gc.version = version;
        gc.audience = "developer";
        gc.currentText = body;
        gc.currentSource = "import";
        gc.currentAt = date;
        gc.persist();

        // New tables
        ChangelogVersion cv = ChangelogVersion.findEntry(provider, project, repo, version);
        if (cv == null) {
            cv = new ChangelogVersion();
            cv.provider = provider;
            cv.project = project;
            cv.repo = repo;
            cv.version = version;
            cv.createdAt = date;
            cv.persist();
        }

        ChangelogRevision rev = ChangelogRevision.latest(cv.id, "developer");
        if (rev == null) {
            rev = new ChangelogRevision();
            rev.version = cv;
            rev.audience = "developer";
            rev.sequence = 0;
            rev.text = body;
            rev.source = "import";
            rev.createdAt = date;
            rev.persist();
        }
    }

    /** Converts a CHANGELOG.md date like "2026-06-24" to an OffsetDateTime for storing in current_at. */
    private static OffsetDateTime parseVersionDate(String date) {
        if (date == null) return OffsetDateTime.now();
        String trimmed = date.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return OffsetDateTime.parse(trimmed + "T00:00:00Z");
        }
        return OffsetDateTime.now();
    }

    /**
     * Upsert: find existing entry and shift current→previous, or create new. Not atomic (two
     * round-trips) but the unique constraint catches races — the loser's commit rolls back.
     */
    @Transactional
    void setCurrent(String project, String repo, String version, String audience,
            String source, String modelId, String inputHash, String editedBy, String text) {
        setCurrent("azure", project, repo, version, audience, source, modelId, inputHash, editedBy, text);
    }

    /**
     * Upsert: find existing entry and shift current→previous, or create new. Not atomic (two
     * round-trips) but the unique constraint catches races — the loser's commit rolls back.
     */
    @Transactional
    void setCurrent(String provider, String project, String repo, String version, String audience,
            String source, String modelId, String inputHash, String editedBy, String text) {
        GeneratedChangelog entry = GeneratedChangelog.findEntry(provider, project, repo, version, audience);
        if (entry != null) {
            entry.previousText = entry.currentText;
            entry.previousSource = entry.currentSource;
            entry.previousModelId = entry.currentModelId;
            entry.previousInputHash = entry.currentInputHash;
            entry.previousEditedBy = entry.currentEditedBy;
            entry.previousAt = entry.currentAt;
            entry.currentText = text;
            entry.currentSource = source;
            entry.currentModelId = modelId;
            entry.currentInputHash = inputHash;
            entry.currentEditedBy = editedBy;
            entry.currentAt = OffsetDateTime.now();
            entry.persist();
        } else {
            GeneratedChangelog gc = new GeneratedChangelog();
            gc.provider = provider;
            gc.project = project;
            gc.repo = repo;
            gc.version = version;
            gc.audience = audience;
            gc.currentText = text;
            gc.currentSource = source;
            gc.currentModelId = modelId;
            gc.currentInputHash = inputHash;
            gc.currentEditedBy = editedBy;
            gc.currentAt = OffsetDateTime.now();
            gc.persist();
        }
    }
}
