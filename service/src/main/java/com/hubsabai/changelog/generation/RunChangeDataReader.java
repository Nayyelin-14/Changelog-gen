package com.hubsabai.changelog.generation;

import com.hubsabai.changelog.connector.azuredevops.ChangeCategoryClassifier;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a provider-normalized {@link RunChangeContext} into the shared {@link ReleaseData} that the
 * AI pipeline consumes. Both {@code GitHubOrgConnector} and {@code AzureDevOpsOrgConnector} produce
 * the context; everything downstream is provider-agnostic. The repo-tier collapse for GitHub is
 * intentionally handled by the caller (the "project" the REST resource passes is the GitHub owner,
 * and repo identity is passed separately as {@code repo}).
 */
@ApplicationScoped
public class RunChangeDataReader {

    public ReleaseData toReleaseData(String project, String repo, String branch, String org, RunChangeContext context) {
        List<ChangeItem> items = new ArrayList<>();

        if (context.getPrs() != null && !context.getPrs().isEmpty()) {
            for (RunChangeContext.PrInfo pr : context.getPrs()) {
                ChangeItem item = new ChangeItem();
                item.setType(ChangeItem.ItemType.PULL_REQUEST);
                item.setId(pr.getId());
                item.setTitle(pr.getTitle());
                item.setDescription(pr.getDescription());
                item.setCategory(ChangeCategoryClassifier.fromText(pr.getTitle()));
                item.setAuthor(pr.getAuthor());
                item.setProject(project);
                item.setRepo(repo);
                item.setDate(pr.getUpdatedAt());
                item.setLinks(pr.getUrl() != null ? List.of(pr.getUrl()) : List.of());
                item.setFilePaths(List.of());
                items.add(item);
            }
        }

        if (context.getCommits() != null) {
            for (RunChangeContext.CommitInfo commit : context.getCommits()) {
                ChangeItem item = new ChangeItem();
                item.setType(ChangeItem.ItemType.COMMIT);
                item.setId(commit.getSha());
                item.setTitle(firstLine(commit.getMessage()));
                item.setDescription(commit.getMessage());
                item.setCategory(ChangeCategoryClassifier.fromText(commit.getMessage()));
                item.setAuthor(commit.getAuthor());
                item.setProject(project);
                item.setRepo(repo);
                item.setDate(commit.getDate());
                item.setLinks(commit.getSha() != null
                        ? List.of("https://github.com/" + repo + "/commit/" + commit.getSha())
                        : List.of());
                item.setFilePaths(commit.getFilePaths() != null ? commit.getFilePaths() : List.of());
                items.add(item);
            }
        }

        if (context.getWorkItems() != null) {
            for (RunChangeContext.WorkItemInfo wi : context.getWorkItems()) {
                ChangeItem item = new ChangeItem();
                item.setType(ChangeItem.ItemType.WORK_ITEM);
                item.setId(wi.getId());
                item.setTitle(wi.getTitle());
                item.setCategory(ChangeCategoryClassifier.fromWorkItemType(wi.getType()));
                item.setDescription(wi.getDescription());
                item.setProject(project);
                item.setRepo(repo);
                item.setLinks(wi.getUrl() != null ? List.of(wi.getUrl()) : List.of());
                item.setFilePaths(List.of());
                items.add(item);
            }
        }

        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg(org);
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());
        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }
}