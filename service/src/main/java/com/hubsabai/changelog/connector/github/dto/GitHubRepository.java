package com.hubsabai.changelog.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepository(
        @JsonProperty("id") long id,
        @JsonProperty("name") String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("description") String description,
        @JsonProperty("visibility") String visibility,
        @JsonProperty("private") boolean isPrivate,
        @JsonProperty("owner") Owner owner,
        @JsonProperty("pushed_at") String pushedAt) {

    public String ownerLogin() {
        return owner != null ? owner.login() : fullName != null && fullName.contains("/")
                ? fullName.substring(0, fullName.indexOf('/')) : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(@JsonProperty("login") String login) {}
}
