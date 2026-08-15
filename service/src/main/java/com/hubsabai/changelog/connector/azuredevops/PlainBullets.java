package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.core.model.ChangeItem;
import java.util.List;

/**
 * Raw markdown bullet list from release/PR facts (no AI). Used as the starting Developer changelog
 * in the pipeline flow; overridable later by AI generation from the dashboard.
 */
public final class PlainBullets {

    private PlainBullets() {}

    /** One bullet per change item (commit/PR/work item) — flat, one line each. Also returned in
     * {@link PipelineIngestResponse} for the calling pipeline to commit to its own CHANGELOG.md. */
    public static String plainBullets(List<ChangeItem> items) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ChangeItem item : items) {
            if (!first) sb.append("\n");
            first = false;
            String title = item.getTitle() != null && !item.getTitle().isBlank() ? oneLine(item.getTitle()) : "(untitled)";
            sb.append("- ").append(title);
            boolean hasId = item.getId() != null && !item.getId().isBlank();
            if (hasId && item.getType() == ChangeItem.ItemType.PULL_REQUEST) {
                sb.append(" (PR #").append(item.getId()).append(")");
            } else if (hasId && item.getType() == ChangeItem.ItemType.WORK_ITEM) {
                sb.append(" (#").append(item.getId()).append(")");
            }
        }
        return sb.toString();
    }

    public static String plainBullets(PrFetcher.PullRequestDetails pr) {
        StringBuilder sb = new StringBuilder();

        String title = pr.title() != null && !pr.title().isBlank() ? oneLine(pr.title()) : "PR #" + pr.prId();
        sb.append("- ").append(title).append(" (PR #").append(pr.prId()).append(")");

        if (pr.description() != null && !pr.description().isBlank()) {
            sb.append("\n  - ").append(oneLine(pr.description()));
        }
        for (String commitMessage : pr.commitMessages()) {
            if (commitMessage == null || commitMessage.isBlank()) continue;
            sb.append("\n  - ").append(oneLine(commitMessage));
        }
        for (PrFetcher.WorkItemSummary workItem : pr.workItems()) {
            String type = workItem.type() != null && !workItem.type().isBlank() ? workItem.type() : "Work item";
            sb.append("\n  - ").append(type).append(" #").append(workItem.id());
            if (workItem.title() != null && !workItem.title().isBlank()) {
                sb.append(": ").append(oneLine(workItem.title()));
            }
        }

        return sb.toString();
    }

    /** Collapse line breaks in commit messages / PR descriptions — one bullet per fact. */
    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s*\\R+\\s*", " ");
    }
}
