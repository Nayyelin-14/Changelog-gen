package com.hubsabai.changelog.connector.azuredevops;

/**
 * Deterministic category inference — no LLM guesswork. Shared by every place that turns
 * a commit message, PR title, or work item type into a {@code ChangeItem.category} value.
 */
public final class ChangeCategoryClassifier {

    private ChangeCategoryClassifier() {}

    public static String fromText(String text) {
        if (text == null) return "chore";
        String t = text.toLowerCase();
        // Checked first: a revert's title/message is usually the original change's own message
        // quoted verbatim (e.g. Revert "feat(x): ..."), which would otherwise match that original
        // change's keyword (feat/fix/etc.) and mislabel an undo as if it were the change itself.
        // Treated as "fix" — a revert is a corrective action, not a new feature or refactor.
        if (t.contains("revert")) return "fix";
        if (t.contains("fix") || t.contains("bug") || t.contains("error") || t.contains("hotfix")) return "fix";
        if (t.contains("feat") || t.contains("add") || t.contains("new") || t.contains("implement")) return "feat";
        if (t.contains("refactor") || t.contains("clean")) return "refactor";
        if (t.contains("test")) return "test";
        if (t.contains("doc")) return "docs";
        if (t.contains("build") || t.contains("ci")) return "build";
        return "chore";
    }

    public static String fromWorkItemType(String workItemType) {
        if (workItemType == null) return "chore";
        return switch (workItemType.toLowerCase()) {
            case "bug" -> "fix";
            case "feature", "user story", "product backlog item" -> "feat";
            case "task" -> "chore";
            case "epic" -> "feat";
            default -> "chore";
        };
    }
}
