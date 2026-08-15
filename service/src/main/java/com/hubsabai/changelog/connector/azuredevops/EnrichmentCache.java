package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.GitRef;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Centralized caching for Azure DevOps enrichment data.
 * - Tags/file-history: short TTL (new tags can appear)
 * - Individual commits: immutable, cached without expiry but bounded LRU
 * - File paths per commit: immutable, bounded LRU
 * - Changelog files: short TTL for read paths, never for push
 */
@ApplicationScoped
public class EnrichmentCache {

    private static final Duration ENRICHMENT_CACHE_TTL = Duration.ofMinutes(5);

    // ---- Tags cache ----
    public record TagsCacheEntry(List<GitRef> tags, long fetchedAtMillis) {
        boolean expired() { return System.currentTimeMillis() - fetchedAtMillis > ENRICHMENT_CACHE_TTL.toMillis(); }
    }
    private final Map<String, TagsCacheEntry> tagsCache = new ConcurrentHashMap<>();

    public List<GitRef> getTags(String project, String repo) {
        String key = project + "/" + repo;
        TagsCacheEntry cached = tagsCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.tags();
        }
        return null;
    }

    public void putTags(String project, String repo, List<GitRef> tags) {
        String key = project + "/" + repo;
        tagsCache.put(key, new TagsCacheEntry(tags, System.currentTimeMillis()));
    }

    // ---- File history cache ----
    public record FileHistoryCacheEntry(List<CommitResponse> commits, long fetchedAtMillis) {
        boolean expired() { return System.currentTimeMillis() - fetchedAtMillis > ENRICHMENT_CACHE_TTL.toMillis(); }
    }
    private final Map<String, FileHistoryCacheEntry> fileHistoryCache = new ConcurrentHashMap<>();

    public List<CommitResponse> getFileHistory(String project, String repo, String path) {
        String key = project + "/" + repo + path;
        FileHistoryCacheEntry cached = fileHistoryCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.commits();
        }
        return null;
    }

    public List<CommitResponse> getFileHistory(String key) {
        FileHistoryCacheEntry cached = fileHistoryCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.commits();
        }
        return null;
    }

    public void putFileHistory(String project, String repo, String path, List<CommitResponse> commits) {
        String key = project + "/" + repo + path;
        fileHistoryCache.put(key, new FileHistoryCacheEntry(commits, System.currentTimeMillis()));
    }

    // ---- Commit metadata cache (bounded LRU) ----
    private static final int COMMIT_CACHE_MAX_ENTRIES = 5000;
    private final Map<String, CommitResponse> commitCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CommitResponse> eldest) {
                    return size() > COMMIT_CACHE_MAX_ENTRIES;
                }
            });

    public CommitResponse getCommit(String project, String repo, String commitId) {
        return commitCache.get(project + "/" + repo + "/" + commitId);
    }

    public void putCommit(String project, String repo, String commitId, CommitResponse commit) {
        commitCache.put(project + "/" + repo + "/" + commitId, commit);
    }

    public CommitResponse getCommitCached(String project, String repo, String commitId) {
        return getCommit(project, repo, commitId);
    }

    // ---- Commit file paths cache (bounded LRU) ----
    private static final int FILE_PATHS_CACHE_MAX_ENTRIES = 5000;
    private final Map<String, List<String>> commitFilePathsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > FILE_PATHS_CACHE_MAX_ENTRIES;
                }
            });

    public List<String> getCommitFilePaths(String project, String repo, String commitId) {
        return commitFilePathsCache.get(project + "/" + repo + "/" + commitId);
    }

    public void putCommitFilePaths(String project, String repo, String commitId, List<String> paths) {
        commitFilePathsCache.put(project + "/" + repo + "/" + commitId, paths);
    }

    // ---- Changelog file cache (short TTL) ----
    public record ChangelogFileCacheEntry(ChangelogFile file, long fetchedAtMillis) {
        boolean expired() { return System.currentTimeMillis() - fetchedAtMillis > ENRICHMENT_CACHE_TTL.toMillis(); }
    }
    private final Map<String, ChangelogFileCacheEntry> changelogFileCache = new ConcurrentHashMap<>();

    public ChangelogFile getChangelogFile(String project, String repo, String branch) {
        String key = project + "/" + repo + "/" + (branch != null ? branch : "");
        ChangelogFileCacheEntry cached = changelogFileCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.file();
        }
        return null;
    }

    public void putChangelogFile(String project, String repo, String branch, ChangelogFile file) {
        String key = project + "/" + repo + "/" + (branch != null ? branch : "");
        changelogFileCache.put(key, new ChangelogFileCacheEntry(file, System.currentTimeMillis()));
    }

    // ---- Changelog entry records ----
    public record ChangelogEntry(String version, String date, String body) {}
    public record ChangelogFile(String filename, String content) {}
}