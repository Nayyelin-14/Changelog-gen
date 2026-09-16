package com.hubsabai.changelog.ai;

import java.util.List;

public record ReleaseNoteEntry(
    String type,
    String scope,
    String title,
    String description,
    Integer prNumber,
    List<String> workItems,
    List<String> filePaths,
    int additions,
    int deletions
) {
    public ReleaseNoteEntry {
        if (filePaths == null) filePaths = List.of();
        if (workItems == null) workItems = List.of();
    }
}
