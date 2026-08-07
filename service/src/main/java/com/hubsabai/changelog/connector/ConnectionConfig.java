package com.hubsabai.changelog.connector;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ConnectionConfig {

    private String project;
    private String repo;
    private String branch;
    private String rawCommitLog;
    private List<Integer> workItemIds;
    private List<Integer> prIds;

    public ConnectionConfig() {}

    @JsonProperty("project")
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("rawCommitLog")
    public String getRawCommitLog() { return rawCommitLog; }
    public void setRawCommitLog(String rawCommitLog) { this.rawCommitLog = rawCommitLog; }

    @JsonProperty("workItemIds")
    public List<Integer> getWorkItemIds() { return workItemIds; }
    public void setWorkItemIds(List<Integer> workItemIds) { this.workItemIds = workItemIds; }

    @JsonProperty("prIds")
    public List<Integer> getPrIds() { return prIds; }
    public void setPrIds(List<Integer> prIds) { this.prIds = prIds; }
}
