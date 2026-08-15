package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ack for the GitHub workflow-run capture. {@code changelog} is a plain, non-AI bullet list
 * served as the starting Developer draft — the calling workflow can commit it to CHANGELOG.md
 * immediately. AI-generated text only comes from dashboard Generate/Edit and overrides this raw
 * draft. Also persisted onto {@code recorded_pipeline_run.raw_changelog} so the dashboard never
 * needs to re-fetch GitHub. */
public class GitHubPipelineIngestResponse {

    private final Long runId;
    private final String project;
    private final String repo;
    private final String version;
    private final String branch;
    private final int prCount;
    private final String changelog;

    public GitHubPipelineIngestResponse(Long runId, String project, String repo, String version, String branch, int prCount, String changelog) {
        this.runId = runId;
        this.project = project;
        this.repo = repo;
        this.version = version;
        this.branch = branch;
        this.prCount = prCount;
        this.changelog = changelog;
    }

    @JsonProperty("runId")
    public Long getRunId() { return runId; }

    @JsonProperty("project")
    public String getProject() { return project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }

    @JsonProperty("version")
    public String getVersion() { return version; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }

    @JsonProperty("prCount")
    public int getPrCount() { return prCount; }

    @JsonProperty("changelog")
    public String getChangelog() { return changelog; }
}