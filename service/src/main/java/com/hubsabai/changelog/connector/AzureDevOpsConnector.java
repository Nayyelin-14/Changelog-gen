package com.hubsabai.changelog.connector;

import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsRestClient;
import com.hubsabai.changelog.connector.azuredevops.ChangeCategoryClassifier;
import com.hubsabai.changelog.connector.azuredevops.WorkItemFields;
import com.hubsabai.changelog.connector.azuredevops.dto.PullRequestResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.WorkItemResponse;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AzureDevOpsConnector implements SourceConnector {

    @Inject
    @RestClient
    AzureDevOpsRestClient azureClient;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    @Override
    public ReleaseData fetch(ConnectionConfig config) {
        String project = config.getProject();
        String rawLog = config.getRawCommitLog();
        List<Integer> ids = config.getWorkItemIds();

        List<ChangeItem> items = new ArrayList<>();

        List<ChangeItem> commitItems = new ArrayList<>();
        if (rawLog != null && !rawLog.isBlank()) {
            commitItems = parseRawCommitLog(rawLog, project);
            items.addAll(commitItems);
        }

        Set<Integer> prIds = new LinkedHashSet<>();
        if (config.getPrIds() != null) {
            prIds.addAll(config.getPrIds());
        }
        prIds.addAll(extractMergedPullRequestIds(commitItems));

        if (!prIds.isEmpty()) {
            for (int id : prIds) {
                try {
                    PullRequestResponse pr = azureClient.getPullRequestById(org, id, AzureDevOpsRestClient.API_VERSION);
                    ChangeItem item = new ChangeItem();
                    item.setType(ChangeItem.ItemType.PULL_REQUEST);
                    item.setId(String.valueOf(pr.pullRequestId()));
                    item.setTitle(pr.title());
                    item.setCategory(ChangeCategoryClassifier.fromText(pr.title()));
                    item.setDescription(pr.description());
                    item.setAuthor(pr.createdBy() != null ? pr.createdBy().displayName() : null);
                    item.setProject(project);
                    item.setLinks(pr.url() != null ? List.of(pr.url()) : new ArrayList<>());
                    item.setFilePaths(new ArrayList<>());
                    items.add(item);
                } catch (Exception e) {
                    ChangeItem item = new ChangeItem();
                    item.setType(ChangeItem.ItemType.PULL_REQUEST);
                    item.setId(String.valueOf(id));
                    item.setTitle("Pull Request #" + id + " (fetch failed: " + e.getMessage() + ")");
                    item.setCategory("chore");
                    item.setProject(project);
                    items.add(item);
                }
            }
        }

        if (ids != null && !ids.isEmpty()) {
            for (int id : ids) {
                try {
                    WorkItemResponse wi = azureClient.getWorkItem(org, project, id, AzureDevOpsRestClient.API_VERSION);
                    ChangeItem item = new ChangeItem();
                    item.setType(ChangeItem.ItemType.WORK_ITEM);
                    item.setId(String.valueOf(wi.getId()));
                    item.setTitle(WorkItemFields.string(wi, "System.Title"));
                    String type = WorkItemFields.string(wi, "System.WorkItemType");
                    item.setCategory(ChangeCategoryClassifier.fromWorkItemType(type));
                    item.setDescription(WorkItemFields.string(wi, "System.Description"));
                    item.setAuthor(WorkItemFields.string(wi, "System.AssignedTo"));
                    item.setProject(project);
                    String url = WorkItemFields.htmlUrl(wi);
                    item.setLinks(url != null ? List.of(url) : new ArrayList<>());
                    item.setFilePaths(new ArrayList<>());
                    items.add(item);
                } catch (Exception e) {
                    ChangeItem item = new ChangeItem();
                    item.setType(ChangeItem.ItemType.WORK_ITEM);
                    item.setId(String.valueOf(id));
                    item.setTitle("Work Item #" + id + " (fetch failed: " + e.getMessage() + ")");
                    item.setCategory("chore");
                    item.setProject(project);
                    items.add(item);
                }
            }
        }

        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setProject(project);
        meta.setRepo(config.getRepo());
        meta.setBranch(config.getBranch());
        meta.setReleaseDate(LocalDate.now().toString());

        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    /**
     * Parses the pipeline's raw commit log, which mimics `git log` output:
     *   === <subject>
     *   <optional body lines>
     *   <changed file paths, one per line, from git diff-tree --name-only>
     * with blocks separated by "===" markers (a leading blank line between commits is optional).
     * The format has no explicit marker between body and file-path lines, so trailing lines that
     * look like paths (no whitespace, and a "/" or file extension) are treated as file paths.
     */
    private List<ChangeItem> parseRawCommitLog(String rawLog, String project) {
        List<ChangeItem> items = new ArrayList<>();
        String[] blocks = rawLog.split("(?m)^===\\s*");

        for (String block : blocks) {
            block = block.strip();
            if (block.isEmpty()) continue;

            String[] lines = block.split("\n");
            String subject = lines[0].trim();
            if (subject.isEmpty()) continue;

            int bodyEnd = lines.length;
            List<String> filePaths = new ArrayList<>();
            while (bodyEnd > 1 && isFilePathLike(lines[bodyEnd - 1].trim())) {
                filePaths.add(0, lines[bodyEnd - 1].trim());
                bodyEnd--;
            }

            StringBuilder body = new StringBuilder();
            for (int i = 1; i < bodyEnd; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (body.length() > 0) body.append("\n");
                body.append(line);
            }

            ChangeItem ci = new ChangeItem();
            ci.setType(ChangeItem.ItemType.COMMIT);
            ci.setTitle(subject);
            ci.setCategory(ChangeCategoryClassifier.fromText(subject));
            ci.setDescription(body.length() > 0 ? body.toString() : subject);
            ci.setProject(project);
            ci.setFilePaths(filePaths);
            items.add(ci);
        }
        return items;
    }

    private static boolean isFilePathLike(String line) {
        if (line.isEmpty() || line.contains(" ")) return false;
        return line.contains("/") || line.matches(".*\\.[A-Za-z0-9]{1,10}$");
    }

    private static final Pattern MERGED_PR_PATTERN =
            Pattern.compile("^Merged PR (\\d+):.*", Pattern.CASE_INSENSITIVE);

    /**
     * Best-effort: detects Azure Repos' default non-fast-forward merge commit subject
     * ("Merged PR &lt;id&gt;: &lt;title&gt;"), already present in the commit items parsed from
     * rawCommitLog. Repos using fast-forward-only merge policies produce no such synthetic
     * commit, so this finds nothing there — a known limitation, not a guarantee.
     */
    private static List<Integer> extractMergedPullRequestIds(List<ChangeItem> commitItems) {
        List<Integer> ids = new ArrayList<>();
        for (ChangeItem item : commitItems) {
            Matcher m = MERGED_PR_PATTERN.matcher(item.getTitle());
            if (m.matches()) {
                ids.add(Integer.parseInt(m.group(1)));
            }
        }
        return ids;
    }

}
