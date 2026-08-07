package com.hubsabai.changelog.connector.azuredevops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** One pipeline run (Classic Build API). {@code triggerInfo} carries {@code "pr.number"} when the
 * run itself was triggered by a PR, letting the pipeline-run changelog flow pick that PR up even
 * if its merge commit text doesn't match the usual "Merged PR N" pattern. {@code buildNumber},
 * {@code status}/{@code result}, {@code finishTime}, and {@code definition} are only needed for
 * listing runs on the dashboard (not for the single-run changelog fetch, which only needs the
 * other four fields). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuildResponse(
        @JsonProperty("id") int id,
        @JsonProperty("buildNumber") String buildNumber,
        @JsonProperty("status") String status,
        @JsonProperty("result") String result,
        @JsonProperty("finishTime") String finishTime,
        @JsonProperty("sourceBranch") String sourceBranch,
        @JsonProperty("sourceVersion") String sourceVersion,
        @JsonProperty("definition") BuildDefinitionRef definition,
        @JsonProperty("triggerInfo") Map<String, String> triggerInfo) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildDefinitionRef(@JsonProperty("id") int id, @JsonProperty("name") String name) {}
}
