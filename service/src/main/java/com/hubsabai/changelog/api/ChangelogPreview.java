package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Read-only, audience-specific view of a repo's latest release — no model choice, no generate button. */
public class ChangelogPreview {

    private final String project;
    private final String repo;
    private final String version;
    private final String audience;
    private final String text;

    public ChangelogPreview(
            @JsonProperty("project") String project,
            @JsonProperty("repo") String repo,
            @JsonProperty("version") String version,
            @JsonProperty("audience") String audience,
            @JsonProperty("text") String text) {
        this.project = project;
        this.repo = repo;
        this.version = version;
        this.audience = audience;
        this.text = text;
    }

    @JsonProperty("project")
    public String getProject() { return project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }

    @JsonProperty("version")
    public String getVersion() { return version; }

    @JsonProperty("audience")
    public String getAudience() { return audience; }

    @JsonProperty("text")
    public String getText() { return text; }
}
