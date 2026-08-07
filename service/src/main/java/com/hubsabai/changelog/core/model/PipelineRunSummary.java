package com.hubsabai.changelog.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One pipeline run for a repo's dashboard "Pipeline runs" list — enough to show and pick a run
 * to generate a changelog from (via its {@code buildId}), without the caller needing to know
 * which pipeline definition(s) build this repo. */
public record PipelineRunSummary(
        @JsonProperty("buildId") int buildId,
        @JsonProperty("buildNumber") String buildNumber,
        @JsonProperty("status") String status,
        @JsonProperty("result") String result,
        @JsonProperty("finishTime") String finishTime,
        @JsonProperty("sourceBranch") String sourceBranch,
        @JsonProperty("sourceVersion") String sourceVersion,
        @JsonProperty("pipelineName") String pipelineName,
        @JsonProperty("prNumber") Integer prNumber,
        @JsonProperty("commitTitle") String commitTitle) {
}
