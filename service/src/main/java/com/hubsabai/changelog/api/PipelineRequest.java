package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class PipelineRequest {

    private String project;
    private String repo;
    private String branch;
    private String version;
    private String rawCommitLog;
    private List<Integer> workItemIds;
    private List<Integer> prIds;
    private String model;
    private String systemPrompt;
    /** {@code prerelease} or {@code release} — which stage of the pipeline is reporting this.
     * Optional: it's what lets a PR's location move from prerelease to release without ever
     * regressing, and what keeps prerelease builds out of the "actually shipped" record, but a
     * caller that omits it (or sends something else) must never have its build fail over it —
     * defaults to {@code release}. Unused when {@code raw} is true — that flow doesn't touch
     * stage tracking at all. */
    private String stage;

    /** When true, this call is a PR-merge raw init, not a release ingestion: instead of the
     * bundled rawCommitLog/workItemIds/prIds above, only {@link #pullRequestId} is used — the
     * Composer fetches that one PR's own data from Azure DevOps and writes a plain, non-AI
     * developer changelog draft. Defaults to false, which keeps today's release-ingestion
     * behavior exactly as-is. */
    private boolean raw;

    /** Required when {@link #raw} is true — the single PR this call is reporting. Unused (and
     * unrelated to) {@link #prIds}, which is a batch of PR ids for the release-ingestion flow. */
    private Integer pullRequestId;

    /** {@code $(Build.BuildId)} of the pipeline run reporting this release — when set, the
     * Composer fetches that run's own commits/work items/PRs from Azure DevOps' Build API itself
     * (see {@code AzureDevOpsOrgConnector#fetchRunChanges}) instead of using {@link
     * #rawCommitLog}/{@link #workItemIds}/{@link #prIds}, which are then ignored. Unrelated to
     * {@link #raw} — this is still the bundled release-ingestion flow, just with a different way
     * of sourcing the same {@code ReleaseData}. */
    private Integer buildId;

    /** {@code $(Build.BuildNumber)} or GitHub Actions run number — the pipeline's own identifier
     * for this run (e.g. "20240115.1" or "42"). Stored alongside {@code buildId} so the
     * dashboard's Pipeline runs list can show both the app version and the pipeline run number. */
    private String buildNumber;

    public PipelineRequest() {}

    @JsonProperty("project")
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    @JsonProperty("rawCommitLog")
    public String getRawCommitLog() { return rawCommitLog; }
    public void setRawCommitLog(String rawCommitLog) { this.rawCommitLog = rawCommitLog; }

    @JsonProperty("workItemIds")
    public List<Integer> getWorkItemIds() { return workItemIds; }
    public void setWorkItemIds(List<Integer> workItemIds) { this.workItemIds = workItemIds; }

    @JsonProperty("prIds")
    public List<Integer> getPrIds() { return prIds; }
    public void setPrIds(List<Integer> prIds) { this.prIds = prIds; }

    @JsonProperty("model")
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @JsonProperty("systemPrompt")
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    @JsonProperty("stage")
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    @JsonProperty("raw")
    public boolean isRaw() { return raw; }
    public void setRaw(boolean raw) { this.raw = raw; }

    @JsonProperty("pullRequestId")
    public Integer getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(Integer pullRequestId) { this.pullRequestId = pullRequestId; }

    @JsonProperty("buildId")
    public Integer getBuildId() { return buildId; }
    public void setBuildId(Integer buildId) { this.buildId = buildId; }

    @JsonProperty("buildNumber")
    public String getBuildNumber() { return buildNumber; }
    public void setBuildNumber(String buildNumber) { this.buildNumber = buildNumber; }
}
