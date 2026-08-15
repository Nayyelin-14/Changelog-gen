package com.hubsabai.changelog.api;

import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector;
import com.hubsabai.changelog.connector.github.GitHubOrgConnector;
import com.hubsabai.changelog.connector.github.RunFetchResult;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.PipelineRunSummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.generation.RunChangeContext;
import com.hubsabai.changelog.storage.RecordedPipelineRun;
import com.hubsabai.changelog.storage.RecordedRunService;
import com.hubsabai.changelog.storage.RawReleaseService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/pipeline/runs")
@Produces(MediaType.APPLICATION_JSON)
public class PipelineRunResource {

    @Inject
    RecordedRunService recordedRunService;

    @Inject
    AzureDevOpsOrgConnector azureConnector;

    @Inject
    GitHubOrgConnector githubConnector;

    @Inject
    RawReleaseService rawReleaseService;

    @GET
    public List<PipelineRunSummary> listRecordedRuns(
            @QueryParam("provider") String provider,
            @QueryParam("project") String project,
            @QueryParam("repo") String repo) {
        if (project == null || repo == null) {
            return List.of();
        }
        String effectiveProvider = provider != null ? provider : "azure";
        List<RecordedPipelineRun> runs = recordedRunService.listRecordedRuns(effectiveProvider, project, repo);

        // Always fetch live provider runs too — recorded runs alone would hide the full workflow
        // history (only runs the pipeline already reported are stored). Recorded runs win where a
        // buildId overlaps, since they carry version metadata; live runs fill in the rest.
        Map<Long, PipelineRunSummary> byBuildId = new java.util.LinkedHashMap<>();
        for (RecordedPipelineRun run : runs) {
            byBuildId.put(run.buildId, toPipelineRunSummary(run));
        }
        try {
            List<PipelineRunSummary> live;
            if ("github".equals(effectiveProvider)) {
                live = githubConnector.listWorkflowRuns(project, repo, 20);
            } else {
                live = azureConnector.listRecentBuilds(project, repo, 20);
            }
            for (PipelineRunSummary summary : live) {
                byBuildId.putIfAbsent(summary.buildId(), summary);
            }
        } catch (Exception e) {
            // Log but don't fail - return whatever we have
        }

        return new java.util.ArrayList<>(byBuildId.values());
    }

    @GET
    @Path("/{runId}")
    public RecordedRunDetail getRecordedRun(
            @QueryParam("provider") String provider,
            @QueryParam("project") String project,
            @QueryParam("repo") String repo,
            @PathParam("runId") Long runId) {
        if (project == null || repo == null) {
            return null;
        }
        String effectiveProvider = provider != null ? provider : "azure";
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(effectiveProvider, project, repo, runId);
        if (run == null) {
            return null;
        }
        return new RecordedRunDetail(run);
    }

    @GET
    @Path("/{runId}/draft")
    public AiDraftDto getAiDraft(
            @QueryParam("provider") String provider,
            @QueryParam("project") String project,
            @QueryParam("repo") String repo,
            @PathParam("runId") Long runId) {
        if (project == null || repo == null || runId == null) {
            return new AiDraftDto(null, null, null, null, null);
        }
        String effectiveProvider = provider != null ? provider : "azure";
        Optional<RecordedPipelineRun> run = recordedRunService.getAiDraft(effectiveProvider, project, repo, runId);
        return run
                .map(r -> new AiDraftDto(r.aiDraftAudience, r.aiDraftText, r.aiDraftModel,
                        r.aiDraftTokens != null ? r.aiDraftTokens.intValue() : null,
                        r.aiDraftDurationMs != null ? r.aiDraftDurationMs.intValue() : null))
                .orElseGet(() -> new AiDraftDto(null, null, null, null, null));
    }

    @GET
    @Path("/{runId}/changes")
    public ReleaseData getRecordedRunChanges(
            @QueryParam("provider") String provider,
            @QueryParam("project") String project,
            @QueryParam("repo") String repo,
            @PathParam("runId") Long runId) {
        if (project == null || repo == null) {
            return emptyReleaseData(project, repo);
        }
        String effectiveProvider = provider != null ? provider : "azure";

        Optional<ReleaseData> stored = recordedRunService.getRecordedRunData(effectiveProvider, project, repo, runId);
        if (stored.isPresent()) {
            return stored.get();
        }

        // Fallback: live fetch from provider. For GitHub this is a lazy capture — the snapshot
        // is persisted to recorded_pipeline_run so the dashboard never re-fetches GitHub again.
        try {
            if ("github".equals(effectiveProvider)) {
                return recordedRunService.getOrCaptureGitHubRun(project, repo, runId)
                        .map(RunFetchResult::releaseData)
                        .orElseGet(() -> emptyReleaseData(project, repo));
            } else {
                return azureConnector.fetchRunChanges(project, repo, runId.intValue());
            }
        } catch (Exception e) {
            return emptyReleaseData(project, repo);
        }
    }

    @GET
    @Path("/{runId}/run-context")
    public RunChangeContext getRecordedRunContext(
            @QueryParam("provider") String provider,
            @QueryParam("project") String project,
            @QueryParam("repo") String repo,
            @PathParam("runId") Long runId) {
        if (project == null || repo == null) {
            return new RunChangeContext();
        }
        String effectiveProvider = provider != null ? provider : "azure";

        Optional<RunChangeContext> stored = recordedRunService.getRecordedRunContext(effectiveProvider, project, repo, runId);
        if (stored.isPresent()) {
            return stored.get();
        }

        // Fallback: live fetch from provider. For GitHub this is a lazy capture — the snapshot
        // is persisted to recorded_pipeline_run so the dashboard never re-fetches GitHub again.
        try {
            if ("github".equals(effectiveProvider)) {
                return recordedRunService.getOrCaptureGitHubRun(project, repo, runId)
                        .map(RunFetchResult::runContext)
                        .orElseGet(RunChangeContext::new);
            } else {
                return azureConnector.fetchRunContext(project, repo, runId.intValue());
            }
        } catch (Exception e) {
            return new RunChangeContext();
        }
    }

    private PipelineRunSummary toPipelineRunSummary(RecordedPipelineRun run) {
        String pipelineName = null;
        String status = null;
        String result = null;
        String finishTime = null;
        String commitTitle = null;
        String sourceVersion = null;
        String runNumber = null;
        if (run.runMetadata != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.runMetadata);
                if (node.has("run")) {
                    com.fasterxml.jackson.databind.JsonNode runNode = node.get("run");
                    if (runNode.has("pipelineName")) pipelineName = runNode.get("pipelineName").asText();
                    if (runNode.has("status")) status = runNode.get("status").asText();
                    if (runNode.has("result")) result = runNode.get("result").asText();
                    if (runNode.has("finishedAt")) finishTime = runNode.get("finishedAt").asText();
                    if (runNode.has("providerCommitMessage")) commitTitle = runNode.get("providerCommitMessage").asText();
                    if (runNode.has("headSha")) sourceVersion = runNode.get("headSha").asText();
                    if (runNode.has("runNumber")) runNumber = runNode.get("runNumber").asText();
                }
            } catch (Exception ignored) {
            }
        }
        // Fallback for finishTime if not in metadata
        if (finishTime == null && run.updatedAt != null) {
            finishTime = run.updatedAt.toString();
        }
        // For recorded runs, buildNumber is the version field; pipelineRunNumber is extracted from
        // the run metadata's runNumber, falling back to String.valueOf(buildId) if missing.
        String pipelineRunNumber = runNumber != null ? runNumber : String.valueOf(run.buildId);
        return new PipelineRunSummary(
                run.buildId,
                run.version,
                pipelineRunNumber,
                status,
                result,
                finishTime,
                run.branch,
                sourceVersion,
                pipelineName,
                null,
                commitTitle
        );
    }

    private ReleaseData emptyReleaseData(String project, String repo) {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setProject(project);
        meta.setRepo(repo);
        data.setRelease(meta);
        data.setItems(List.of());
        return data;
    }

    public static class RecordedRunDetail {
        public Long id;
        public String provider;
        public String project;
        public String repo;
        public Long buildId;
        public String version;
        public String stage;
        public String branch;
        public String runMetadata;
        public String changeItems;
        public String createdAt;
        public String updatedAt;

        public RecordedRunDetail() {}

        public RecordedRunDetail(RecordedPipelineRun run) {
            this.id = run.id;
            this.provider = run.provider;
            this.project = run.project;
            this.repo = run.repo;
            this.buildId = run.buildId;
            this.version = run.version;
            this.stage = run.stage;
            this.branch = run.branch;
            this.runMetadata = run.runMetadata;
            this.changeItems = run.changeItems;
            this.createdAt = run.createdAt != null ? run.createdAt.toString() : null;
            this.updatedAt = run.updatedAt != null ? run.updatedAt.toString() : null;
        }
    }

    /** A saved (version-free) AI draft on a recorded pipeline run — the Save action's artifact.
     * Null fields mean no draft is saved for this run. */
    public static class AiDraftDto {
        public String audience;
        public String text;
        public String model;
        public Integer tokens;
        public Integer durationMs;

        public AiDraftDto() {}

        public AiDraftDto(String audience, String text, String model, Integer tokens, Integer durationMs) {
            this.audience = audience;
            this.text = text;
            this.model = model;
            this.tokens = tokens;
            this.durationMs = durationMs;
        }
    }
}