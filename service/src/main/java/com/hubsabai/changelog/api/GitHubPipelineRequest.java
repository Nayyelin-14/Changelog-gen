package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST /api/github/pipeline/generate} — the eager GitHub workflow-run
 * capture. {@code runId} is the GitHub Actions workflow run ID ({@code github.run_id} in the
 * workflow YAML); {@code project}/{@code repo} identify the repository (GitHub requires owner and
 * repo to fetch a run — the ID alone is not enough). {@code version} is optional: raw capture is
 * version-free and stores the snapshot on {@code recorded_pipeline_run} keyed by run ID only. */
public class GitHubPipelineRequest {

    private Long runId;
    private String project;
    private String repo;
    private String version;
    private String branch;

    public GitHubPipelineRequest() {}

    @JsonProperty("runId")
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    @JsonProperty("project")
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
}
