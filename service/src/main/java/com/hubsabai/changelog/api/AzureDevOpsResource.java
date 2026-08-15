package com.hubsabai.changelog.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.ai.AiMessage;
import com.hubsabai.changelog.ai.AiModelCatalog;
import com.hubsabai.changelog.ai.AiModelOption;
import com.hubsabai.changelog.ai.AiProvider;
import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.ai.AiStreamException;
import com.hubsabai.changelog.ai.AiUsage;
import com.hubsabai.changelog.ai.ReleaseNotePreparer;
import com.hubsabai.changelog.chat.ChangelogChatPromptBuilder;
import com.hubsabai.changelog.chat.ChangelogChatRequest;
import com.hubsabai.changelog.chat.ChatTurn;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector;
import com.hubsabai.changelog.connector.azuredevops.ChangelogFileManager;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;
import com.hubsabai.changelog.connector.github.ChangelogMarkdown;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.util.VersionUtils;
import com.hubsabai.changelog.core.model.OrgFetchResult;
import com.hubsabai.changelog.core.model.PipelineRunSummary;
import com.hubsabai.changelog.core.model.ProjectSummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.core.model.RepositorySummary;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector.ChangelogEntry;
import com.hubsabai.changelog.generation.ChangelogGenerationService;
import com.hubsabai.changelog.generation.RunChangeContext;
import com.hubsabai.changelog.generation.RunChangeDataReader;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector.ChangelogEnrichment;
import com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector.ChangelogFile;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import com.hubsabai.changelog.storage.ChangelogRevision;
import com.hubsabai.changelog.storage.ChangelogService;
import com.hubsabai.changelog.storage.ChangelogVersion;
import com.hubsabai.changelog.storage.GeneratedChangelog;
import com.hubsabai.changelog.storage.InputHash;
import com.hubsabai.changelog.storage.RawRelease;
import com.hubsabai.changelog.storage.RawReleaseService;
import com.hubsabai.changelog.storage.RecordedPipelineRun;
import com.hubsabai.changelog.storage.RecordedRunService;
import com.hubsabai.changelog.storage.ReleasePr;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The drill-down navigation surface from README §4.2: all projects fetched up front,
 * then repos for a selected project, then a single repo's normalized changes.
 * {@code /fetch-all} walks the whole org in one call — see README §3 ("fetch everything").
 */
@Path("")
@Produces(MediaType.APPLICATION_JSON)
public class AzureDevOpsResource {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AzureDevOpsResource.class.getName());

    // The dashboard no longer exposes a branch selector for browsing/generating — everything but
    // Push falls back to the repo's own actual default branch when none is given. Only used as a
    // last resort if that lookup itself fails (repo not found) — not every repo in this org uses
    // "dev", and hardcoding it 404s every git-scoped call for a repo whose default is "main"/"test".
    private static final String DEFAULT_BRANCH = "dev";

    @Inject
    AzureDevOpsOrgConnector orgConnector;

    /** Resolves the branch to operate on when the caller didn't pick one explicitly. */
    private String resolveBranch(String project, String repo, String branch) {
        if (branch != null) return branch;
        String repoDefault = orgConnector.defaultBranch(project, repo);
        return repoDefault != null ? repoDefault : DEFAULT_BRANCH;
    }

    @Inject
    RecordedRunService recordedRunService;

    @Inject
    AiProvider aiProvider;

    @Inject
    ChangelogCacheService cacheService;

    @Inject
    ChangelogService changelogService;

    @Inject
    RawReleaseService rawReleaseService;

    @Inject
    ChangelogGenerationService generationService;

    @Inject
    RunChangeDataReader runChangeDataReader;

    @GET
    @Path("/projects")
    public List<ProjectSummary> listProjects() {
        return orgConnector.listProjects();
    }

    @GET
    @Path("/projects/{project}/repos")
    public List<RepositorySummary> listRepositories(@PathParam("project") String project) {
        return orgConnector.listRepositories(project);
    }

    /**
     * Repos filtered to ones with a changelog (cached in DB or CHANGELOG.md on default branch).
     * Checked concurrently: N repos cost one round trip, one error can't blank the whole list.
     */
    @GET
    @Path("/projects/{project}/repos-with-changelog")
    public List<RepositorySummary> listRepositoriesWithChangelog(@PathParam("project") String project) {
        List<RepositorySummary> repos = orgConnector.listRepositories(project);
        // Looked up up front, on the request thread: Panache's entity manager isn't available on
        // the plain virtual threads below, which run outside Quarkus's request-scoped context.
        Set<String> cachedRepos = GeneratedChangelog.reposWithImportCache(project);
        List<RepositorySummary> uncached = repos.stream()
                .filter(r -> !cachedRepos.contains(r.name()))
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = uncached.stream()
                    .map(r -> executor.submit(() -> orgConnector.hasChangelogFileSafely(project, r.name())))
                    .toList();
            Set<String> liveConfirmed = new HashSet<>();
            for (int i = 0; i < uncached.size(); i++) {
                if (futures.get(i).get()) {
                    liveConfirmed.add(uncached.get(i).name());
                }
            }
            return repos.stream()
                    .filter(r -> cachedRepos.contains(r.name()) || liveConfirmed.contains(r.name()))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking changelogs for " + project, e);
        } catch (ExecutionException e) {
            // hasChangelogFileSafely never throws, so this only fires on an unexpected executor failure.
            throw new IllegalStateException("Failed to check changelogs for " + project, e.getCause());
        }
    }

    /**
     * Per-repo status for the Dev dashboard's repo table: latest real version and how many
     * recently merged PRs still have no changelog. Reuses {@link #history} itself (rather than
     * re-deriving the same generated/ungenerated logic) with a small limit.
     *
     * Sequential, not concurrent: {@link #history} calls into Panache ({@code
     * cacheService.saveHistoryEntryIfAbsent}, {@code @Transactional} lookups), which needs
     * Quarkus's request-scoped CDI context — that context isn't propagated onto unmanaged
     * virtual threads (see {@link #listRepositoriesWithChangelog}'s own comment on this exact
     * trap), so running these concurrently on a plain executor silently breaks every DB call
     * instead of throwing loudly, which is worse. One slow/failing repo blocks the rest here,
     * traded deliberately for correctness over the concurrency win.
     */
    @GET
    @Path("/projects/{project}/repos-overview")
    public List<RepoOverview> reposOverview(@PathParam("project") String project) {
        List<RepositorySummary> repos = orgConnector.listRepositories(project);
        return repos.stream().map(r -> buildRepoOverview(project, r)).toList();
    }

    private RepoOverview buildRepoOverview(String project, RepositorySummary repo) {
        try {
            HistoryResponse resp = history(project, repo.name(), null, 0, UNGENERATED_PR_LIMIT);
            String latestVersion = null;
            String latestVersionAt = null;
            int needsReview = 0;
            for (HistoryEntry e : resp.getEntries()) {
                if (!e.isGenerated()) {
                    needsReview++;
                    continue;
                }
                if (latestVersion == null) {
                    latestVersion = e.getVersion();
                    latestVersionAt = e.getTimestamp();
                }
            }
            return new RepoOverview(repo.name(), repo.defaultBranch(), latestVersion, latestVersionAt, needsReview);
        } catch (Exception e) {
            LOG.warning("Failed to build repo overview for " + project + "/" + repo.name() + ": " + e);
            return new RepoOverview(repo.name(), repo.defaultBranch(), null, null, 0);
        }
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/release-version")
    public Response resolveReleaseVersion(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("branch") String branch) {
        String resolvedBranch = resolveBranch(project, repo, branch);
        String headSha = resolveHeadSha(project, repo, resolvedBranch);

        List<String> versions = collectSemanticVersions(project, repo, resolvedBranch);
        String latestVersion = VersionUtils.findLatestVersion(versions);
        String suggestedNext = latestVersion != null
                ? VersionUtils.incrementPatch(latestVersion)
                : "1.0.0";

        boolean changelogExists = latestVersion != null;
        boolean requiresInitial = !changelogExists;

        ReleaseVersionResolution resolution = new ReleaseVersionResolution(
                latestVersion,
                suggestedNext,
                headSha,
                changelogExists,
                requiresInitial
        );
        return Response.ok(resolution).build();
    }

    private String resolveHeadSha(String project, String repo, String branch) {
        try {
            return orgConnector.resolveBranchHead(project, repo, branch);
        } catch (Exception e) {
            LOG.warning("resolveHeadSha failed for " + project + "/" + repo + ": " + e);
            return null;
        }
    }

    private List<String> collectSemanticVersions(String project, String repo, String branch) {
        List<String> versions = new ArrayList<>();

        com.hubsabai.changelog.connector.azuredevops.AzureDevOpsOrgConnector.ChangelogFile file =
                orgConnector.fetchChangelogFileCached(project, repo, branch);
        if (file != null && file.content() != null) {
            List<ChangelogEntry> entries = orgConnector.parseChangelogEntries(file.content());
            for (ChangelogEntry entry : entries) {
                if (entry.version() != null && !entry.version().isBlank()) {
                    versions.add(entry.version());
                }
            }
        }
        return versions;
    }

    @GET
    @Path("/projects/{project}/work-items")
    public List<ChangeItem> listWorkItems(@PathParam("project") String project) {
        return orgConnector.fetchProjectWorkItems(project);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/branches")
    public List<String> listBranches(@PathParam("project") String project, @PathParam("repo") String repo) {
        return orgConnector.listBranches(project, repo);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/changes")
    public ReleaseData fetchRepoChanges(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("fromVersion") String fromVersion,
            @QueryParam("toVersion") String toVersion,
            @QueryParam("branch") String branch) {
        return orgConnector.fetchRepoChanges(project, repo, fromVersion, toVersion, branch);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/commit-count")
    public int commitCount(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("branch") String branch) {
        return orgConnector.commitCountForVersion(project, repo, version, branch);
    }

    @GET
    @Path("/fetch-all")
    public OrgFetchResult fetchAll() {
        return orgConnector.fetchAll();
    }

    @POST
    @Path("/projects/{project}/repos/{repo}/generate")
    public GenerateResponse generate(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("model") String model,
            @QueryParam("fromVersion") String fromVersion,
            @QueryParam("version") String version,
            @QueryParam("branch") String branch,
            @QueryParam("manualText") String manualText,
            @QueryParam("audience") String audience,
            @QueryParam("force") boolean force,
            @QueryParam("buildId") int buildId,
            // Dashboard preview flow: commit=false runs AI but leaves generated_changelog
            // untouched — the user confirms via /generate-commit before anything changes.
            @QueryParam("commit") @DefaultValue("true") boolean commit) {
        boolean hasManualText = manualText != null && !manualText.isBlank();
        if ((version == null || version.isBlank()) && !hasManualText && buildId <= 0) {
            // Always required unless a build or manual text identifies the data — version is the
            // cache key and generated_changelog.version is NOT NULL; a blank value would surface
            // as a raw persistence error instead of this.
            throw new AiException("A version is required to generate a changelog.");
        }
        if (model == null || model.isBlank()) {
            // Dashboard generation is a deliberate model choice — never silently substitute.
            throw new AiException("A model must be selected to generate a changelog.");
        }
        ReleaseData data;
        if (hasManualText) {
            data = buildReleaseDataFromManual(project, repo, branch, version, manualText);
        } else if (buildId > 0) {
            // Use stored recorded run data as primary source; fall back to live fetch
            Optional<RunChangeContext> storedContext = recordedRunService.getRecordedRunContext("azure", project, repo, (long) buildId);
            if (storedContext.isPresent()) {
                data = runChangeDataReader.toReleaseData(project, repo, branch, "datasabai", storedContext.get());
            } else {
                // Fallback: live fetch
                data = runChangeDataReader.toReleaseData(project, repo, branch, "datasabai",
                        orgConnector.fetchRunContext(project, repo, buildId));
            }
        } else {
            data = orgConnector.fetchRepoChanges(project, repo, fromVersion, version, branch);
            if (data.getItems().isEmpty()) {
                data = buildReleaseDataFromRaw(project, repo, branch, version);
            }
        }
        if (data.getItems().isEmpty()) {
            // Third fallback: version may have been pushed to CHANGELOG.md already — reconstruct
            // items from its body text (same as resolveReleaseData does for preview/push).
            data = buildReleaseDataFromChangelog(project, repo, branch, version);
        }
        if (data.getItems().isEmpty()) {
            // Fourth fallback: a Developer draft already exists in Postgres for this version
            // (e.g. from the PR-merge raw-init pipeline path, which never persists structured
            // items) even though it hasn't been pushed to CHANGELOG.md yet.
            data = buildReleaseDataFromSavedDeveloperText(project, repo, branch, version);
        }
        if (data.getItems().isEmpty()) {
            // Nothing reconstructable for this version/branch — refuse rather than let the AI
            // fabricate from an empty item list.
            throw new AiException("No commits or PRs found for "
                    + (version != null ? "v" + version : "this range") + " on branch "
                    + (branch != null ? branch : "(default)") + " — nothing to generate from.");
        }
        long start = System.currentTimeMillis();
        String inputHash = InputHash.of(ReleaseNotePreparer.prepare(data.getItems()));
        // A version-free preview can never commit — generated_changelog is keyed on a non-null
        // version. Version is only ever filled in later, at push time, by the human in the modal.
        boolean effectiveCommit = commit && version != null && !version.isBlank();

        if (audience != null && !audience.isBlank()) {
            // Single-audience request. ensureAudienceText handles the chain (dev→qa→business)
            // transparently. force=true = "Regenerate": skip cache and always call the AI.
            boolean wasCached = !force && version != null && !version.isBlank()
                    && cacheService.getCurrent(project, repo, version, audience, inputHash).isPresent();
            AiResult result = generationService.ensureAudience(project, repo, version, audience, model, true, data, inputHash, force, effectiveCommit, new HashMap<>());
            long durationMs = System.currentTimeMillis() - start;
            return new GenerateResponse(
                    "developer".equals(audience) ? result.getText() : null,
                    "qa".equals(audience) ? result.getText() : null,
                    "business".equals(audience) ? result.getText() : null,
                    result.getUsage() != null ? List.of(result.getUsage()) : List.of(), durationMs, wasCached);
        }

        // Sequential: business depends on qa, qa depends on developer (see ensureAudienceText).
        // Share one `computed` map across all three so qa/business's own internal dependency
        // lookups reuse developer/qa's result in-memory instead of recomputing them.
        Map<String, AiResult> computed = new HashMap<>();
        AiResult developerResult = generationService.ensureAudience(project, repo, version, "developer", model, true, data, inputHash, false, true, computed);
        AiResult qaResult = generationService.ensureAudience(project, repo, version, "qa", model, true, data, inputHash, false, true, computed);
        AiResult businessResult = generationService.ensureAudience(project, repo, version, "business", model, true, data, inputHash, false, true, computed);

        long durationMs = System.currentTimeMillis() - start;
        List<AiUsage> usage = Stream.of(developerResult, qaResult, businessResult)
                .map(AiResult::getUsage)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new GenerateResponse(developerResult.getText(), qaResult.getText(), businessResult.getText(), usage, durationMs, false);
    }

    private static final ObjectMapper SSE_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Cap on concurrent chat streams — each occupies a worker thread for its whole duration.
    private static final Semaphore CHAT_CONCURRENCY = new Semaphore(20);
    // Only the most recent turns are replayed — bounds prompt size/cost on a long-running chat.
    private static final int CHAT_HISTORY_LIMIT = 10;
    // Safety timeout — prevents a hanging AI provider from occupying a worker thread forever.
    private static final long CHAT_TIMEOUT_MS = 60_000;

    @POST
    @Path("/projects/{project}/repos/{repo}/generate-stream")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Response generateStream(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            String rawBody) {
        // A JSON body, not query params: manualText is the raw commit/PR/work-item text for a
        // whole build and can run into the tens of thousands of characters — well past what a
        // URL's length limit allows (HTTP 414), long before it's an issue for a request body.
        // Bound as a raw String (not GenerateStreamRequest directly) because a caller that sends
        // no body at all often sends no/a wrong Content-Type either (browsers omit it, some HTTP
        // clients default to something other than application/json) — RESTEasy Reactive would
        // 415 on that before this method ever runs if the param were POJO-typed. A missing/blank
        // body is treated the same as all-fields-blank so the validation below still reports a
        // clean 400, not a 415 or an NPE.
        GenerateStreamRequest request;
        if (rawBody == null || rawBody.isBlank()) {
            request = new GenerateStreamRequest();
        } else {
            try {
                request = SSE_MAPPER.readValue(rawBody, GenerateStreamRequest.class);
            } catch (Exception e) {
                throw new AiException("Request body must be valid JSON.");
            }
        }
        String model = request.getModel();
        String fromVersion = request.getFromVersion();
        String version = request.getVersion();
        String branch = request.getBranch();
        String manualText = request.getManualText();
        boolean force = request.isForce();
        int buildId = request.getBuildId() != null ? request.getBuildId().intValue() : 0;
        boolean hasManualText = manualText != null && !manualText.isBlank();
        if ((version == null || version.isBlank()) && !hasManualText && buildId <= 0) {
            throw new AiException("A version is required to generate a changelog.");
        }
        if (model == null || model.isBlank()) {
            // Same as /generate: this is a deliberate model choice, never a silent substitution.
            throw new AiException("A model must be selected to generate a changelog.");
        }
        ReleaseData data;
        if (hasManualText) {
            data = buildReleaseDataFromManual(project, repo, branch, version, manualText);
        } else if (buildId > 0) {
            // Use stored recorded run data as primary source; fall back to live fetch
            Optional<RunChangeContext> storedContext = recordedRunService.getRecordedRunContext("azure", project, repo, (long) buildId);
            if (storedContext.isPresent()) {
                data = runChangeDataReader.toReleaseData(project, repo, branch, "datasabai", storedContext.get());
            } else {
                // Fallback: live fetch
                data = runChangeDataReader.toReleaseData(project, repo, branch, "datasabai",
                        orgConnector.fetchRunContext(project, repo, buildId));
            }
        } else {
            data = orgConnector.fetchRepoChanges(project, repo, fromVersion, version, branch);
            if (data.getItems().isEmpty()) {
                data = buildReleaseDataFromRaw(project, repo, branch, version);
            }
        }
        if (data.getItems().isEmpty()) {
            data = buildReleaseDataFromChangelog(project, repo, branch, version);
        }
        if (data.getItems().isEmpty()) {
            data = buildReleaseDataFromSavedDeveloperText(project, repo, branch, version);
        }
        if (data.getItems().isEmpty()) {
            throw new AiException("No commits or PRs found for "
                    + (version != null ? "v" + version : "this range") + " on branch "
                    + (branch != null ? branch : "(default)") + " — nothing to generate from.");
        }

        ReleaseData finalData = data;
        long start = System.currentTimeMillis();
        String inputHash = InputHash.of(ReleaseNotePreparer.prepare(data.getItems()));

        StreamingOutput stream = output -> {
            var writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            int totalTokens = 0;
            boolean hadError = false;
            Map<String, AiResult> computed = new HashMap<>();
            // Developer-only: this page (reached from a pipeline run or "+ Generate new") is for
            // reviewing/pushing the Developer entry quickly. QA/Business generation happens
            // elsewhere — the history panel's own per-audience generate flow — never here, so
            // this never spends an AI call on an audience the user hasn't asked to see yet.
            for (String audience : List.of("developer")) {
                try {
                    // commit=false: this is a preview the user reviews before choosing to push —
                    // see /changelog-push, which is the only place a generation gets persisted now.
                    AiResult result = generationService.ensureAudience(project, repo, version, audience, model, true, finalData, inputHash, force, false, computed);
                    AiUsage usage = result.getUsage();
                    if (usage != null) {
                        totalTokens += usage.getTotalTokens();
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("audience", audience);
                    payload.put("text", result.getText());
                    payload.put("usage", usage);
                    String json = SSE_MAPPER.writeValueAsString(payload);
                    writer.write("event: audience\ndata: " + json + "\n\n");
                    writer.flush();
                } catch (Exception e) {
                    hadError = true;
                    String errJson = SSE_MAPPER.writeValueAsString(Map.of("error", e.getMessage() != null ? e.getMessage() : "Generation failed"));
                    writer.write("event: error\ndata: " + errJson + "\n\n");
                    writer.flush();
                    break;
                }
            }

            if (!hadError) {
                long durationMs = System.currentTimeMillis() - start;
                String doneJson = SSE_MAPPER.writeValueAsString(Map.of(
                        "durationMs", durationMs,
                        "totalTokens", totalTokens));
                writer.write("event: done\ndata: " + doneJson + "\n\n");
                writer.flush();
            }
        };

        return Response.ok(stream)
                .type(MediaType.SERVER_SENT_EVENTS)
                .header("Cache-Control", "no-cache")
                .build();
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/history")
    public HistoryResponse history(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("branch") String branch,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("10") @QueryParam("limit") int limit) {
        String resolvedBranch = resolveBranch(project, repo, branch);

        // DB-native side: every version this repo has ever had reported/generated/edited.
        // This is instant — no network calls.
        List<GeneratedChangelog> developerEntries = cacheService.getDeveloperEntries(project, repo);
        Map<String, GeneratedChangelog> dbByVersion = developerEntries.stream()
                // currentText null means every revision behind this dual-write row got deleted
                // (see ChangelogService#deleteSharedRevision -> syncCurrentAfterDelete) — nothing
                // left to show, so don't surface it as a "generated" entry.
                .filter(e -> e.version != null && !e.version.isBlank() && e.currentText != null)
                .collect(Collectors.toMap(e -> e.version, e -> e, (a, b) -> a));

        // Also read from new tables
        List<ChangelogVersion> newVersions = changelogService.listVersions(project, repo, 0, 1000);
        for (ChangelogVersion cv : newVersions) {
            dbByVersion.putIfAbsent(cv.version, null);
        }

        // If we have DB data for this repo, build response from DB only — skip git entirely
        if (!dbByVersion.isEmpty()) {
            return withDrafts(project, repo, resolvedBranch, page,
                    buildHistoryFromDb(project, repo, resolvedBranch, page, limit, dbByVersion));
        }

        // No DB data — try git as fallback
        ChangelogFile file = orgConnector.fetchChangelogFileCached(project, repo, resolvedBranch);
        List<ChangelogEntry> parsed = file != null
                ? new ArrayList<>(orgConnector.parseChangelogEntries(file.content()))
                : new ArrayList<>();

        // Save each parsed entry to the DB
        for (ChangelogEntry entry : parsed) {
            if (entry.version() != null && !entry.version().isBlank()) {
                cacheService.saveHistoryEntryIfAbsent(project, repo, entry.version(), entry.date(), entry.body());
            }
        }

        parsed.sort((a, b) -> {
            int byTimestamp = compareDatesDescending(a.date(), b.date());
            return byTimestamp != 0 ? byTimestamp : compareVersionsDescending(a.version(), b.version());
        });
        Set<String> seenVersions = new HashSet<>();
        List<ChangelogEntry> deduplicated = new ArrayList<>();
        for (ChangelogEntry entry : parsed) {
            String ver = entry.version();
            if (ver != null && !ver.isBlank() && seenVersions.add(ver)) {
                deduplicated.add(entry);
            }
        }
        parsed = deduplicated;
        Map<String, ChangelogEntry> gitByVersion = new LinkedHashMap<>();
        for (ChangelogEntry ce : parsed) {
            if (ce.version() != null && !ce.version().isBlank()) {
                gitByVersion.put(ce.version(), ce);
            }
        }

        Set<String> allVersions = new HashSet<>(gitByVersion.keySet());
        allVersions.addAll(dbByVersion.keySet());
        List<String> sortedVersions = new ArrayList<>(allVersions);
        sortedVersions.sort((v1, v2) -> {
            String t1 = versionTimestamp(gitByVersion.get(v1), dbByVersion.get(v1));
            String t2 = versionTimestamp(gitByVersion.get(v2), dbByVersion.get(v2));
            int byTimestamp = compareDatesDescending(t1, t2);
            return byTimestamp != 0 ? byTimestamp : compareVersionsDescending(v1, v2);
        });

        int total = sortedVersions.size();
        int from = page * limit;
        if (from >= total) {
            return withDrafts(project, repo, resolvedBranch, page, new HistoryResponse(List.of(), total));
        }
        int to = Math.min(from + limit, total);
        List<String> pageVersions = sortedVersions.subList(from, to);

        List<ChangelogEntry> pageGitEntries = pageVersions.stream()
                .map(gitByVersion::get)
                .filter(Objects::nonNull)
                .toList();
        Map<String, ChangelogEnrichment> enrichment = file != null && !pageGitEntries.isEmpty()
                ? orgConnector.enrichChangelogEntries(project, repo, file.filename(), pageGitEntries)
                : Map.of();

        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < pageVersions.size(); i++) {
            int globalIndex = from + i;
            String version = pageVersions.get(i);
            ChangelogEntry ce = gitByVersion.get(version);
            GeneratedChangelog dbEntry = dbByVersion.get(version);

            if (ce != null) {
                String id = "repo-" + globalIndex + "-" + version + "-" + ce.date();
                ChangelogEnrichment enr = enrichment.get(version);
                String timestamp = parseChangelogDate(ce.date());
                String author = enr != null ? enr.author() : null;
                String developerText = dbEntry != null ? dbEntry.currentText : ce.body();
                HistoryEntry entry = new HistoryEntry(
                        id, project, repo, resolvedBranch, version,
                        author != null ? List.of(author) : List.of(),
                        timestamp, developerText);
                entry.setSource(dbEntry != null ? dbEntry.currentSource : "changelog");
                entries.add(entry);
            } else {
                RawRelease raw = RawRelease.findEntry(project, repo, version);
                String entryBranch = raw != null && raw.branch != null ? raw.branch : resolvedBranch;
                List<String> authors = rawReleaseService.findItems(project, repo, version).stream()
                        .map(ChangeItem::getAuthor)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                String timestamp = dbEntry != null && dbEntry.currentAt != null
                        ? dbEntry.currentAt.toString()
                        : (raw != null && raw.createdAt != null ? raw.createdAt.toString() : null);
                String developerText = dbEntry != null ? dbEntry.currentText : null;
                HistoryEntry entry = new HistoryEntry(
                        "pipeline-" + globalIndex + "-" + version, project, repo, entryBranch, version,
                        authors, timestamp, developerText);
                entry.setSource(dbEntry != null ? dbEntry.currentSource : null);
                entries.add(entry);
            }
        }

        return withDrafts(project, repo, resolvedBranch, page,
                withUngeneratedPrs(project, repo, resolvedBranch, page, entries, total));
    }

    private HistoryResponse buildHistoryFromDb(String project, String repo, String branch,
            int page, int limit, Map<String, GeneratedChangelog> dbByVersion) {
        // Also check new tables
        Map<String, ChangelogVersion> newByVersion = new HashMap<>();
        for (ChangelogVersion cv : changelogService.listVersions(project, repo, 0, 1000)) {
            newByVersion.put(cv.version, cv);
        }

        Set<String> allVersions = new HashSet<>(dbByVersion.keySet());
        allVersions.addAll(newByVersion.keySet());
        List<String> sorted = new ArrayList<>(allVersions);
        sorted.sort((v1, v2) -> {
            GeneratedChangelog d1 = dbByVersion.get(v1);
            GeneratedChangelog d2 = dbByVersion.get(v2);
            String t1 = d1 != null && d1.currentAt != null ? d1.currentAt.toString() : v1;
            String t2 = d2 != null && d2.currentAt != null ? d2.currentAt.toString() : v2;
            return t2.compareTo(t1);
        });

        int total = sorted.size();
        int from = page * limit;
        if (from >= total) {
            return withDrafts(project, repo, branch, page, new HistoryResponse(List.of(), total));
        }
        int to = Math.min(from + limit, total);
        List<String> pageVersions = sorted.subList(from, to);

        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < pageVersions.size(); i++) {
            int idx = from + i;
            String version = pageVersions.get(i);
            GeneratedChangelog db = dbByVersion.get(version);
            ChangelogVersion cv = newByVersion.get(version);
            List<String> authors = List.of();
            if (cv != null) {
                authors = changelogService.findAuthors(project, repo, version);
            } else if (db != null) {
                authors = rawReleaseService.findItems(project, repo, version).stream()
                        .map(ChangeItem::getAuthor).filter(Objects::nonNull).distinct().toList();
            }
            String timestamp = db != null && db.currentAt != null ? db.currentAt.toString()
                    : (cv != null && cv.createdAt != null ? cv.createdAt.toString() : null);
            String text = db != null ? db.currentText : (cv != null ? changelogService.getLatestText(cv.id, "developer") : null);
            HistoryEntry entry = new HistoryEntry(
                    "db-" + idx + "-" + version, project, repo, branch, version,
                    authors, timestamp, text);
            if (db != null) entry.setSource(db.currentSource);
            entries.add(entry);
        }
        return new HistoryResponse(entries, total);
    }

    /** The timestamp a version sorts by: the git-parsed date if this version has a CHANGELOG.md
     * entry, otherwise the DB row's current_at (when it was ingested/generated/edited) — whichever
     * side actually has data for it. */
    private static String versionTimestamp(ChangelogEntry git, GeneratedChangelog db) {
        if (git != null) {
            return parseChangelogDate(git.date());
        }
        if (db != null && db.currentAt != null) {
            return db.currentAt.toString();
        }
        return null;
    }

    // Only the most recently merged PRs are worth checking against release_pr, so this only runs
    // for page 0 — older un-generated PRs would just push the real history further down the list.
    private static final int UNGENERATED_PR_LIMIT = 10;

    /** Prepends saved (version-free) AI drafts — the Save action's artifact — to the first history
     * page so a saved draft shows up on the dashboard even before it has a version. Each becomes a
     * generated entry keyed on its pipeline run id ("run-<buildId>"), sourced "raw" (pending
     * review), with the draft text as the developer body. Clicking it opens the generate page for
     * that run so it can be reviewed and pushed. */
    private HistoryResponse withDrafts(String project, String repo, String branch, int page,
            HistoryResponse response) {
        if (page != 0) {
            return response;
        }
        List<RecordedPipelineRun> drafts = recordedRunService.listDraftRuns("azure", project, repo);
        if (drafts.isEmpty()) {
            return response;
        }
        List<HistoryEntry> draftEntries = new ArrayList<>();
        for (RecordedPipelineRun run : drafts) {
            String title = run.displayTitle();
            HistoryEntry entry = new HistoryEntry(
                    "run-" + run.buildId, project, repo, branch, null,
                    List.of(), run.aiDraftAt != null ? run.aiDraftAt.toString() : null,
                    title != null ? title : run.aiDraftText);
            entry.setSource("raw");
            draftEntries.add(entry);
        }
        List<HistoryEntry> merged = new ArrayList<>(draftEntries);
        merged.addAll(response.getEntries());
        return new HistoryResponse(merged, response.getTotal() + draftEntries.size());
    }

    private HistoryResponse withUngeneratedPrs(
            String project, String repo, String branch, int page, List<HistoryEntry> entries, int total) {
        if (page != 0) {
            return new HistoryResponse(entries, total);
        }
        List<HistoryEntry> ungenerated = buildUngeneratedEntries(project, repo, branch);
        if (ungenerated.isEmpty()) {
            return new HistoryResponse(entries, total);
        }
        List<HistoryEntry> merged = new ArrayList<>(ungenerated);
        merged.addAll(entries);
        return new HistoryResponse(merged, total + ungenerated.size());
    }

    /** PRs merged since the last real release, on this branch, with no generated changelog yet —
     * see {@link #changelogLocation}, the same release_pr-backed lookup, reused here to filter.
     * Deliberately does NOT ask "what's the latest version THIS APP has generated a changelog
     * for" to find the boundary (an earlier version of this method did, via bumpPatchVersion) —
     * this app's own AI-generation flow can produce a changelog for a version nothing in git has
     * actually reached yet (no tag, no marker commit), and asking for "since that version" then
     * silently falls through to treating the WHOLE repo history as pending instead of failing
     * loudly. {@link AzureDevOpsOrgConnector#fetchChangesSinceLastRelease} finds the boundary from
     * git's own release markers directly, independent of this app's DB state. */
    private List<HistoryEntry> buildUngeneratedEntries(String project, String repo, String branch) {
        ReleaseData releaseData;
        try {
            releaseData = orgConnector.fetchChangesSinceLastRelease(project, repo, branch);
        } catch (Exception e) {
            return List.of();
        }
        List<ChangeItem> sinceLastRelease = releaseData.getItems();
        List<ChangeItem> prs = sinceLastRelease.stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .sorted((a, b) -> compareDatesDescending(a.getDate(), b.getDate()))
                .toList();

        List<HistoryEntry> result = new ArrayList<>();
        for (ChangeItem pr : prs) {
            int prId;
            try {
                prId = Integer.parseInt(pr.getId());
            } catch (NumberFormatException e) {
                continue;
            }
            if (rawReleaseService.findLocation(project, repo, prId).isPresent()) {
                continue;
            }
            HistoryEntry entry = new HistoryEntry(
                    "pr-" + prId, project, repo, branch, null,
                    pr.getAuthor() != null ? List.of(pr.getAuthor()) : List.of(),
                    pr.getDate() != null ? pr.getDate() : "",
                    pr.getTitle());
            entry.setGenerated(false);
            result.add(entry);
            if (result.size() >= UNGENERATED_PR_LIMIT) {
                break;
            }
        }
        return result;
    }

    /**
     * Resolves which release (if any) a PR actually shipped in — a direct, indexed lookup against
     * {@code release_pr}, never a search over generated changelog text (see RawReleaseService's
     * Javadoc for why that distinction matters). {@code status} is one of:
     * <ul>
     *   <li>{@code released} — the PR shipped in {@code version}.</li>
     *   <li>{@code prerelease} — real, reported, but not yet promoted to a release.</li>
     *   <li>{@code not_found} — no pipeline has ever reported this PR for this project/repo.</li>
     * </ul>
     */
    @GET
    @Path("/projects/{project}/repos/{repo}/pull-requests/{prId}/changelog-location")
    public ChangelogLocationResponse changelogLocation(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("prId") int prId) {
        Optional<ReleasePr> entry = rawReleaseService.findLocation(project, repo, prId);
        if (entry.isEmpty()) {
            return new ChangelogLocationResponse("not_found", null, null);
        }
        ReleasePr pr = entry.get();
        String status = "release".equals(pr.stage) ? "released" : "prerelease";
        return new ChangelogLocationResponse(status, pr.version, pr.stage);
    }

    /**
     * One PR's own title/description/commits/linked work items, live from Azure DevOps — for the
     * dashboard's "generate" flow when the user picked a merged PR that has no changelog yet
     * (see {@link #buildUngeneratedEntries}) and there's no version to key a range-based fetch on.
     * Same data {@code PipelineResource}'s raw-init flow uses, just exposed for the dashboard.
     */
    @GET
    @Path("/projects/{project}/repos/{repo}/pull-requests/{prId}/details")
    public AzureDevOpsOrgConnector.PullRequestDetails pullRequestDetails(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("prId") int prId) {
        return orgConnector.fetchPullRequestDetails(project, repo, prId);
    }

    /** Recent pipeline runs for this repo (any pipeline definition that builds it), for the
     * dashboard's "Pipeline runs" list — pick one to generate a changelog from its buildId. */
    @GET
    @Path("/projects/{project}/repos/{repo}/builds")
    public List<PipelineRunSummary> repoBuilds(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @DefaultValue("20") @QueryParam("top") int top) {
        return orgConnector.listRecentBuilds(project, repo, top);
    }

    /** Same commits/PRs/work-items fetch the pipeline-run intake path uses (see
     * {@link AzureDevOpsOrgConnector#fetchRunChanges}), exposed read-only for a human picking a
     * run from the dashboard's "Pipeline runs" list instead of the pipeline calling it itself.
     * Uses stored run data as primary source; falls back to live Azure DevOps fetch if not recorded. */
    @GET
    @Path("/projects/{project}/repos/{repo}/builds/{buildId}/changes")
    public ReleaseData buildChanges(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("buildId") int buildId) {
        Optional<ReleaseData> stored = recordedRunService.getRecordedRunData("azure", project, repo, (long) buildId);
        if (stored.isPresent()) {
            return stored.get();
        }
        // Fallback: live fetch
        return orgConnector.fetchRunChanges(project, repo, buildId);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/builds/{buildId}/run-context")
    public RunChangeContext runContext(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("buildId") int buildId) {
        Optional<RunChangeContext> stored = recordedRunService.getRecordedRunContext("azure", project, repo, (long) buildId);
        if (stored.isPresent()) {
            return stored.get();
        }
        // Fallback: live fetch
        return orgConnector.fetchRunContext(project, repo, buildId);
    }

    /**
     * Newest-first version compare, numeric segments compared as integers rather than text
     * (so {@code 1.4.9} sorts before {@code 1.4.10}). Tie-breaker for entries whose pipeline
     * batched several version bumps into a single commit and so share one timestamp.
     */
    private static int compareVersionsDescending(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return 1;
        if (v2 == null) return -1;

        String[] a = v1.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        String[] b = v2.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            String ta = i < a.length ? a[i] : "";
            String tb = i < b.length ? b[i] : "";
            int cmp = ta.matches("\\d+") && tb.matches("\\d+")
                    ? Integer.compare(Integer.parseInt(ta), Integer.parseInt(tb))
                    : ta.compareTo(tb);
            if (cmp != 0) return -cmp;
        }
        return 0;
    }

    /** Newest-first ISO-8601 string compare — safe because both sides here always come from the
     * same source (Azure DevOps' own timestamp format via {@code ChangeItem.getDate()}), unlike
     * comparing across DB-stored and API-fetched dates where differing ISO-8601 renderings
     * ("Z" vs a numeric offset) can silently break plain string ordering. */
    private static int compareDatesDescending(String d1, String d2) {
        if (d1 == null && d2 == null) return 0;
        if (d1 == null) return 1;
        if (d2 == null) return -1;
        return d2.compareTo(d1);
    }

    /** Converts a CHANGELOG.md date like "2026-06-24" to an ISO-8601 timestamp for sort order. */
    private static String parseChangelogDate(String date) {
        if (date == null) return "0000-01-01T00:00:00Z";
        String trimmed = date.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return trimmed + "T00:00:00Z";
        }
        return trimmed;
    }

    /**
     * Parses user input in the universal {@code emit raw} format:
     * <pre>
     * === Merge pull request #123: Add auth middleware
     * commit body lines (optional)
     * src/main/java/auth/OAuthFilter.java
     * src/main/java/auth/Config.java
     * === Fix NPE
     * src/main/java/config/Parser.java
     * </pre>
     */
    /** Fallback when a live Azure fetch finds nothing for this version/branch: reuse whatever a
     * pipeline already reported via {@code /api/pipeline/generate} for this exact project/repo/
     * version (see {@link RawReleaseService#findItems}), so an already-successful ingest is never
     * stranded by a live lookup that comes up empty (e.g. the same Build-Changes-API gap that
     * affects the pipeline's own ingest call). Empty items in, empty items out — the caller still
     * refuses to generate rather than fabricate from nothing. */
    private ReleaseData buildReleaseDataFromRaw(String project, String repo, String branch, String version) {
        List<ChangeItem> items = rawReleaseService.findItems(project, repo, version);

        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("datasabai");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());

        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    /** Third fallback: version was already pushed to CHANGELOG.md — reconstruct items from its
     * entry body text. Returns empty data (not null) if the changelog can't be fetched or no
     * matching version entry is found, so callers can chain this with other fallbacks. */
    private ReleaseData buildReleaseDataFromChangelog(String project, String repo, String branch, String version) {
        String resolvedBranch = resolveBranch(project, repo, branch);
        ChangelogFile file = orgConnector.fetchChangelogFile(project, repo, resolvedBranch);
        if (file == null) {
            return emptyReleaseData(project, repo, branch);
        }
        List<ChangelogEntry> parsed = orgConnector.parseChangelogEntries(file.content());
        if (parsed.isEmpty()) {
            return emptyReleaseData(project, repo, branch);
        }
        String body = parsed.stream()
                .filter(e -> version.equals(e.version()))
                .map(ChangelogEntry::body)
                .filter(b -> b != null && !b.isBlank())
                .findFirst()
                .orElse(null);
        if (body == null) {
            return emptyReleaseData(project, repo, branch);
        }
        return buildReleaseDataFromChangelogBody(project, repo, resolvedBranch, body);
    }

    /** Fourth fallback: nothing reconstructable from git or raw pipeline ingestion, but a
     * Developer draft already exists in Postgres for this version — same bullet-per-line
     * reconstruction as {@link #buildReleaseDataFromChangelog}, just reading whatever's currently
     * saved instead of requiring it to already be pushed into CHANGELOG.md. This is what covers a
     * version created via the PR-merge raw-init pipeline path ({@code POST /pipeline/generate}
     * with {@code raw=true}): that path fetches the PR's details live to build the Developer
     * bullets and never persists structured items anywhere, so once that request is done, the
     * saved Developer text is the only record left of what the version's changes were. */
    private ReleaseData buildReleaseDataFromSavedDeveloperText(String project, String repo, String branch, String version) {
        String resolvedBranch = resolveBranch(project, repo, branch);
        String body = cacheService.getCurrentText(project, repo, version, "developer").orElse(null);
        if (body == null || body.isBlank()) {
            return emptyReleaseData(project, repo, branch);
        }
        return buildReleaseDataFromChangelogBody(project, repo, resolvedBranch, body);
    }

    private static ReleaseData emptyReleaseData(String project, String repo, String branch) {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("datasabai");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());
        data.setRelease(meta);
        data.setItems(List.of());
        return data;
    }

    private static ReleaseData buildReleaseDataFromManual(String project, String repo, String branch, String version, String text) {
        List<ChangeItem> items = new ArrayList<>();
        String currentTitle = null;
        ChangeItem.ItemType currentType = ChangeItem.ItemType.COMMIT;
        String currentId = null;
        String currentAuthor = null;
        String currentCategory = null;
        StringBuilder currentBody = new StringBuilder();
        List<String> currentFiles = new ArrayList<>();

        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("=== ")) {
                // Save previous entry
                if (currentTitle != null) {
                    items.add(buildChangeItem(project, repo, currentTitle, currentBody.toString(), currentFiles,
                            currentType, currentId, currentAuthor, currentCategory));
                }
                String rest = trimmed.substring(4).strip();
                // Parse structured metadata prefix: [TYPE|ID|AUTHOR|CATEGORY] title
                if (rest.startsWith("[") && rest.contains("] ")) {
                    int closeBracket = rest.indexOf("] ");
                    String meta = rest.substring(1, closeBracket);
                    if (meta.contains("|")) {
                        String[] parts = meta.split("\\|", -1);
                        currentType = parseItemType(parts[0]);
                        currentId = parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : null;
                        currentAuthor = parts.length >= 3 && !parts[2].isEmpty() ? parts[2] : null;
                        currentCategory = parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : null;
                        currentTitle = rest.substring(closeBracket + 2).strip();
                    } else {
                        currentTitle = rest;
                        currentType = ChangeItem.ItemType.COMMIT;
                        currentId = null;
                        currentAuthor = null;
                        currentCategory = null;
                    }
                } else {
                    currentTitle = rest;
                    currentType = ChangeItem.ItemType.COMMIT;
                    currentId = null;
                    currentAuthor = null;
                    currentCategory = null;
                }
                currentBody = new StringBuilder();
                currentFiles = new ArrayList<>();
            } else if (currentTitle != null) {
                // File path (contains / or .) or body text
                if (trimmed.contains("/") || trimmed.contains(".")) {
                    currentFiles.add(trimmed);
                } else {
                    if (!currentBody.isEmpty()) currentBody.append("\n");
                    currentBody.append(trimmed);
                }
            }
        }
        // Last entry
        if (currentTitle != null) {
            items.add(buildChangeItem(project, repo, currentTitle, currentBody.toString(), currentFiles,
                    currentType, currentId, currentAuthor, currentCategory));
        }

        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("datasabai");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());

        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    private static ChangeItem.ItemType parseItemType(String s) {
        try {
            return ChangeItem.ItemType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ChangeItem.ItemType.COMMIT;
        }
    }

    private static ChangeItem buildChangeItem(String project, String repo, String title, String body, List<String> filePaths) {
        return buildChangeItem(project, repo, title, body, filePaths,
                ChangeItem.ItemType.COMMIT, null, null, null);
    }

    private static ChangeItem buildChangeItem(String project, String repo, String title, String body, List<String> filePaths,
            ChangeItem.ItemType type, String id, String author, String category) {
        ChangeItem item = new ChangeItem();
        item.setType(type);
        item.setId(id);
        item.setTitle(title);
        item.setCategory(category);
        item.setDescription(body);
        item.setAuthor(author);
        item.setFilePaths(filePaths);
        item.setProject(project);
        item.setRepo(repo);
        return item;
    }

    /**
     * One {@link ChangeItem} per bullet line of an already-written CHANGELOG.md entry — used when
     * {@code fetchRepoChanges} can't reconstruct raw commits/PRs for a version (see the caller).
     * The bullet text is the only signal available at that point, so each line becomes its own
     * item rather than trying to re-split it into a title/description pair.
     */
    private static ReleaseData buildReleaseDataFromChangelogBody(String project, String repo, String branch, String body) {
        List<ChangeItem> items = new ArrayList<>();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String bullet = line.replaceFirst("^[-*]\\s+", "");
            items.add(buildChangeItem(project, repo, bullet, "", List.of()));
        }

        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("datasabai");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());

        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    /** Live from the provider account, not a hardcoded list — falls back to the curated list only if that call fails. */
    @GET
    @Path("/ai/models")
    public List<AiModelOption> listAiModels() {
        try {
            List<AiModelOption> live = aiProvider.listModels();
            return live.isEmpty() ? AiModelCatalog.FREE_MODELS : live;
        } catch (Exception e) {
            return AiModelCatalog.FREE_MODELS;
        }
    }

    /**
     * Whatever's already sitting in Postgres for this version+audience — an edit or a prior
     * generation — with no AI call and no Azure DevOps round-trip. Lets the dashboard show
     * already-generated qa/business content the moment a tab opens, instead of only after the
     * user clicks Generate in the current session (which would otherwise re-hide anything
     * generated earlier, e.g. by a previous session or the pipeline).
     */
    @GET
    @Path("/projects/{project}/repos/{repo}/changelog-text")
    public Map<String, String> changelogText(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("audience") String audience) {
        if (!"developer".equals(audience) && !"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'developer', 'qa', or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("text", cacheService.getCurrentText(project, repo, version, audience).orElse(null));
        return response;
    }

    /**
     * What's known about the current text for a version+audience — an AI generation (with its
     * model) or a human edit (with who and when) — so the dashboard can render an accurate
     * footer instead of assuming everything came from the last AI call. All-null when nothing's
     * been saved for this version+audience yet, which is a normal, common case, not an error.
     */
    @GET
    @Path("/projects/{project}/repos/{repo}/changelog-meta")
    public ChangelogMeta changelogMeta(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("audience") String audience,
            @QueryParam("branch") String branch) {
        if (!"developer".equals(audience) && !"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'developer', 'qa', or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }

        List<ChangelogRevisionDto> revisions = List.of();
        ChangelogVersion cv = ChangelogVersion.findEntry(project, repo, version);
        if (cv != null) {
            revisions = ChangelogRevision.findByVersionAndAudience(cv.id, audience).stream()
                    .map(r -> new ChangelogRevisionDto(r.sequence, r.source, r.model, r.tokens, r.durationMs,
                            r.editedBy, r.text, r.createdAt != null ? r.createdAt.toString() : null))
                    .toList();
        }
        List<ChangelogRevisionDto> finalRevisions = revisions;
        // Sorted ascending by sequence, so the last one is the current text's own revision —
        // that's where its real tokens/duration live (the top-level current/previous fields above
        // come from the older dual-write and never carried usage data at all).
        ChangelogRevisionDto latestRevision = finalRevisions.isEmpty() ? null : finalRevisions.get(finalRevisions.size() - 1);
        Integer latestTokens = latestRevision != null ? latestRevision.getTokens() : null;
        Integer latestDurationMs = latestRevision != null ? latestRevision.getDurationMs() : null;

        return cacheService.getCurrentEntry(project, repo, version, audience)
                .map(e -> new ChangelogMeta(e.currentSource, e.currentModelId, e.currentEditedBy,
                        e.currentAt != null ? e.currentAt.toString() : null, e.previousText != null, e.previousText,
                        e.previousSource, e.previousModelId, e.previousEditedBy,
                        e.previousAt != null ? e.previousAt.toString() : null,
                        "developer".equals(audience) && hasUnpushedChanges(project, repo, version, branch, e),
                        e.pushedAt != null ? e.pushedAt.toString() : null, e.pushedPullRequestUrl, e.pushedText,
                        finalRevisions, latestTokens, latestDurationMs))
                .orElse(new ChangelogMeta(null, null, null, null, false, null, null, null, null, null, false, null, null, null,
                        finalRevisions, latestTokens, latestDurationMs));
    }

    /**
     * Whether Developer's current text still needs pushing. Comparing only against our own
     * {@code pushed_text} bookkeeping isn't enough: a version whose developer text came from the
     * pipeline's own direct commit (see {@link PipelineResource}, which writes CHANGELOG.md
     * itself and never goes through this app's push button) already has this exact text live in
     * the repo, yet {@code pushed_text} was never set — so the naive check would show "Push to
     * repo" for something that's already there. Only when neither our own bookkeeping nor the
     * repo's actual current content already matches is there really something to push.
     */
    private boolean hasUnpushedChanges(String project, String repo, String version, String branch, GeneratedChangelog e) {
        // An import entry was cached once, globally, without recording which branch it came
        // from — comparing it against whatever branch happens to be selected right now is
        // unreliable (a branch switch can make an untouched import entry look "unpushed" against
        // a branch it was never sourced from), and nothing about it was ever generated or edited
        // via this app in the first place, so there is never really something of ours to push.
        if ("import".equals(e.currentSource)) {
            return false;
        }
        if (e.pushedText != null && e.pushedText.equals(e.currentText)) {
            return false;
        }
        String liveBody = fetchLiveDeveloperText(project, repo, version, branch);
        return liveBody == null || !liveBody.equals(e.currentText.strip());
    }

    /**
     * The developer entry's body exactly as it exists in the repo's CHANGELOG.md right now (on
     * {@code branch}), or null if that version has no entry there yet. Shared by {@link
     * #hasUnpushedChanges} and {@link #changelogRepoText}, which exposes this same lookup to the
     * dashboard so the push confirmation can diff against the real file instead of asking "are
     * you sure" with nothing to compare.
     */
    private String fetchLiveDeveloperText(String project, String repo, String version, String branch) {
        String resolvedBranch = resolveBranch(project, repo, branch);
        ChangelogFile file = orgConnector.fetchChangelogFile(project, repo, resolvedBranch);
        if (file == null) {
            return null;
        }
        return orgConnector.parseChangelogEntries(file.content()).stream()
                .filter(entry -> version.equals(entry.version()))
                .map(ChangelogEntry::body)
                .map(String::strip)
                .findFirst()
                .orElse(null);
    }

    /**
     * The developer entry's body exactly as it exists in the repo's CHANGELOG.md right now — read
     * for the push confirmation dialog to diff against, never written. Null if that version has
     * no entry there yet (e.g. this would be the first push for it).
     */
    @GET
    @Path("/projects/{project}/repos/{repo}/changelog-repo-text")
    public Map<String, String> changelogRepoText(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("branch") String branch) {
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("text", fetchLiveDeveloperText(project, repo, version, branch));
        return response;
    }

    /**
     * Restores whatever was current before the last edit or regeneration for one version+audience
     * — see {@link com.hubsabai.changelog.storage.ChangelogCacheService#restorePrevious}. Works
     * for all three audiences (unlike {@code changelog-push}, which is developer-only): restore is
     * a Postgres-only swap, it never touches the repo, so there's no "no file to write into" limit
     * here the way there is for push.
     */
    @PUT
    @Path("/projects/{project}/repos/{repo}/changelog-restore")
    public Map<String, String> restoreChangelog(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("audience") String audience) {
        if (!"developer".equals(audience) && !"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'developer', 'qa', or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        String restored = cacheService.restorePrevious(project, repo, version, audience)
                .orElseThrow(() -> new AiException("Nothing to restore for v" + version + " (" + audience + ")."));

        ChangelogVersion cv = ChangelogVersion.findEntry(project, repo, version);
        if (cv != null) {
            changelogService.createSnapshot(cv.id, audience, restored, "restore", null, null, null, null);
        }

        Map<String, String> response = new LinkedHashMap<>();
        response.put("text", restored);
        return response;
    }

    /**
     * Rolls back to whatever was last successfully pushed to the repo — a separate rollback target
     * from {@code changelog-restore}, which only ever undoes the last edit/regeneration. Developer-
     * only, same restriction as {@code changelog-push}: qa/business never push, so they never have
     * a pushed version to roll back to.
     */
    @PUT
    @Path("/projects/{project}/repos/{repo}/changelog-restore-pushed")
    public Map<String, String> restoreChangelogToPushed(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("audience") String audience) {
        if (!"developer".equals(audience)) {
            throw new AiException("Restoring the last pushed version is only supported for the developer view.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        String restored = cacheService.restoreToPushed(project, repo, version, audience)
                .orElseThrow(() -> new AiException("Nothing has been pushed yet for v" + version + "."));

        ChangelogVersion cv = ChangelogVersion.findEntry(project, repo, version);
        if (cv != null) {
            changelogService.createSnapshot(cv.id, audience, restored, "restore", null, null, null, null);
        }

        Map<String, String> response = new LinkedHashMap<>();
        response.put("text", restored);
        return response;
    }

    /**
     * Rolls back to an arbitrary past revision, not just the immediately-previous one — the
     * general form of {@code changelog-restore}. Lets the user pick any row out of the full edit
     * history (developer, qa, or business each keep their own independent sequence) and make it
     * current again, instead of the two fixed one-step-back targets {@code changelog-restore} /
     * {@code changelog-restore-pushed} support. Preserves the target revision's own
     * source/model/editedBy attribution (see {@link ChangelogCacheService#restoreToRevision}), and
     * appends a new "restore" entry to the log rather than mutating what revision {@code sequence}
     * once was — the log stays an honest record of "we rolled back to #N", not a rewrite of history.
     */
    @PUT
    @Path("/projects/{project}/repos/{repo}/changelog-revision-restore")
    public Map<String, String> restoreChangelogRevision(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("audience") String audience,
            @QueryParam("sequence") int sequence) {
        if (!"developer".equals(audience) && !"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'developer', 'qa', or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        ChangelogVersion cv = ChangelogVersion.findEntry(project, repo, version);
        ChangelogRevision target = cv != null ? ChangelogRevision.findBySequence(cv.id, audience, sequence) : null;
        if (target == null) {
            throw new AiException("Revision #" + sequence + " not found for v" + version + " (" + audience + ").");
        }

        cacheService.restoreToRevision(project, repo, version, audience, target.source, target.model, target.editedBy, target.text);
        changelogService.createSnapshot(cv.id, audience, target.text, "restore", target.model, null, null, target.editedBy);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("text", target.text);
        return response;
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/has-changelog")
    public boolean hasChangelog(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("branch") String branch) {
        return orgConnector.hasChangelogFile(project, repo, branch);
    }

    private record ResolvedRelease(String version, String branch, ReleaseData data) {}

    /**
     * Resolves a version (defaulting to latest) and its {@link ReleaseData} for a repo, with the
     * same changelog-body fallback used when raw commit/PR reconstruction fails for old versions
     * (see {@link #buildReleaseDataFromChangelogBody}). Shared by the read-only preview and the
     * edit endpoint, both of which need a version's items to feed the AI.
     */
    private ResolvedRelease resolveReleaseData(String project, String repo, String version, String branch) {
        String resolvedBranch = resolveBranch(project, repo, branch);

        ChangelogFile file = orgConnector.fetchChangelogFile(project, repo, resolvedBranch);
        if (file == null) {
            throw new AiException("No CHANGELOG.md found for " + project + "/" + repo + " yet.");
        }
        List<ChangelogEntry> parsed = orgConnector.parseChangelogEntries(file.content());
        if (parsed.isEmpty()) {
            throw new AiException("CHANGELOG.md for " + project + "/" + repo + " has no entries yet.");
        }
        List<ChangelogEntry> sorted = new ArrayList<>(parsed);
        sorted.sort((a, b) -> {
            int byTimestamp = parseChangelogDate(b.date()).compareTo(parseChangelogDate(a.date()));
            return byTimestamp != 0 ? byTimestamp : compareVersionsDescending(a.version(), b.version());
        });

        String resolvedVersion = (version != null && !version.isBlank()) ? version : sorted.get(0).version();

        ReleaseData data = orgConnector.fetchRepoChanges(project, repo, null, resolvedVersion, resolvedBranch);
        if (data.getItems().isEmpty()) {
            // Same fallback /generate itself uses: a pipeline may have already ingested this
            // exact version's items (see buildReleaseDataFromRaw) even though there's no real git
            // history to reconstruct from — a synthetic/prerelease build number, for instance.
            data = buildReleaseDataFromRaw(project, repo, resolvedBranch, resolvedVersion);
        }
        if (data.getItems().isEmpty()) {
            // Reconstruction can fail for older untagged versions (scan capped at MAX_PAGES).
            // Fall back to the CHANGELOG.md entry's own body text — the same text the Developer
            // tab already shows for this version.
            String body = sorted.stream()
                    .filter(e -> resolvedVersion.equals(e.version()))
                    .map(ChangelogEntry::body)
                    .filter(b -> b != null && !b.isBlank())
                    .findFirst()
                    .orElse(null);
            if (body == null) {
                throw new AiException("No commits, PRs, or changelog text found for v" + resolvedVersion
                        + " on " + project + "/" + repo + " — nothing to preview.");
            }
            data = buildReleaseDataFromChangelogBody(project, repo, resolvedBranch, body);
        }
        return new ResolvedRelease(resolvedVersion, resolvedBranch, data);
    }

    /**
     * Read-only preview for qa/business viewers: returns whatever text has already been generated
     * for this version+audience, or null if nothing has been generated yet. Never triggers an AI
     * call — generation is a deliberate developer action in the Dev dashboard, not something a
     * read-only viewer should silently trigger.
     */
    @GET
    @Path("/projects/{project}/repos/{repo}/changelog-preview")
    public ChangelogPreview changelogPreview(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("audience") String audience,
            @QueryParam("version") String version,
            @QueryParam("branch") String branch) {
        if (!"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'qa' or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        String text = cacheService.getCurrentText(project, repo, version, audience).orElse(null);
        return new ChangelogPreview(project, repo, version, audience, text);
    }

    /**
     * Streams a plain-language answer to a QA/Business question about one version's already-
     * generated changelog. Grounded in that audience's summary text plus the full list of
     * changes reported for the version (see {@link ChangelogChatPromptBuilder}); never triggers
     * a changelog generation itself, same read-only spirit as {@link #changelogPreview}. Real
     * per-token streaming, same {@code StreamingOutput} shape as {@link #generateStream}.
     *
     * {@code CHAT_CONCURRENCY} bounds how many of these can run at once — each occupies a worker
     * thread for its whole duration, and that pool is shared with every other endpoint in the
     * app. See CHATBOT-PLAN.md "Performance" for why the cap ships now and a non-blocking rewrite
     * is deferred until a load test shows it's actually needed.
     */
    @POST
    @Path("/projects/{project}/repos/{repo}/changelog-chat/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Response changelogChatStream(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("audience") String audience,
            @QueryParam("version") String version,
            ChangelogChatRequest request) {
        if (!"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'qa' or 'business'.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required.");
        }
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new AiException("A question is required.");
        }
        String summaryText = cacheService.getCurrentText(project, repo, version, audience).orElse(null);
        if (summaryText == null) {
            throw new AiException("No " + audience + " changelog has been generated for this version yet.");
        }

        if (!CHAT_CONCURRENCY.tryAcquire()) {
            return Response.status(429)
                    .entity(Map.of("error", "Chat is busy right now — try again shortly."))
                    .build();
        }

        List<ChangeItem> items = rawReleaseService.findItems(project, repo, version);
        String systemPrompt = ChangelogChatPromptBuilder.build(repo, version, audience, summaryText, items);
        List<AiMessage> messages = buildChatMessages(systemPrompt, request);
        long deadline = System.currentTimeMillis() + CHAT_TIMEOUT_MS;

        StreamingOutput stream = output -> {
            var writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            try {
                AiResult result = aiProvider.chatStream(messages, null, delta -> onChatDelta(writer, deadline, delta));
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("model", result.getModel());
                writer.write("event: done\ndata: " + SSE_MAPPER.writeValueAsString(done) + "\n\n");
                writer.flush();
            } catch (Exception e) {
                writeChatError(writer, e);
            } finally {
                CHAT_CONCURRENCY.release();
            }
        };
        return Response.ok(stream).type(MediaType.SERVER_SENT_EVENTS).header("Cache-Control", "no-cache").build();
    }

    private List<AiMessage> buildChatMessages(String systemPrompt, ChangelogChatRequest request) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage("system", systemPrompt));
        List<ChatTurn> history = request.getHistory();
        if (history != null && !history.isEmpty()) {
            int from = Math.max(0, history.size() - CHAT_HISTORY_LIMIT);
            for (ChatTurn turn : history.subList(from, history.size())) {
                messages.add(new AiMessage(turn.getRole(), turn.getContent()));
            }
        }
        messages.add(new AiMessage("user", request.getQuestion()));
        return messages;
    }

    /** Writes one streamed chunk, then checks {@link PrintWriter#checkError()} — a disconnected
     * client (Stop button, widget closed, tab closed, network drop) never throws from write()/
     * flush() itself (PrintWriter swallows the underlying IOException), so checkError() is the
     * only way this actually surfaces. Throwing here unwinds out of chatStream's
     * try-with-resources around the NIM connection, closing it too — no separate cleanup path. */
    private void onChatDelta(PrintWriter writer, long deadline, String delta) {
        if (System.currentTimeMillis() > deadline) {
            throw new AiStreamException("This response took too long and was stopped.", null, true);
        }
        try {
            writer.write("event: delta\ndata: " + SSE_MAPPER.writeValueAsString(delta) + "\n\n");
        } catch (Exception e) {
            throw new AiStreamException("Failed to encode response chunk.", e, true);
        }
        writer.flush();
        if (writer.checkError()) {
            throw new java.io.UncheckedIOException(new java.io.IOException("client disconnected"));
        }
    }

    /** Reports a chat failure as {@code event: error} if the connection is still alive; a client
     * that's already gone (see {@link #onChatDelta}) has nothing left to write to. */
    private void writeChatError(PrintWriter writer, Exception e) {
        if (e instanceof java.io.UncheckedIOException) {
            return;
        }
        boolean partial = e instanceof AiStreamException ase && ase.anyOutputEmitted();
        String message = e instanceof AiStreamException ase ? ase.getMessage()
                : "Something went wrong generating a response.";
        try {
            writer.write("event: error\ndata: " + SSE_MAPPER.writeValueAsString(
                    Map.of("message", message, "partial", partial)) + "\n\n");
            writer.flush();
        } catch (Exception alsoGone) {
            // Connection died while reporting the error — nothing more to do.
        }
    }

    /**
     * Saves a human edit to one audience's changelog text for a version. Editing developer
     * cascades forward: qa and business were generated from the old developer text, so they're
     * stale the moment it changes. Whichever of qa/business hasn't itself been hand-edited gets
     * regenerated from the new text; one that already carries its own edit is left alone — a
     * human's own edit to a downstream view must never be silently overwritten by this cascade.
     * Editing qa or business directly only ever affects that one view.
     */
    @PUT
    @Path("/projects/{project}/repos/{repo}/changelog-edit")
    public GenerateResponse saveChangelogEdit(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            ChangelogEditRequest request) {
        String audience = request.getAudience();
        if (!"developer".equals(audience) && !"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'developer', 'qa', or 'business'.");
        }
        if (request.getVersion() == null || request.getVersion().isBlank()) {
            throw new AiException("A version is required to save a changelog edit.");
        }
        if (request.getText() == null || request.getText().isBlank()) {
            throw new AiException("Edited text must not be blank.");
        }
        String version = request.getVersion();

        cacheService.saveEdit(project, repo, version, audience, request.getText(), request.getEditedBy());
        long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null, null).id;
        changelogService.createSnapshot(vid, audience, request.getText(), "edit", null, null, null, request.getEditedBy());

        String developerText = "developer".equals(audience) ? request.getText() : null;
        String qaText = "qa".equals(audience) ? request.getText() : null;
        String businessText = "business".equals(audience) ? request.getText() : null;

        return new GenerateResponse(developerText, qaText, businessText, List.of(), 0, true);
    }

    /**
     * Persists a candidate AI generation the dashboard already showed the user for confirmation —
     * see {@code /generate?commit=false}, which produces the exact {@code text} this call saves.
     * No new AI call happens here: this only ever writes what was already generated and reviewed,
     * attributed to the model that produced it. Developer-only for now — qa/business are always a
     * separate, manual generation the user triggers themselves later, never an automatic side
     * effect of saving developer's text.
     */
    @PUT
    @Path("/projects/{project}/repos/{repo}/generate-commit")
    public GenerateResponse generateCommit(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            GenerateCommitRequest request) {
        String audience = request.getAudience();
        if (!"developer".equals(audience) && !"qa".equals(audience) && !"business".equals(audience)) {
            throw new AiException("audience must be 'developer', 'qa', or 'business'.");
        }
        if (request.getText() == null || request.getText().isBlank()) {
            throw new AiException("Generated text must not be blank.");
        }
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new AiException("A model is required to commit a changelog generation.");
        }
        String version = request.getVersion();
        Long buildId = request.getBuildId();
        boolean hasVersion = version != null && !version.isBlank();
        // A manual dashboard generation has no version yet — the human decides it in the push
        // modal. Such a save must be keyed on the pipeline run it came from instead.
        if (!hasVersion && (buildId == null || buildId <= 0)) {
            throw new AiException("A version or a pipeline build ID is required to save a changelog generation.");
        }
        if (!hasVersion) {
            recordedRunService.saveAiDraft("azure", project, repo, buildId, audience,
                    request.getModel(), request.getText(), request.getTokens(), request.getDurationMs());
            return new GenerateResponse(null, null, null, List.of(), 0, true);
        }

        // inputHash is only ever used later as a cache-freshness fingerprint (see
        // ChangelogCacheService#getCurrent) — never required for the save itself. A version fresh
        // out of the pipeline-run generate flow (a specific buildId, a manually pasted PR, or
        // hand-typed text) often has no discoverable git/pipeline trail yet, so resolving it here
        // can legitimately fail; that must never block persisting text the user already reviewed
        // and asked to save. Worst case with a null hash: a later preview doesn't reuse this as a
        // cache hit and regenerates instead — never a correctness problem.
        String inputHash = null;
        try {
            ResolvedRelease resolved = resolveReleaseData(project, repo, version, request.getBranch());
            inputHash = InputHash.of(ReleaseNotePreparer.prepare(resolved.data().getItems()));
        } catch (AiException e) {
            LOG.warning("Could not resolve release data for " + project + "/" + repo + " v" + version
                    + " while committing a changelog generation — saving without an input hash: " + e.getMessage());
        }
        cacheService.put(project, repo, version, audience, request.getModel(), request.getText(), inputHash);
        long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null, null).id;
        changelogService.createSnapshot(vid, audience, request.getText(), "ai", request.getModel(),
                request.getTokens(), request.getDurationMs(), null);

        String developerText = "developer".equals(audience) ? request.getText() : null;
        String qaText = "qa".equals(audience) ? request.getText() : null;
        String businessText = "business".equals(audience) ? request.getText() : null;

        return new GenerateResponse(developerText, qaText, businessText, List.of(), 0, true);
    }

    /**
     * Pushes the current Developer text for one version back into the repo's CHANGELOG.md as a
     * direct commit to {@code branch} (via the service's own PAT, not a branch + PR) — developer-
     * only for now (no other audience has a home in the file), {@code branch} is whatever the
     * caller already resolved this version's entry against (a {@code HistoryEntry.branch} — never
     * stored, never guessed from a dropdown), and the target branch's file is re-checked fresh
     * inside {@link AzureDevOpsOrgConnector#pushChangelogEdit}, not against whatever the page had
     * loaded when it was opened.
     */
    @POST
    @Path("/projects/{project}/repos/{repo}/changelog-push")
    public Map<String, String> pushChangelog(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            GenerateCommitRequest request) {
        String audience = request.getAudience();
        String version = request.getVersion();
        String branch = request.getBranch();
        Long buildId = request.getBuildId();
        if (!"developer".equals(audience)) {
            throw new AiException("Pushing to the repo is only supported for the developer view right now.");
        }
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required to push a changelog.");
        }
        if (branch == null || branch.isBlank()) {
            throw new AiException("A branch is required to push a changelog.");
        }

        // A generate preview is never auto-saved anymore (see /generate-stream), so the caller
        // passes its own on-screen text directly here instead of this endpoint reading it back
        // from the DB. A history-driven push (already-cached, e.g. from the Version history list)
        // omits it and falls back to what's already saved, unchanged from before.
        String requestText = request.getText();
        boolean needsCaching = requestText != null && !requestText.isBlank();
        String text = needsCaching
                ? requestText
                : cacheService.getCurrentText(project, repo, version, "developer")
                        .orElseThrow(() -> new AiException("Nothing generated or edited yet for v" + version + " — nothing to push."));

        // pushChangelogEdit now replaces v{version}'s entry if one exists, creates a new entry if
        // not, and creates CHANGELOG.md itself from scratch if the branch has no changelog file
        // at all yet — validated (must look like a real version, text must be non-empty), and
        // failures propagate as exceptions rather than an empty result to check for.
        String commitUrl = orgConnector.pushChangelogEdit(project, repo, branch, version, text);

        if (needsCaching) {
            // Only now that it's safely committed in the real repo do we let the DB know about
            // it — never before, so the DB can never claim a version the repo doesn't also have.
            // The push itself already succeeded above; resolveReleaseData here is only for the
            // cache-freshness inputHash (see generateCommit's own comment on this), so a failure
            // to resolve it must never make this endpoint report the push as failed.
            String inputHash = null;
            try {
                ResolvedRelease resolved = resolveReleaseData(project, repo, version, branch);
                inputHash = InputHash.of(ReleaseNotePreparer.prepare(resolved.data().getItems()));
            } catch (AiException e) {
                LOG.warning("Could not resolve release data for " + project + "/" + repo + " v" + version
                        + " while recording a pushed changelog — caching without an input hash: " + e.getMessage());
            }
            String model = request.getModel() != null && !request.getModel().isBlank() ? request.getModel() : "unknown";
            cacheService.put(project, repo, version, "developer", model, text, inputHash);
            long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null, null).id;
            changelogService.createSnapshot(vid, "developer", text, "ai", model, 0, 0, null);
        }
        cacheService.markPushed(project, repo, version, "developer", text, commitUrl);

        // Version-free save → push-modal flow: fill the human-chosen version into the recorded
        // run the draft was saved against, and clear the now-obsolete draft.
        if (buildId != null && buildId > 0) {
            recordedRunService.applyPushedVersion("azure", project, repo, buildId, version);
        }

        Map<String, String> response = new LinkedHashMap<>();
        response.put("commitUrl", commitUrl);
        return response;
    }

    /**
     * Deletes one shared revision — a revision is a snapshot across all three audiences (see
     * {@link com.hubsabai.changelog.storage.ChangelogService#createSnapshot}), so this removes
     * whichever of them has a row at {@code sequence} and renumbers all three together, never just
     * one audience in isolation (that would drift its numbering out of alignment with the others).
     */
    @DELETE
    @Path("/projects/{project}/repos/{repo}/changelog-revision")
    public void deleteChangelogRevision(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("version") String version,
            @QueryParam("sequence") int sequence) {
        if (version == null || version.isBlank()) {
            throw new AiException("A version is required to delete a changelog revision.");
        }
        ChangelogVersion cv = ChangelogVersion.findEntry(project, repo, version);
        if (cv == null) {
            throw new AiException("Version not found.");
        }
        changelogService.deleteSharedRevision(cv.id, sequence);
    }
}
