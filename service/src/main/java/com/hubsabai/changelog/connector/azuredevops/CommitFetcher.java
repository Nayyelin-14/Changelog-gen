package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitChangesResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;
import com.hubsabai.changelog.core.model.ChangeItem;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Fetches commits by range, by path, and converts to ChangeItems.
 */
@ApplicationScoped
public class CommitFetcher {

    private static final int COMMIT_PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final int RANGE_SCAN_PAGES = 5;

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    EnrichmentCache enrichmentCache;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    private static final Logger LOG = Logger.getLogger(CommitFetcher.class.getName());

    /**
     * Resolves the branch tip (HEAD commit SHA) for a given branch.
     */
    public String resolveBranchHead(String project, String repo, String branch) {
        AzureDevOpsListResponse<CommitResponse> resp = client.listCommitsByRange(
                org, project, repo, 1, 0, null, null, branch, null, AzureDevOpsRestClient.API_VERSION);
        List<CommitResponse> commits = resp.valueOrEmpty();
        return commits.isEmpty() ? null : commits.get(0).commitId();
    }

    /**
     * Fetches commits in a range and converts to ChangeItems with file paths.
     */
    public List<ChangeItem> fetchCommitsByRange(String project, String repo, String fromCommitId, String toCommitId, String branch) {
        List<CommitResponse> raw = fetchCommitsUpTo(project, repo, branch, toCommitId, fromCommitId);
        List<ChangeItem> items = new ArrayList<>(raw.size());
        for (CommitResponse c : raw) {
            items.add(toChangeItem(project, repo, c));
        }
        // Fetch file-level changes for each commit
        for (ChangeItem item : items) {
            if (item.getId() != null) {
                item.setFilePaths(fetchCommitFilePaths(project, repo, item.getId()));
            }
        }
        return items;
    }

    /**
     * Walks backward from {@code toCommitId} until {@code lowerBoundaryCommitId} (exclusive)
     * or {@link #RANGE_SCAN_PAGES} pages.
     */
    public List<CommitResponse> fetchCommitsUpTo(String project, String repo, String branch, String toCommitId, String lowerBoundaryCommitId) {
        List<CommitResponse> commits = new ArrayList<>();
        int skip = 0;
        for (int page = 0; page < RANGE_SCAN_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommitsByRange(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, null, toCommitId, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> pageCommits = response.valueOrEmpty();
            if (pageCommits.isEmpty()) break;
            for (CommitResponse c : pageCommits) {
                if (lowerBoundaryCommitId != null && lowerBoundaryCommitId.equals(c.commitId())) {
                    return commits;
                }
                commits.add(c);
            }
            skip += pageCommits.size();
            if (pageCommits.size() < COMMIT_PAGE_SIZE) break;
            if (page == RANGE_SCAN_PAGES - 1 && lowerBoundaryCommitId != null) {
                LOG.warning("fetchCommitsUpTo for " + project + "/" + repo + " never reached lower boundary "
                        + lowerBoundaryCommitId + " within " + RANGE_SCAN_PAGES + " pages — list may be incomplete");
            }
        }
        return commits;
    }

    /**
     * Fetches commits with optional date filters.
     */
    public List<ChangeItem> fetchCommits(String project, String repo, String since, String until, String branch) {
        List<ChangeItem> items = new ArrayList<>();
        int skip = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommits(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, since, until, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> commits = response.valueOrEmpty();
            if (commits.isEmpty()) {
                break;
            }
            for (CommitResponse c : commits) {
                items.add(toChangeItem(project, repo, c));
            }
            skip += commits.size();
            if (commits.size() < COMMIT_PAGE_SIZE) {
                break;
            }
        }
        return items;
    }

    /**
     * All commits that touched a specific file path (e.g. {@code "/CHANGELOG.md"}), newest first.
     */
    public List<CommitResponse> fetchCommitsForPath(String project, String repo, String path) {
        String key = project + "/" + repo + path;
        List<CommitResponse> cached = enrichmentCache.getFileHistory(key);
        if (cached != null) {
            return cached;
        }
        List<CommitResponse> items = new ArrayList<>();
        int skip = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommits(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, null, null, null, path, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> commits = response.valueOrEmpty();
            if (commits.isEmpty()) break;
            items.addAll(commits);
            skip += commits.size();
            if (commits.size() < COMMIT_PAGE_SIZE) break;
        }
        enrichmentCache.putFileHistory(project, repo, path, items);
        return items;
    }

    /**
     * Fetches the list of file paths changed in one commit. Returns empty on error.
     */
    public List<String> fetchCommitFilePaths(String project, String repo, String commitId) {
        String key = project + "/" + repo + "/" + commitId;
        List<String> cached = enrichmentCache.getCommitFilePaths(project, repo, commitId);
        if (cached != null) return cached;
        try {
            CommitChangesResponse resp = client.getCommitChanges(org, project, repo, commitId, AzureDevOpsRestClient.API_VERSION);
            List<String> paths = resp == null || resp.changes() == null
                    ? List.of()
                    : resp.changes().stream()
                            .map(c -> c.item() != null ? c.item().path() : null)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
            enrichmentCache.putCommitFilePaths(project, repo, commitId, paths);
            return paths;
        } catch (Exception e) {
            LOG.warning("Failed to fetch changes for commit " + commitId + " in " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    public ChangeItem toChangeItem(String project, String repo, CommitResponse c) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setId(c.commitId());
        item.setTitle(c.comment());
        item.setDescription(c.comment());
        item.setCategory(ChangeCategoryClassifier.fromText(c.comment()));
        item.setAuthor(c.author() != null ? c.author().name() : null);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(c.author() != null ? c.author().date() : null);
        item.setLinks(c.url() != null ? List.of(c.url()) : List.of());
        item.setFilePaths(List.of());
        return item;
    }

    public CommitResponse getCommitCached(String project, String repo, String commitId) {
        return enrichmentCache.getCommitCached(project, repo, commitId);
    }
}