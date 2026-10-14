package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One changelog version. Field names/dates come from the repo's CHANGELOG.md, but {@code developer}
 * is the best available Developer text — a saved edit or AI generation from Postgres if either
 * exists for this version, otherwise the raw CHANGELOG.md body. See
 * ChangelogCacheService#getBestTextsByVersion. */
public class HistoryEntry {

    private String id;
    private String project;
    private String repo;
    private String branch;
    private String version;
    private List<String> authors;
    private String timestamp;
    private String developer;
    // true for a real generated changelog entry; false for a merged PR surfaced on the history
    // list with no changelog generated yet (see AzureDevOpsResource#buildUngeneratedEntries).
    private boolean generated = true;
    // "raw" (pipeline draft, not yet reviewed), "ai" (AI-generated), "edit" (human-edited), or
    // "import" (straight CHANGELOG.md import) — null when generated is false. See
    // ChangelogCacheService#getCurrentSourcesByVersion.
    private String source;

    public HistoryEntry() {}

    public HistoryEntry(
            String id, String project, String repo, String branch, String version,
            List<String> authors, String timestamp, String developer) {
        this.id = id;
        this.project = project;
        this.repo = repo;
        this.branch = branch;
        this.version = version;
        this.authors = authors;
        this.timestamp = timestamp;
        this.developer = developer;
    }

    /**
     * The id for a versioned history row — shared by BOTH sources of history (the CHANGELOG.md
     * parse and the Postgres tables), so one version keeps one id no matter which source served it.
     *
     * <p>The sources are not independent: the CHANGELOG.md path persists everything it parses
     * (see {@code GitHubResource#history} → {@code saveHistoryEntryIfAbsent}), so the very next
     * call finds rows and takes the Postgres path. An index/date-based id
     * ({@code "repo-9-1.0.9-2026-07-23"} vs {@code "db-9-1.0.9"}) then flips scheme underneath the
     * click that just opened it — the dashboard's list and the detail view's lookup disagree and the
     * row renders as "That version couldn't be found." The version is the only identity a changelog
     * entry actually has, so it (and nothing positional) is what the id is built from.
     */
    public static String versionId(String version) {
        return "v-" + version;
    }

    @JsonProperty("id")
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @JsonProperty("project")
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    @JsonProperty("authors")
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) { this.authors = authors; }

    @JsonProperty("timestamp")
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    /** Best available Developer text for this version — see the class Javadoc. */
    @JsonProperty("developer")
    public String getDeveloper() { return developer; }
    public void setDeveloper(String developer) { this.developer = developer; }

    @JsonProperty("generated")
    public boolean isGenerated() { return generated; }
    public void setGenerated(boolean generated) { this.generated = generated; }

    @JsonProperty("source")
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
