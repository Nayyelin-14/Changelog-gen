package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommit(
        @JsonProperty("sha") String sha,
        @JsonProperty("commit") GitHubCommitDetail commit,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("parents") List<GitHubCommitParent> parents,
        @JsonProperty("files") List<GitHubFile> files,
        @JsonProperty("tree") GitHubTree tree) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitDetail(
            @JsonProperty("author") GitHubCommitIdentity author,
            @JsonProperty("committer") GitHubCommitIdentity committer,
            @JsonProperty("message") String message,
            @JsonProperty("parents") List<GitHubCommitParent> parents) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitParent(
            @JsonProperty("sha") String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitIdentity(
            @JsonProperty("name") String name,
            @JsonProperty("email") String email,
            @JsonProperty("date") String date) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubFile(
            @JsonProperty("filename") String filename) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubTree(
            @JsonProperty("sha") String sha) {}
}