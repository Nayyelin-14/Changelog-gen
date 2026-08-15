package com.hubsabai.changelog.api;

import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.storage.RecordedRunService;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.logging.Logger;

/** GitHub workflow-run intake — the GitHub counterpart of {@link PipelineResource}. Takes a GitHub
 * Actions workflow run ID ({@code github.run_id}) and captures the raw, non-AI changelog snapshot
 * onto {@code recorded_pipeline_run}, keyed by {@code (provider, project, repo, build_id)}. The
 * response's {@code changelog} can be committed straight into the repo's CHANGELOG.md by the
 * calling workflow. {@code version} is optional — raw capture is version-free; AI/editorial output
 * stays a separate, version-keyed dashboard flow. Idempotent: re-POSTing the same run ID overwrites
 * the stored snapshot instead of duplicating it. */
@Path("/github/pipeline")
@PipelineAuth
public class GitHubPipelineResource {

    private static final Logger LOG = Logger.getLogger(GitHubPipelineResource.class.getName());

    @Inject
    RecordedRunService recordedRunService;

    @POST
    @Path("/generate")
    @Produces(MediaType.APPLICATION_JSON)
    public GitHubPipelineIngestResponse generate(GitHubPipelineRequest request) {
        if (request == null || request.getRunId() == null) {
            throw new AiException("runId is required.");
        }
        if (request.getProject() == null || request.getProject().isBlank()) {
            throw new AiException("project is required.");
        }
        if (request.getRepo() == null || request.getRepo().isBlank()) {
            throw new AiException("repo is required.");
        }

        RecordedRunService.GitHubCaptureResult result = recordedRunService.captureGitHubRun(
                request.getProject(), request.getRepo(), request.getRunId(), request.getVersion());

        if (result.fetch().releaseData().getItems().isEmpty()) {
            LOG.warning("No commits or PRs found for workflow run " + request.getRunId()
                    + " on " + request.getProject() + "/" + request.getRepo()
                    + " — snapshot recorded, changelog empty.");
        }

        int prCount = (int) result.fetch().releaseData().getItems().stream()
                .filter(item -> item.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .count();

        return new GitHubPipelineIngestResponse(request.getRunId(), request.getProject(), request.getRepo(),
                request.getVersion(), request.getBranch(), prCount, result.rawChangelog());
    }
}