package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ChangeItem {
    private ItemType type;
    private String id;
    private String title;
    private String category;
    private String description;
    private String author;
    private String project;
    private String repo;
    private String date;
    private List<String> links;
    private List<String> filePaths;

    public enum ItemType {
        WORK_ITEM,
        PULL_REQUEST,
        COMMIT
    }

    public ChangeItem() {}

    @JsonProperty("type")
    public ItemType getType() { return type; }
    public void setType(ItemType type) { this.type = type; }

    @JsonProperty("id")
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @JsonProperty("title")
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    @JsonProperty("category")
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    @JsonProperty("description")
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @JsonProperty("author")
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    @JsonProperty("project")
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    @JsonProperty("repo")
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    @JsonProperty("date")
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    @JsonProperty("links")
    public List<String> getLinks() { return links; }
    public void setLinks(List<String> links) { this.links = links; }

    @JsonProperty("filePaths")
    public List<String> getFilePaths() { return filePaths; }
    public void setFilePaths(List<String> filePaths) { this.filePaths = filePaths; }
}
