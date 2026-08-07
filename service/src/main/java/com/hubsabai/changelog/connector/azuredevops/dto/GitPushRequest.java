package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Request body for {@code POST .../pushes} — one or more file edits committed onto a ref. */
public record GitPushRequest(
        @JsonProperty("refUpdates") List<RefUpdate> refUpdates,
        @JsonProperty("commits") List<Commit> commits) {

    public record Commit(
            @JsonProperty("comment") String comment,
            @JsonProperty("changes") List<Change> changes) {
    }

    public record Change(
            @JsonProperty("changeType") String changeType,
            @JsonProperty("item") Item item,
            @JsonProperty("newContent") Content newContent) {
    }

    public record Item(@JsonProperty("path") String path) {
    }

    /** {@code contentType: "rawtext"} means {@code content} is the plain file text — no base64. */
    public record Content(
            @JsonProperty("content") String content,
            @JsonProperty("contentType") String contentType) {
    }

    /** One edit to one existing file, on top of {@code baseCommitSha}. */
    public static GitPushRequest editFile(String refName, String baseCommitSha, String path, String newContent, String comment) {
        return new GitPushRequest(
                List.of(RefUpdate.push(refName, baseCommitSha)),
                List.of(new Commit(comment, List.of(new Change("edit", new Item(path), new Content(newContent, "rawtext"))))));
    }

    /** Same as {@link #editFile}, but for a path that doesn't exist on the branch yet — Azure
     * DevOps' push API distinguishes "add" from "edit" and rejects an "edit" whose path is new. */
    public static GitPushRequest addFile(String refName, String baseCommitSha, String path, String newContent, String comment) {
        return new GitPushRequest(
                List.of(RefUpdate.push(refName, baseCommitSha)),
                List.of(new Commit(comment, List.of(new Change("add", new Item(path), new Content(newContent, "rawtext"))))));
    }
}
