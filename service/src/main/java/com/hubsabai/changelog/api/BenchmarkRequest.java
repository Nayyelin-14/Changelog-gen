package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class BenchmarkRequest {

    private String project;
    private String repo;
    private String branch;
    private String fromVersion;
    private String version;
    private List<String> models;
    private int trials = 3;

    public BenchmarkRequest() {}

    @JsonProperty("project")
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("fromVersion")
    public String getFromVersion() { return fromVersion; }
    public void setFromVersion(String fromVersion) { this.fromVersion = fromVersion; }

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    /** Model ids to benchmark; defaults to the app's recommended set when omitted. */
    @JsonProperty("models")
    public List<String> getModels() { return models; }
    public void setModels(List<String> models) { this.models = models; }

    /** Calls per model; clamped to [1, 10] to keep a single benchmark run bounded. */
    @JsonProperty("trials")
    public int getTrials() { return trials; }
    public void setTrials(int trials) { this.trials = trials; }
}
