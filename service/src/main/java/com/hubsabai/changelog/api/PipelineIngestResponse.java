package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ack for a pipeline call. {@code changelog} is a plain, non-AI bullet list served as the
 * starting Developer draft — the calling CI can commit it to CHANGELOG.md immediately.
 * AI-generated text only comes from dashboard Generate/Edit and overrides this raw draft. */
public class PipelineIngestResponse {

    private final String project;
    private final String repo;
    private final String version;
    private final String stage;
    private final int prCount;
    private final String changelog;

    public PipelineIngestResponse(String project, String repo, String version, String stage, int prCount, String changelog) {
        this.project = project;
        this.repo = repo;
        this.version = version;
        this.stage = stage;
        this.prCount = prCount;
        this.changelog = changelog;
    }

    @JsonProperty("project")
    public String getProject() { return project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }

    @JsonProperty("version")
    public String getVersion() { return version; }

    @JsonProperty("stage")
    public String getStage() { return stage; }

    @JsonProperty("prCount")
    public int getPrCount() { return prCount; }

    @JsonProperty("changelog")
    public String getChangelog() { return changelog; }
}
