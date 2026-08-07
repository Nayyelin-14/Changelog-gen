package com.hubsabai.changelog.ai;

import java.util.List;

public record ReleaseNoteEntry(
    String type,
    String scope,
    String title,
    String description,
    Integer prNumber,
    List<String> workItems
) {}
