package com.hubsabai.changelog.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector;
import com.hubsabai.changelog.connector.azuredevops.PlainBullets;
import com.hubsabai.changelog.connector.github.GitHubOrgConnector;
import com.hubsabai.changelog.connector.github.RunFetchResult;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.generation.RunChangeContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
public class RecordedRunService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = Logger.getLogger(RecordedRunService.class.getName());

    @Inject
    AzureDevOpsOrgConnector azureConnector;

    @Inject
    GitHubOrgConnector githubConnector;

    @Inject
    RawReleaseService rawReleaseService;

    @Transactional
    public RecordedPipelineRun recordRun(String provider, String project, String repo, Long buildId,
            String version, String stage, String branch, RunChangeContext context, List<ChangeItem> changeItems) {
        return persistRun(provider, project, repo, buildId, version, stage, branch, context, changeItems, null);
    }

    @Transactional
    public RecordedPipelineRun recordRun(String provider, String project, String repo, Long buildId,
            String version, String stage, String branch, RunChangeContext context, List<ChangeItem> changeItems,
            String rawChangelog) {
        return persistRun(provider, project, repo, buildId, version, stage, branch, context, changeItems, rawChangelog);
    }

    /**
     * Legacy method for Azure DevOps - fetches data internally (for backward compatibility).
     * Prefer using {@link #recordRun(String, String, String, Long, String, String, String, RunChangeContext, List)} with pre-fetched data.
     */
    @Transactional
    public RecordedPipelineRun recordAzureRun(String project, String repo, int buildId, String version, String stage, String branch) {
        com.hubsabai.changelog.connector.azuredevops.RunFetchResult result = azureConnector.fetchRunData(project, repo, buildId);
        return persistRun("azure", project, repo, (long) buildId, version, stage, branch, result.runContext(), result.releaseData().getItems(), null);
    }

    /**
     * The single capture implementation for GitHub workflow runs, used by both the eager intake
     * endpoint ({@code POST /api/github/pipeline/generate}) and the lazy dashboard fallback.
     * Fetches run data from GitHub, builds the raw (version-free) changelog bullet list and
     * persists the snapshot onto {@code recorded_pipeline_run}. Upsert on the existing unique
     * constraint {@code (provider, project, repo, build_id)} keeps the call idempotent — a second
     * capture overwrites the snapshot instead of duplicating it.
     */
    @Transactional
    public GitHubCaptureResult captureGitHubRun(String project, String repo, long buildId, String version) {
        RunFetchResult fetch = githubConnector.fetchRunData(project, repo, buildId);
        if (fetch.runContext().getRun() == null) {
            throw new AiException("Workflow run " + buildId + " not found or unreachable on " + project + "/" + repo + ".");
        }
        String rawChangelog = PlainBullets.plainBullets(fetch.releaseData().getItems());
        String branch = fetch.runContext().getRun().getBranch();
        RecordedPipelineRun run = persistRun("github", project, repo, buildId, version, null, branch,
                fetch.runContext(), fetch.releaseData().getItems(), rawChangelog);
        return new GitHubCaptureResult(fetch, rawChangelog, run);
    }

    /**
     * Stored-first lookup for a GitHub workflow run: returns the recorded snapshot when present,
     * otherwise performs a lazy capture that persists the snapshot ({@link #captureGitHubRun}).
     * Never returns empty for a resolvable run — a missing/unreachable run throws.
     */
    @Transactional
    public Optional<RunFetchResult> getOrCaptureGitHubRun(String project, String repo, long buildId) {
        if (RecordedPipelineRun.findByBuildId("github", project, repo, buildId) != null) {
            Optional<ReleaseData> data = getRecordedRunData("github", project, repo, buildId);
            Optional<RunChangeContext> context = getRecordedRunContext("github", project, repo, buildId);
            if (data.isPresent() && context.isPresent()) {
                return Optional.of(new RunFetchResult(data.get(), context.get()));
            }
            // Stored snapshot failed to deserialize — recapture (upsert heals the row).
        }
        return Optional.of(captureGitHubRun(project, repo, buildId, null).fetch());
    }

    /**
     * Legacy method for GitHub - fetches and persists run data internally (for backward compatibility).
     * Prefer {@link #captureGitHubRun(String, String, long, String)}.
     */
    @Transactional
    public RecordedPipelineRun recordGitHubRun(String project, String repo, long buildId, String version, String stage, String branch) {
        return captureGitHubRun(project, repo, buildId, version).run();
    }

    private RecordedPipelineRun persistRun(String provider, String project, String repo, Long buildId,
            String version, String stage, String branch, RunChangeContext context, List<ChangeItem> changeItems,
            String rawChangelog) {
        String runMetadataJson;
        String changeItemsJson;
        try {
            runMetadataJson = MAPPER.writeValueAsString(context);
            changeItemsJson = MAPPER.writeValueAsString(changeItems);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize run data: " + e.getMessage(), e);
        }

        RecordedPipelineRun existing = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (existing != null) {
            existing.version = version;
            existing.stage = stage;
            existing.branch = branch;
            existing.runMetadata = runMetadataJson;
            existing.changeItems = changeItemsJson;
            existing.rawChangelog = rawChangelog;
            existing.updatedAt = OffsetDateTime.now();
            existing.persist();
            return existing;
        }

        RecordedPipelineRun run = new RecordedPipelineRun();
        run.provider = provider;
        run.project = project;
        run.repo = repo;
        run.buildId = buildId;
        run.version = version;
        run.stage = stage;
        run.branch = branch;
        run.runMetadata = runMetadataJson;
        run.changeItems = changeItemsJson;
        run.rawChangelog = rawChangelog;
        run.createdAt = OffsetDateTime.now();
        run.updatedAt = OffsetDateTime.now();
        run.persist();
        return run;
    }

    public Optional<ReleaseData> getRecordedRunData(String provider, String project, String repo, Long buildId) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null || run.changeItems == null) {
            return Optional.empty();
        }
        try {
            List<ChangeItem> items = MAPPER.readValue(run.changeItems, new TypeReference<List<ChangeItem>>() {});
            ReleaseData data = new ReleaseData();
            ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
            meta.setProject(project);
            meta.setRepo(repo);
            meta.setBranch(run.branch);
            meta.setMilestone(run.version);
            data.setRelease(meta);
            data.setItems(items);
            return Optional.of(data);
        } catch (Exception e) {
            LOG.warning("Failed to deserialize recorded run data for " + provider + "/" + project + "/" + repo + "/" + buildId + ": " + e);
            return Optional.empty();
        }
    }

    public Optional<RunChangeContext> getRecordedRunContext(String provider, String project, String repo, Long buildId) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null || run.runMetadata == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(run.runMetadata, RunChangeContext.class));
        } catch (Exception e) {
            LOG.warning("Failed to deserialize recorded run context for " + provider + "/" + project + "/" + repo + "/" + buildId + ": " + e);
            return Optional.empty();
        }
    }

    public List<RecordedPipelineRun> listRecordedRuns(String provider, String project, String repo) {
        return RecordedPipelineRun.find("provider = ?1 and project = ?2 and repo = ?3 order by createdAt desc",
                provider, project, repo).list();
    }

    /** Recorded runs that carry at least one saved per-audience draft — the Save action's artifact.
     * Used by {@code /history} so a saved draft shows up on the dashboard even before it has a
     * version. Checks the new per-audience {@code recorded_run_draft} table. */
    public List<RecordedPipelineRun> listDraftRuns(String provider, String project, String repo) {
        List<RecordedPipelineRun> runs = RecordedPipelineRun.find(
                "provider = ?1 and project = ?2 and repo = ?3 order by createdAt desc",
                provider, project, repo).list();
        return runs.stream()
                .filter(r -> RecordedRunDraft.findAllByRun(r.id).stream()
                        .anyMatch(d -> d.draftText != null && !d.draftText.isBlank()))
                .toList();
    }

    /** One point-in-time draft revision for a version-free recorded run — mirrors the shape the
     * UI already shows for a version's {@link com.hubsabai.changelog.api.ChangelogRevisionDto}, but
     * scoped to a single audience. */
    public record DraftRevision(Long sequence, String audience, String source, String model, Long tokens,
            Long durationMs, String editedBy, String text, String createdAt) {}

    /** How many prior drafts to keep in {@code draft_history}; the current text is excluded. */
    private static final int MAX_DRAFT_HISTORY = 25;

    @SuppressWarnings("unchecked")
    private static List<DraftRevision> readDraftHistory(RecordedRunDraft draft) {
        if (draft.draftHistory == null || draft.draftHistory.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(draft.draftHistory, new TypeReference<List<DraftRevision>>() {});
        } catch (Exception e) {
            LOG.warning("Could not parse draft_history for run " + draft.run.id + " audience " + draft.audience
                    + " — starting fresh: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static String writeDraftHistory(List<DraftRevision> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(history);
        } catch (Exception e) {
            LOG.warning("Could not serialize draft_history — dropping it: " + e.getMessage());
            return null;
        }
    }

    /**
     * Persists a version-free AI draft for a specific audience on a recorded pipeline run.
     * Each audience has its own independent row in {@code recorded_run_draft} — saving QA
     * never overwrites Developer or Business. The previous current text for that audience is
     * pushed into its draft history so past drafts stay browsable and restorable.
     */
    @Transactional
    public void saveAiDraft(String provider, String project, String repo, Long buildId,
            String audience, String source, String editedBy, String model, String text,
            Integer tokens, Integer durationMs) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null) {
            throw new AiException("No recorded pipeline run for build " + buildId
                    + " on " + project + "/" + repo + " — nothing to save a draft against.");
        }
        RecordedRunDraft draft = RecordedRunDraft.findByRunAndAudience(run.id, audience);
        if (draft == null) {
            draft = new RecordedRunDraft();
            draft.run = run;
            draft.audience = audience;
        } else {
            // Push the existing current text into history before overwriting
            if (draft.draftText != null && !draft.draftText.isBlank() && !draft.draftText.equals(text)) {
                List<DraftRevision> history = readDraftHistory(draft);
                long next = history.stream().mapToLong(DraftRevision::sequence).max().orElse(0L) + 1;
                history.add(new DraftRevision(next, draft.audience, draft.draftSource, draft.draftModel,
                        draft.draftTokens, draft.draftDurationMs, draft.draftEditedBy, draft.draftText,
                        draft.draftAt != null ? draft.draftAt.toString() : null));
                if (history.size() > MAX_DRAFT_HISTORY) {
                    history = new ArrayList<>(history.subList(history.size() - MAX_DRAFT_HISTORY, history.size()));
                }
                draft.draftHistory = writeDraftHistory(history);
            }
        }
        draft.draftText = text;
        draft.draftSource = source;
        draft.draftEditedBy = editedBy;
        draft.draftModel = model;
        draft.draftTokens = tokens != null ? tokens.longValue() : null;
        draft.draftDurationMs = durationMs != null ? durationMs.longValue() : null;
        draft.draftAt = OffsetDateTime.now();
        draft.persist();

        // Update the parent run's updatedAt
        run.updatedAt = OffsetDateTime.now();
        run.persist();
    }

    /** Every draft revision for a specific audience on a run — stored history first, then the
     * current text as the last entry — ordered ascending by sequence. */
    public List<DraftRevision> listDraftRevisions(String provider, String project, String repo, Long buildId) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null) {
            throw new AiException("No recorded pipeline run for build " + buildId
                    + " on " + project + "/" + repo + " — nothing to show a draft history for.");
        }
        List<DraftRevision> all = new ArrayList<>();
        for (RecordedRunDraft draft : RecordedRunDraft.findAllByRun(run.id)) {
            List<DraftRevision> history = readDraftHistory(draft);
            all.addAll(history);
            if (draft.draftText != null && !draft.draftText.isBlank()) {
                long next = all.stream().mapToLong(DraftRevision::sequence).max().orElse(0L) + 1;
                all.add(new DraftRevision(next, draft.audience, draft.draftSource, draft.draftModel,
                        draft.draftTokens, draft.draftDurationMs, draft.draftEditedBy, draft.draftText,
                        draft.draftAt != null ? draft.draftAt.toString() : null));
            }
        }
        return all;
    }

    /** Restores a run's draft to one of its stored history revisions — the current text is bumped
     * into history and {@code target} becomes the live draft. Returns the restored text. */
    @Transactional
    public String restoreAiDraftRevision(String provider, String project, String repo, Long buildId, Long sequence) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null) {
            throw new AiException("No recorded pipeline run for build " + buildId
                    + " on " + project + "/" + repo + " — nothing to restore a draft revision for.");
        }
        DraftRevision target = listDraftRevisions(provider, project, repo, buildId).stream()
                .filter(r -> r.sequence().equals(sequence))
                .findFirst()
                .orElseThrow(() -> new AiException("Revision #" + sequence + " not found for this run's draft."));
        saveAiDraft(provider, project, repo, buildId, target.audience(), "restore", target.editedBy(),
                target.model(), target.text(), null, null);
        return target.text();
    }

    /** The saved AI draft for a recorded run and specific audience, if any. */
    public Optional<RecordedRunDraft> getAiDraft(String provider, String project, String repo, Long buildId, String audience) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null) return Optional.empty();
        RecordedRunDraft draft = RecordedRunDraft.findByRunAndAudience(run.id, audience);
        return draft != null && draft.draftText != null && !draft.draftText.isBlank()
                ? Optional.of(draft)
                : Optional.empty();
    }

    /** Legacy single-audience getter for backward compat with {@link PipelineRunResource#getAiDraft}. */
    public Optional<RecordedPipelineRun> getAiDraft(String provider, String project, String repo, Long buildId) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null) return Optional.empty();
        // Check new per-audience table first
        List<RecordedRunDraft> drafts = RecordedRunDraft.findAllByRun(run.id);
        if (!drafts.isEmpty()) {
            // Return the run if any audience has a draft (for backward compat)
            boolean anyDraft = drafts.stream().anyMatch(d -> d.draftText != null && !d.draftText.isBlank());
            return anyDraft ? Optional.of(run) : Optional.empty();
        }
        // Fall back to legacy single-slot fields on the run row itself
        return run.aiDraftText != null && !run.aiDraftText.isBlank()
                ? Optional.of(run)
                : Optional.empty();
    }

    /**
     * Fills the human-chosen version into a recorded run row after a successful push, clearing
     * all per-audience drafts — the final step of the version-free Save → push-modal flow.
     */
    @Transactional
    public void applyPushedVersion(String provider, String project, String repo, Long buildId, String version) {
        RecordedPipelineRun run = RecordedPipelineRun.findByBuildId(provider, project, repo, buildId);
        if (run == null) {
            return;
        }
        run.version = version;
        // Clear legacy single-slot fields
        run.aiDraftAudience = null;
        run.aiDraftText = null;
        run.aiDraftModel = null;
        run.aiDraftTokens = null;
        run.aiDraftDurationMs = null;
        run.aiDraftAt = null;
        run.updatedAt = OffsetDateTime.now();
        run.persist();
        // Delete all per-audience draft rows
        RecordedRunDraft.delete("run.id = ?1", run.id);
    }

    /** Result of a GitHub workflow run capture: the fetched data, the raw changelog and the persisted row. */
    public record GitHubCaptureResult(RunFetchResult fetch, String rawChangelog, RecordedPipelineRun run) {}
}