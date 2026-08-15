package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlQuery;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlResult;
import com.hubsabai.changelog.connector.azuredevops.dto.WorkItemResponse;
import com.hubsabai.changelog.core.model.ChangeItem;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Fetches work items for a project.
 */
@ApplicationScoped
public class WorkItemFetcher {

    private static final int WORK_ITEM_BATCH_SIZE = 200;

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    @Inject
    @ConfigProperty(name = "azure.devops.done-states", defaultValue = "Closed,Done,Resolved,Completed")
    String doneStatesConfig;

    private static final Logger LOG = Logger.getLogger(WorkItemFetcher.class.getName());

    /**
     * Work items belong to the project, not a specific repo.
     */
    public List<ChangeItem> fetchProjectWorkItems(String project) {
        String stateList = java.util.Arrays.stream(doneStatesConfig.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> "'" + s.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
        String wiql = """
                SELECT [System.Id] FROM WorkItems
                WHERE [System.TeamProject] = @project
                AND [System.State] IN (%s)
                ORDER BY [System.ChangedDate] DESC""".formatted(stateList);

        WiqlResult result = client.queryWorkItems(org, project, new WiqlQuery(wiql), AzureDevOpsRestClient.API_VERSION);
        List<Integer> ids = result.workItemsOrEmpty().stream()
                .map(WiqlResult.WorkItemReference::id)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<ChangeItem> items = new ArrayList<>();
        for (List<Integer> batch : partition(ids, WORK_ITEM_BATCH_SIZE)) {
            String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
            AzureDevOpsListResponse<WorkItemResponse> batchResponse =
                    client.getWorkItemsBatch(org, project, idsParam, AzureDevOpsRestClient.API_VERSION);
            for (WorkItemResponse wi : batchResponse.valueOrEmpty()) {
                items.add(toChangeItem(project, wi));
            }
        }
        return items;
    }

    public ChangeItem toChangeItem(String project, WorkItemResponse wi) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.WORK_ITEM);
        item.setId(String.valueOf(wi.getId()));
        item.setTitle(WorkItemFields.string(wi, "System.Title"));
        item.setCategory(ChangeCategoryClassifier.fromWorkItemType(WorkItemFields.string(wi, "System.WorkItemType")));
        item.setDescription(WorkItemFields.string(wi, "System.Description"));
        item.setAuthor(WorkItemFields.string(wi, "System.AssignedTo"));
        item.setProject(project);
        item.setLinks(List.of("https://dev.azure.com/%s/%s/_workitems/edit/%s".formatted(org, project, wi.getId())));
        item.setFilePaths(List.of());
        return item;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}