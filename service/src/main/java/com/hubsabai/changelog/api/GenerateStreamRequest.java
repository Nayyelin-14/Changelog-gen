package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body for {@code POST /generate-stream} — moved out of query params because {@code manualText}
 * (the raw commit/PR/work-item text pasted or fetched for a build) can run into the tens of
 * thousands of characters, which blows past a URL's length limit (HTTP 414) long before it blows
 * past a JSON body's. */
public class GenerateStreamRequest {

    private String model;
    private String fromVersion;
    private String version;
    private String branch;
    private String manualText;
    private boolean force;
    private Long buildId;

    public GenerateStreamRequest() {}

    @JsonProperty("model")
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @JsonProperty("buildId")
    public Long getBuildId() { return buildId; }
    public void setBuildId(Long buildId) { this.buildId = buildId; }

    @JsonProperty("fromVersion")
    public String getFromVersion() { return fromVersion; }
    public void setFromVersion(String fromVersion) { this.fromVersion = fromVersion; }

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("manualText")
    public String getManualText() { return manualText; }
    public void setManualText(String manualText) { this.manualText = manualText; }

    @JsonProperty("force")
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
}
