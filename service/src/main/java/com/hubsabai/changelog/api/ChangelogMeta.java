package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ChangelogMeta {

    private final String source;
    private final String model;
    private final String editedBy;
    private final String at;
    private final boolean hasPrevious;
    private final String previousText;
    private final String previousSource;
    private final String previousModel;
    private final String previousEditedBy;
    private final String previousAt;
    private final boolean hasUnpushedChanges;
    private final String pushedAt;
    private final String pushedPullRequestUrl;
    private final String pushedText;
    private final List<ChangelogRevisionDto> revisions;
    private final Integer tokens;
    private final Integer durationMs;

    public ChangelogMeta(
            @JsonProperty("source") String source,
            @JsonProperty("model") String model,
            @JsonProperty("editedBy") String editedBy,
            @JsonProperty("at") String at,
            @JsonProperty("hasPrevious") boolean hasPrevious,
            @JsonProperty("previousText") String previousText,
            @JsonProperty("previousSource") String previousSource,
            @JsonProperty("previousModel") String previousModel,
            @JsonProperty("previousEditedBy") String previousEditedBy,
            @JsonProperty("previousAt") String previousAt,
            @JsonProperty("hasUnpushedChanges") boolean hasUnpushedChanges,
            @JsonProperty("pushedAt") String pushedAt,
            @JsonProperty("pushedPullRequestUrl") String pushedPullRequestUrl,
            @JsonProperty("pushedText") String pushedText,
            @JsonProperty("revisions") List<ChangelogRevisionDto> revisions,
            @JsonProperty("tokens") Integer tokens,
            @JsonProperty("durationMs") Integer durationMs) {
        this.source = source;
        this.model = model;
        this.editedBy = editedBy;
        this.at = at;
        this.hasPrevious = hasPrevious;
        this.previousText = previousText;
        this.previousSource = previousSource;
        this.previousModel = previousModel;
        this.previousEditedBy = previousEditedBy;
        this.previousAt = previousAt;
        this.hasUnpushedChanges = hasUnpushedChanges;
        this.pushedAt = pushedAt;
        this.pushedPullRequestUrl = pushedPullRequestUrl;
        this.pushedText = pushedText;
        this.revisions = revisions != null ? revisions : List.of();
        this.tokens = tokens;
        this.durationMs = durationMs;
    }

    @JsonProperty("source") public String getSource() { return source; }
    @JsonProperty("model") public String getModel() { return model; }
    @JsonProperty("editedBy") public String getEditedBy() { return editedBy; }
    @JsonProperty("at") public String getAt() { return at; }
    @JsonProperty("hasPrevious") public boolean isHasPrevious() { return hasPrevious; }
    @JsonProperty("previousText") public String getPreviousText() { return previousText; }
    @JsonProperty("previousSource") public String getPreviousSource() { return previousSource; }
    @JsonProperty("previousModel") public String getPreviousModel() { return previousModel; }
    @JsonProperty("previousEditedBy") public String getPreviousEditedBy() { return previousEditedBy; }
    @JsonProperty("previousAt") public String getPreviousAt() { return previousAt; }
    @JsonProperty("hasUnpushedChanges") public boolean isHasUnpushedChanges() { return hasUnpushedChanges; }
    @JsonProperty("pushedAt") public String getPushedAt() { return pushedAt; }
    @JsonProperty("pushedPullRequestUrl") public String getPushedPullRequestUrl() { return pushedPullRequestUrl; }
    @JsonProperty("pushedText") public String getPushedText() { return pushedText; }
    @JsonProperty("tokens") public Integer getTokens() { return tokens; }
    @JsonProperty("durationMs") public Integer getDurationMs() { return durationMs; }
    @JsonProperty("revisions") public List<ChangelogRevisionDto> getRevisions() { return revisions; }
}
