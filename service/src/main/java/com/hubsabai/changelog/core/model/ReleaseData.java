package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ReleaseData {

    private ReleaseMeta release;
    private List<ChangeItem> items;

    public static class ReleaseMeta {
        private String org;
        private String project;
        private String repo;
        private String branch;
        private String milestone;
        private String releaseDate;

        public ReleaseMeta() {}

        @JsonProperty("org")
        public String getOrg() { return org; }
        public void setOrg(String org) { this.org = org; }

        @JsonProperty("project")
        public String getProject() { return project; }
        public void setProject(String project) { this.project = project; }

        @JsonProperty("repo")
        public String getRepo() { return repo; }
        public void setRepo(String repo) { this.repo = repo; }

        @JsonProperty("branch")
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }

        @JsonProperty("milestone")
        public String getMilestone() { return milestone; }
        public void setMilestone(String milestone) { this.milestone = milestone; }

        @JsonProperty("releaseDate")
        public String getReleaseDate() { return releaseDate; }
        public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    }

    public ReleaseData() {}

    @JsonProperty("release")
    public ReleaseMeta getRelease() { return release; }
    public void setRelease(ReleaseMeta release) { this.release = release; }

    @JsonProperty("items")
    public List<ChangeItem> getItems() { return items; }
    public void setItems(List<ChangeItem> items) { this.items = items; }
}