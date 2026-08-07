package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body element for {@code POST .../refs} (creating a branch) and one entry of a
 * {@code GitPushRequest}'s {@code refUpdates}. {@code newObjectId} is required to create a ref
 * (the branch's starting commit) but omitted for a push onto an existing ref (the push's own
 * commit supplies the new tip) — {@code @JsonInclude(NON_NULL)} drops it from the JSON when null
 * rather than sending an explicit {@code null} Azure DevOps doesn't expect there.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefUpdate(
        @JsonProperty("name") String name,
        @JsonProperty("oldObjectId") String oldObjectId,
        @JsonProperty("newObjectId") String newObjectId) {

    /** All-zero SHA Azure DevOps requires as {@code oldObjectId} to mark "this ref doesn't exist yet." */
    public static final String NEW_REF_OLD_OBJECT_ID = "0000000000000000000000000000000000000000";

    /** For creating a brand-new branch ref pointed at {@code baseCommitSha}. */
    public static RefUpdate create(String refName, String baseCommitSha) {
        return new RefUpdate(refName, NEW_REF_OLD_OBJECT_ID, baseCommitSha);
    }

    /** For a push's refUpdates entry — the new tip comes from the push's own commit, not this. */
    public static RefUpdate push(String refName, String currentCommitSha) {
        return new RefUpdate(refName, currentCommitSha, null);
    }
}
