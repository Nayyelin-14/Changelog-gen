package com.hubsabai.changelog.generation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-normalized snapshot of everything a single pipeline/workflow run makes available for
 * changelog generation. Both {@code AzureDevOpsOrgConnector} and {@code GitHubOrgConnector} fill
 * this from their own API calls; the rest of the generation pipeline only sees this shape, so the
 * AI/business logic never leaks provider details.
 */
public class RunChangeContext {

    private RunInfo run;
    private List<PrInfo> prs;
    private List<CommitInfo> commits;
    private List<FileChangeInfo> files;
    private List<WorkItemInfo> workItems;

    public RunChangeContext() {
        this.prs = new ArrayList<>();
        this.commits = new ArrayList<>();
        this.files = new ArrayList<>();
        this.workItems = new ArrayList<>();
    }

    @JsonProperty("run")
    public RunInfo getRun() { return run; }
    public void setRun(RunInfo run) { this.run = run; }

    @JsonProperty("prs")
    public List<PrInfo> getPrs() { return prs; }
    public void setPrs(List<PrInfo> prs) { this.prs = prs; }

    /** @deprecated Use {@link #getPrs()} instead. Kept for backward compatibility with stored data. */
    @Deprecated
    @JsonProperty("pr")
    public PrInfo getPr() { return prs.isEmpty() ? null : prs.get(0); }

    /** @deprecated Use {@link #setPrs(List)} instead. Kept for backward compatibility. */
    @Deprecated
    public void setPr(PrInfo pr) {
        if (pr != null) {
            this.prs.clear();
            this.prs.add(pr);
        }
    }

    @JsonProperty("commits")
    public List<CommitInfo> getCommits() { return commits; }
    public void setCommits(List<CommitInfo> commits) { this.commits = commits; }

    @JsonProperty("workItems")
    public List<WorkItemInfo> getWorkItems() { return workItems; }
    public void setWorkItems(List<WorkItemInfo> workItems) { this.workItems = workItems; }

    @JsonProperty("files")
    public List<FileChangeInfo> getFiles() { return files; }
    public void setFiles(List<FileChangeInfo> files) { this.files = files; }

    /** Metadata about the pipeline/workflow run itself. */
    public static class RunInfo {
        private String runId;
        private String runNumber;
        private String pipelineName;
        private String status;
        private String result;
        private String branch;
        private String headSha;
        private String startedAt;
        private String finishedAt;
        private String triggerUrl;
        private String providerCommitMessage;

        public RunInfo() {}

        @JsonProperty("runId") public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        @JsonProperty("runNumber") public String getRunNumber() { return runNumber; }
        public void setRunNumber(String runNumber) { this.runNumber = runNumber; }
        @JsonProperty("pipelineName") public String getPipelineName() { return pipelineName; }
        public void setPipelineName(String pipelineName) { this.pipelineName = pipelineName; }
        @JsonProperty("status") public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        @JsonProperty("result") public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        @JsonProperty("branch") public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        @JsonProperty("headSha") public String getHeadSha() { return headSha; }
        public void setHeadSha(String headSha) { this.headSha = headSha; }
        @JsonProperty("startedAt") public String getStartedAt() { return startedAt; }
        public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
        @JsonProperty("finishedAt") public String getFinishedAt() { return finishedAt; }
        public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
        @JsonProperty("triggerUrl") public String getTriggerUrl() { return triggerUrl; }
        public void setTriggerUrl(String triggerUrl) { this.triggerUrl = triggerUrl; }
        @JsonProperty("providerCommitMessage") public String getProviderCommitMessage() { return providerCommitMessage; }
        public void setProviderCommitMessage(String providerCommitMessage) { this.providerCommitMessage = providerCommitMessage; }
    }

    /** The pull/merge request that triggered (or is associated with) the run, if any. */
    public static class PrInfo {
        private String id;
        private String title;
        private String description;
        private String author;
        private String state;
        private String url;
        private String updatedAt;

        public PrInfo() {}

        @JsonProperty("id") public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        @JsonProperty("title") public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        @JsonProperty("description") public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        @JsonProperty("author") public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        @JsonProperty("state") public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        @JsonProperty("url") public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        @JsonProperty("updatedAt") public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    }

    /** One commit in the run's change range. */
    public static class CommitInfo {
        private String sha;
        private String message;
        private String author;
        private String date;
        private List<String> filePaths;

        public CommitInfo() {}

        @JsonProperty("sha") public String getSha() { return sha; }
        public void setSha(String sha) { this.sha = sha; }
        @JsonProperty("message") public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        @JsonProperty("author") public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        @JsonProperty("date") public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        @JsonProperty("filePaths") public List<String> getFilePaths() { return filePaths; }
        public void setFilePaths(List<String> filePaths) { this.filePaths = filePaths; }
    }

    /** Work items / issues linked to the run. Provider-specific and optional (Azure has them,
     * GitHub typically none). */
    public static class WorkItemInfo {
        private String id;
        private String title;
        private String type;
        private String state;
        private String url;
        private String description;

        public WorkItemInfo() {}

        @JsonProperty("id") public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        @JsonProperty("title") public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        @JsonProperty("type") public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        @JsonProperty("state") public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        @JsonProperty("url") public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        @JsonProperty("description") public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /** A file changed by the run, with an add/delete summary (diff stats). */
    public static class FileChangeInfo {
        private String path;
        private String status;
        private int additions;
        private int deletions;

        public FileChangeInfo() {}

        @JsonProperty("path") public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        @JsonProperty("status") public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        @JsonProperty("additions") public int getAdditions() { return additions; }
        public void setAdditions(int additions) { this.additions = additions; }
        @JsonProperty("deletions") public int getDeletions() { return deletions; }
        public void setDeletions(int deletions) { this.deletions = deletions; }
    }
}