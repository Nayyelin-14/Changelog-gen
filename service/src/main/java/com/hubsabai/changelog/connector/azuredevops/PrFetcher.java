package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.PullRequestResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlQuery;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlResult;
import com.hubsabai.changelog.connector.azuredevops.dto.WorkItemResponse;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.PrReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Fetches pull requests and their details.
 */
@ApplicationScoped
public class PrFetcher {

    private static final int PULL_REQUEST_PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final int PR_MERGE_LOOKUP_MAX_PAGES = 5;

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    CommitFetcher commitFetcher;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    private static final Logger LOG = Logger.getLogger(PrFetcher.class.getName());

    /**
     * Fetches PRs whose creation date falls within the time span of the commit range.
     * Uses the commit dates of the boundary SHAs as an approximate filter.
     */
    public List<ChangeItem> fetchPullRequestsForRange(String project, String repo, String fromCommitId, String toCommitId, String branch) {
        // Get dates for the boundary commits to approximate the PR time window
        String since = null;
        if (fromCommitId != null) {
            try {
                CommitResponse c = commitFetcher.getCommitCached(project, repo, fromCommitId);
                if (c != null && c.author() != null) since = c.author().date();
            } catch (Exception e) {
                LOG.warning("getCommit failed for " + fromCommitId + ": " + e);
            }
        }
        String until = null;
        if (toCommitId != null) {
            try {
                CommitResponse c = commitFetcher.getCommitCached(project, repo, toCommitId);
                if (c != null && c.author() != null) until = c.author().date();
            } catch (Exception e) {
                LOG.warning("getCommit failed for " + toCommitId + ": " + e);
            }
        }
        return fetchPullRequests(project, repo, since, until, branch);
    }

    /**
     * Azure DevOps's List Pull Requests API has no date filter, so {@code since} is applied client-side.
     * Pages come back newest-first (default order), so once a page's oldest PR is before {@code since}
     * we stop paginating — no need to walk the rest of the repo's PR history.
     */
    public List<ChangeItem> fetchPullRequests(String project, String repo, String since, String until, String branch) {
        List<ChangeItem> items = new ArrayList<>();
        int skip = 0;
        String targetRefName = branch != null ? "refs/heads/" + branch : null;

        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<PullRequestResponse> response =
                    client.listPullRequests(org, project, repo, "all", targetRefName, PULL_REQUEST_PAGE_SIZE, skip, AzureDevOpsRestClient.API_VERSION);
            List<PullRequestResponse> prs = response.valueOrEmpty();
            if (prs.isEmpty()) {
                break;
            }
            for (PullRequestResponse pr : prs) {
                if (isWithinRange(pr.creationDate(), since, until)) {
                    items.add(toChangeItem(project, repo, pr));
                }
            }
            skip += prs.size();
            boolean pastSince = since != null && prs.stream()
                    .anyMatch(pr -> pr.creationDate() != null && pr.creationDate().compareTo(since) < 0);
            if (prs.size() < PULL_REQUEST_PAGE_SIZE || pastSince) {
                break;
            }
        }
        return items;
    }

    /**
     * The most recently completed PRs on a branch, newest first — used by the history endpoint to
     * find merges nobody has generated a changelog for yet. Only the first page is fetched: a PR
     * old enough to have fallen off it is assumed to already have a changelog, so it's not worth
     * the extra Azure DevOps calls to keep paginating.
     */
    public List<PullRequestResponse> listRecentCompletedPullRequests(String project, String repo, String branch) {
        String targetRefName = branch != null ? "refs/heads/" + branch : null;
        AzureDevOpsListResponse<PullRequestResponse> response = client.listPullRequests(
                org, project, repo, "completed", targetRefName, PULL_REQUEST_PAGE_SIZE, 0, AzureDevOpsRestClient.API_VERSION);
        return response.valueOrEmpty();
    }

    /**
     * Fetches PR details for pipeline raw-init flow.
     */
    public PullRequestDetails fetchPullRequestDetails(String project, String repo, int prId) {
        PullRequestResponse pr = client.getPullRequestById(org, prId, AzureDevOpsRestClient.API_VERSION);

        List<String> commitMessages = new ArrayList<>();
        try {
            AzureDevOpsListResponse<CommitResponse> commits = client.listPullRequestCommits(
                    org, project, repo, prId, AzureDevOpsRestClient.API_VERSION);
            for (CommitResponse c : commits.valueOrEmpty()) {
                if (c.comment() != null && !c.comment().isBlank()) {
                    commitMessages.add(c.comment());
                }
            }
        } catch (Exception e) {
            LOG.warning("listPullRequestCommits failed for PR " + prId + " in " + project + "/" + repo + ": " + e);
        }

        List<WorkItemSummary> workItems = new ArrayList<>();
        try {
            AzureDevOpsListResponse<WiqlResult.WorkItemReference> refs = client.getPullRequestWorkItems(
                    org, project, repo, prId, AzureDevOpsRestClient.API_VERSION);
            List<Integer> ids = refs.valueOrEmpty().stream().map(WiqlResult.WorkItemReference::id).toList();
            if (!ids.isEmpty()) {
                String idsParam = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
                AzureDevOpsListResponse<WorkItemResponse> batch =
                        client.getWorkItemsBatch(org, project, idsParam, AzureDevOpsRestClient.API_VERSION);
                for (WorkItemResponse wi : batch.valueOrEmpty()) {
                    workItems.add(new WorkItemSummary(wi.getId(),
                            WorkItemFields.string(wi, "System.Title"),
                            WorkItemFields.string(wi, "System.WorkItemType"),
                            WorkItemFields.string(wi, "System.Description"),
                            WorkItemFields.string(wi, "System.State"),
                            WorkItemFields.string(wi, "System.AssignedTo"),
                            "https://dev.azure.com/" + org + "/" + project + "/_workitems/edit/" + wi.getId()));
                }
            }
        } catch (Exception e) {
            LOG.warning("Fetching linked work items failed for PR " + prId + " in " + project + "/" + repo + ": " + e);
        }

        return new PullRequestDetails(
                pr.pullRequestId(), pr.title(), pr.description(),
                pr.createdBy() != null ? pr.createdBy().displayName() : null,
                commitMessages, workItems);
    }

    /**
     * Fetches PR commits for deduping merge commits.
     */
    public List<CommitResponse> fetchPullRequestCommits(String project, String repo, String prId) {
        try {
            AzureDevOpsListResponse<CommitResponse> response = client.listPullRequestCommits(
                    org, project, repo, Integer.parseInt(prId), AzureDevOpsRestClient.API_VERSION);
            return response.valueOrEmpty();
        } catch (Exception e) {
            LOG.warning("listPullRequestCommits failed for PR " + prId + " in " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    /**
     * ID-based counterpart to {@link PrReference}: maps each of {@code commitIds} to the completed
     * PR whose {@code lastMergeCommit} landed it on {@code targetBranch}.
     */
    public Map<String, PullRequestResponse> findCompletedPrsByMergeCommit(
            String project, String repo, String targetBranch, Set<String> commitIds) {
        Map<String, PullRequestResponse> byCommitId = new HashMap<>();
        if (targetBranch == null || commitIds.isEmpty()) {
            return byCommitId;
        }
        int skip = 0;
        String targetRefName = "refs/heads/" + targetBranch;
        for (int page = 0; page < PR_MERGE_LOOKUP_MAX_PAGES; page++) {
            AzureDevOpsListResponse<PullRequestResponse> response = client.listPullRequests(
                    org, project, repo, "completed", targetRefName, PULL_REQUEST_PAGE_SIZE, skip, AzureDevOpsRestClient.API_VERSION);
            List<PullRequestResponse> prs = response.valueOrEmpty();
            if (prs.isEmpty()) break;
            for (PullRequestResponse pr : prs) {
                String commitId = pr.lastMergeCommit() != null ? pr.lastMergeCommit().commitId() : null;
                if (commitId != null && commitIds.contains(commitId)) {
                    byCommitId.put(commitId, pr);
                }
            }
            skip += prs.size();
            if (byCommitId.size() >= commitIds.size() || prs.size() < PULL_REQUEST_PAGE_SIZE) break;
        }
        return byCommitId;
    }

    private static boolean isWithinRange(String timestamp, String since, String until) {
        if (timestamp == null) {
            return true;
        }
        if (since != null && timestamp.compareTo(since) < 0) {
            return false;
        }
        return until == null || timestamp.compareTo(until) <= 0;
    }

    public ChangeItem toChangeItem(String project, String repo, PullRequestResponse pr) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.PULL_REQUEST);
        item.setId(String.valueOf(pr.pullRequestId()));
        item.setTitle(pr.title());
        item.setDescription(pr.description());
        item.setCategory(ChangeCategoryClassifier.fromText(pr.title()));
        item.setAuthor(pr.createdBy() != null ? pr.createdBy().displayName() : null);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(pr.creationDate());
        item.setLinks(List.of("https://dev.azure.com/%s/%s/_git/%s/pullrequest/%d".formatted(org, project, repo, pr.pullRequestId())));
        item.setFilePaths(List.of());
        return item;
    }

    // WorkItemSummary and PullRequestDetails are moved from the original connector
    public record WorkItemSummary(int id, String title, String type, String description,
                                   String state, String assignedTo, String url) {}

    public record PullRequestDetails(
            int prId, String title, String description, String author,
            List<String> commitMessages, List<WorkItemSummary> workItems) {}
}