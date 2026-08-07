package com.hubsabai.changelog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.connector.ConnectionConfig;
import com.hubsabai.changelog.connector.SourceConnector;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector;
import com.hubsabai.changelog.connector.azuredevops.PlainBullets;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import com.hubsabai.changelog.storage.ChangelogService;
import com.hubsabai.changelog.storage.RawReleaseService;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/** Pipeline intake for raw release facts. No AI call — plain non-AI bullet list only. Two modes:
 * bundled (default, records what the pipeline knows about a whole release) and per-PR ({@code raw},
 * see {@link #generateRaw}). The response's {@code changelog} is saved as the starting Developer
 * draft and returned for the calling CI to commit to its own CHANGELOG.md. AI-generated text is
 * exclusively a dashboard action and always overrides this raw draft. */
@Path("/pipeline")
@PipelineAuth
public class PipelineResource {

    private static final Logger LOG = Logger.getLogger(PipelineResource.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_STAGES = Set.of("prerelease", "release");

    @Inject
    SourceConnector sourceConnector;

    @Inject
    RawReleaseService rawReleaseService;

    @Inject
    AzureDevOpsOrgConnector orgConnector;

    @Inject
    ChangelogCacheService cacheService;

    @Inject
    ChangelogService changelogService;

    @POST
    @Path("/generate")
    @Produces(MediaType.APPLICATION_JSON)
    public PipelineIngestResponse generate(PipelineRequest request) {
        if (request.getProject() == null || request.getProject().isBlank()) {
            throw new AiException("project is required.");
        }
        if (request.getRepo() == null || request.getRepo().isBlank()) {
            throw new AiException("repo is required.");
        }
        if (request.getVersion() == null || request.getVersion().isBlank()) {
            // Best-effort intake, same treatment as an empty commit/PR list below: without a
            // version there's nothing to key raw_release/generated_changelog on (both NOT NULL
            // at the DB level), but a caller that omits it must never have its build fail over
            // this — log and no-op instead of rejecting.
            LOG.warning("No version reported for " + request.getProject() + "/" + request.getRepo()
                    + " (buildId=" + request.getBuildId() + ") — skipping ingest, nothing to key it on.");
            return new PipelineIngestResponse(request.getProject(), request.getRepo(), null,
                    request.getStage(), 0, null);
        }

        if (request.isRaw()) {
            return generateRaw(request);
        }

        // Defaults to "release" rather than rejecting the call: stage only matters for the
        // prerelease/release PR-location tracking below, and a caller that doesn't send one
        // shouldn't have its build fail over it.
        String stage = request.getStage() != null && VALID_STAGES.contains(request.getStage()) ? request.getStage() : "release";

        ReleaseData data;
        if (request.getBuildId() != null) {
            // Pipeline-run flow: the Composer fetches this run's own commits/work items/PRs
            // straight from Azure DevOps' Build API — rawCommitLog/workItemIds/prIds are ignored.
            data = orgConnector.fetchRunChanges(request.getProject(), request.getRepo(), request.getBuildId());
        } else {
            ConnectionConfig config = new ConnectionConfig();
            config.setProject(request.getProject());
            config.setRepo(request.getRepo());
            config.setBranch(request.getBranch());
            config.setRawCommitLog(request.getRawCommitLog());
            config.setWorkItemIds(request.getWorkItemIds());
            config.setPrIds(request.getPrIds());
            data = sourceConnector.fetch(config);
        }

        if (data.getItems().isEmpty()) {
            // Best-effort intake, not a release gate: the calling pipeline's own build/publish
            // must never fail just because this lookup came up empty (e.g. Azure DevOps' Build
            // Changes API can legitimately report nothing for a given buildId). The dashboard's
            // own Generate flow re-fetches this version's data live and doesn't depend on this
            // call having succeeded, so there's nothing to gain by failing the caller here.
            LOG.warning("No commits or PRs found for " + request.getProject() + "/" + request.getRepo()
                    + " v" + request.getVersion() + " on branch "
                    + (request.getBranch() != null ? request.getBranch() : "(default)")
                    + " (buildId=" + request.getBuildId() + ") — skipping ingest, nothing recorded.");
            return new PipelineIngestResponse(request.getProject(), request.getRepo(), request.getVersion(),
                    stage, 0, null);
        }

        String project = request.getProject();
        String repo = request.getRepo();
        String version = request.getVersion();

        Set<Integer> prIds = new HashSet<>();
        for (ChangeItem item : data.getItems()) {
            if (item.getType() != ChangeItem.ItemType.PULL_REQUEST || item.getId() == null) {
                continue;
            }
            try {
                prIds.add(Integer.valueOf(item.getId()));
            } catch (NumberFormatException e) {
                // Not a plain numeric PR id — nothing to index it under, skip rather than fail
                // the whole ingestion over one malformed item.
            }
        }

        String itemsJson;
        try {
            itemsJson = MAPPER.writeValueAsString(data.getItems());
        } catch (Exception e) {
            throw new AiException("Failed to serialize release items: " + e.getMessage());
        }

        rawReleaseService.ingest(project, repo, request.getBranch(), version, stage, itemsJson, prIds);

        // Same plain, non-AI bullet list as the raw-init flow below — saved here as this
        // version's starting Developer draft, and handed back to the caller so its own CI can
        // commit it straight into its own CHANGELOG.md without waiting on a human or an AI call.
        String rawChangelog = PlainBullets.plainBullets(data.getItems());
        boolean wasNew = cacheService.saveRawInitIfAbsent(project, repo, version, "developer", rawChangelog);
        if (wasNew) {
            long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null).id;
            changelogService.createRawRevision(vid, "developer", rawChangelog);
        }

        return new PipelineIngestResponse(project, repo, version, stage, prIds.size(), rawChangelog);
    }

    /**
     * PR-merge raw init: no AI, no rawCommitLog/workItemIds/prIds bundle — just one PR number.
     * The Composer fetches that PR's own title/description/commits/linked work items itself and
     * writes a plain-bullet Developer changelog draft, immediately, guaranteed never to touch
     * {@code aiProvider}. A developer can later overwrite this draft with a real AI generation
     * from the dashboard (existing Generate/Regenerate flow) — this never happens automatically.
     */
    private PipelineIngestResponse generateRaw(PipelineRequest request) {
        if (request.getPullRequestId() == null) {
            throw new AiException("pullRequestId is required when raw is true.");
        }

        String project = request.getProject();
        String repo = request.getRepo();
        String version = request.getVersion();
        int prId = request.getPullRequestId();

        AzureDevOpsOrgConnector.PullRequestDetails details;
        try {
            details = orgConnector.fetchPullRequestDetails(project, repo, prId);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                throw new AiException("Pull request #" + prId + " not found.");
            }
            throw e;
        }

        String rawText = PlainBullets.plainBullets(details);
        boolean wasNew = cacheService.saveRawInitIfAbsent(project, repo, version, "developer", rawText);
        if (wasNew) {
            long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null).id;
            changelogService.createRawRevision(vid, "developer", rawText);
        }

        return new PipelineIngestResponse(project, repo, version, request.getStage(), 1, rawText);
    }
}
