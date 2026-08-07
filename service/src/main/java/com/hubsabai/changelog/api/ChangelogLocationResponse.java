package com.hubsabai.changelog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Where (if anywhere) a PR's changes actually landed — backs the deep-link feature that lets an
 * external dashboard jump straight from a PR number to its changelog. Built from the
 * {@code release_pr} index, populated at pipeline-ingestion time, never from searching generated
 * changelog text — see RawReleaseService. */
public class ChangelogLocationResponse {

    /** {@code released}, {@code prerelease}, or {@code not_found}. */
    private final String status;
    private final String version;
    private final String stage;

    public ChangelogLocationResponse(String status, String version, String stage) {
        this.status = status;
        this.version = version;
        this.stage = stage;
    }

    @JsonProperty("status")
    public String getStatus() { return status; }

    @JsonProperty("version")
    public String getVersion() { return version; }

    @JsonProperty("stage")
    public String getStage() { return stage; }
}
