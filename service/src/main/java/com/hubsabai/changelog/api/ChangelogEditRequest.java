package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ChangelogEditRequest {

    private String version;
    private String branch;
    private String audience;
    private String text;
    private String editedBy;

    public ChangelogEditRequest() {}

    @JsonProperty("version")
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    @JsonProperty("branch")
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    @JsonProperty("audience")
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    @JsonProperty("text")
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    @JsonProperty("editedBy")
    public String getEditedBy() { return editedBy; }
    public void setEditedBy(String editedBy) { this.editedBy = editedBy; }
}
