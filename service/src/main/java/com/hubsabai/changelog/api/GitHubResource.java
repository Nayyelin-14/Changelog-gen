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
import com.hubsabai.changelog.connector.github.ChangelogMarkdown;
import com.hubsabai.changelog.connector.github.GitHubOrgConnector;
import com.hubsabai.changelog.connector.github.RunFetchResult;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.util.VersionUtils;
import com.hubsabai.changelog.core.model.OrgFetchResult;
import com.hubsabai.changelog.core.model.PipelineRunSummary;
import com.hubsabai.changelog.core.model.ProjectSummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.core.model.RepositorySummary;
import com.hubsabai.changelog.generation.ChangelogGenerationService;
import com.hubsabai.changelog.generation.RunChangeContext;
import com.hubsabai.changelog.generation.RunChangeDataReader;
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
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GitHub mirror of the dashboard API, mounted at {@code /github} so its routes never collide with
 * Azure DevOps' (which ride the root). The frontend picks the provider and swaps its API base
 * between {@code /api} and {@code /api/github} ({@code web-view/src/lib/provider.ts}).
 *
 * <p>Everything except the {@code GitHubOrgConnector} is shared with Azure: the same Postgres
 * cache, the same AI provider, the same edit/restore/push semantics. The only structural
 * difference is that a GitHub "project" is the configured owner (org or personal account), so the
 * project tier always collapses to that one owner.
 */
@Path("/github")
@Produces(MediaType.APPLICATION_JSON)
public class GitHubResource {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(GitHubResource.class.getName());

    private static final ObjectMapper SSE_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Semaphore CHAT_CONCURRENCY = new Semaphore(20);
    private static final int CHAT_HISTORY_LIMIT = 10;
    private static final long CHAT_TIMEOUT_MS = 60_000;

    @Inject
    RecordedRunService recordedRunService;

    @Inject
    GitHubOrgConnector orgConnector;

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

    // --- navigation ---

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

    /** Repos filtered to ones with a changelog (cached in DB or CHANGELOG.md on default branch). */
    @GET
    @Path("/projects/{project}/repos-with-changelog")
    public List<RepositorySummary> listRepositoriesWithChangelog(@PathParam("project") String project) {
        return orgConnector.listRepositories(project).stream()
                .filter(r -> {
                    try {
                        return orgConnector.hasChangelogFileSafely(project, r.name());
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    /** Per-repo status for the Dev dashboard's repo table. */
    @GET
    @Path("/projects/{project}/repos-overview")
    public List<RepoOverview> reposOverview(@PathParam("project") String project) {
        List<RepositorySummary> repos = orgConnector.listRepositories(project);
        return repos.stream().map(r -> {
            try {
                HistoryResponse resp = history(project, r.name(), null, 0, 10);
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
                return new RepoOverview(r.name(), r.defaultBranch(), latestVersion, latestVersionAt, needsReview);
            } catch (Exception e) {
                LOG.warning("Failed to build repo overview for " + project + "/" + r.name() + ": " + e);
                return new RepoOverview(r.name(), r.defaultBranch(), null, null, 0);
            }
        }).toList();
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/release-version")
    public Response resolveReleaseVersion(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("branch") String branch) {
        String resolvedBranch = branch != null ? branch : orgConnector.defaultBranch(project, repo);
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

        com.hubsabai.changelog.connector.github.ChangelogMarkdown.ChangelogFile file =
                orgConnector.fetchChangelogFileCached(project, repo, branch);
        if (file != null && file.content() != null) {
            List<ChangelogMarkdown.ChangelogEntry> entries = orgConnector.parseChangelogEntries(file.content());
            for (ChangelogMarkdown.ChangelogEntry entry : entries) {
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
        return orgConnector.listWorkItems(project);
    }

    @GET
    @Path("/fetch-all")
    public OrgFetchResult fetchAll() {
        return orgConnector.fetchAll();
    }

    // --- raw repo data ---

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
    @Path("/projects/{project}/repos/{repo}/builds")
    public List<PipelineRunSummary> repoBuilds(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @DefaultValue("20") @QueryParam("top") int top) {
        return orgConnector.listWorkflowRuns(project, repo, top);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/builds/{buildId}/changes")
    public ReleaseData buildChanges(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("buildId") long buildId) {
        Optional<ReleaseData> stored = recordedRunService.getRecordedRunData("github", project, repo, buildId);
        if (stored.isPresent()) {
            return stored.get();
        }
        // Fallback: lazy capture — persists the snapshot so the dashboard never re-fetches GitHub.
        try {
            return recordedRunService.getOrCaptureGitHubRun(project, repo, buildId)
                    .map(RunFetchResult::releaseData)
                    .orElseGet(() -> emptyReleaseData(project, repo, null));
        } catch (Exception e) {
            return emptyReleaseData(project, repo, null);
        }
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/builds/{buildId}/run-context")
    public RunChangeContext runContext(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("buildId") long buildId) {
        Optional<RunChangeContext> stored = recordedRunService.getRecordedRunContext("github", project, repo, buildId);
        if (stored.isPresent()) {
            return stored.get();
        }
        // Fallback: lazy capture — persists the snapshot so the dashboard never re-fetches GitHub.
        try {
            return recordedRunService.getOrCaptureGitHubRun(project, repo, buildId)
                    .map(RunFetchResult::runContext)
                    .orElseGet(RunChangeContext::new);
        } catch (Exception e) {
            return new RunChangeContext();
        }
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/pull-requests/{prId}/details")
    public GitHubOrgConnector.PullRequestDetails pullRequestDetails(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("prId") int prId) {
        return orgConnector.fetchPullRequestDetails(project, repo, prId);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/has-changelog")
    public boolean hasChangelog(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("branch") String branch) {
        return orgConnector.hasChangelogFile(project, repo, branch);
    }

    @GET
    @Path("/projects/{project}/repos/{repo}/pull-requests/{prId}/changelog-location")
    public ChangelogLocationResponse changelogLocation(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @PathParam("prId") int prId) {
        Optional<com.hubsabai.changelog.storage.ReleasePr> entry = rawReleaseService.findLocation(project, repo, prId);
        if (entry.isEmpty()) {
            return new ChangelogLocationResponse("not_found", null, null);
        }
        com.hubsabai.changelog.storage.ReleasePr pr = entry.get();
        String status = "release".equals(pr.stage) ? "released" : "prerelease";
        return new ChangelogLocationResponse(status, pr.version, pr.stage);
    }

    // --- history ---

    @GET
    @Path("/projects/{project}/repos/{repo}/history")
    public HistoryResponse history(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            @QueryParam("branch") String branch,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("10") @QueryParam("limit") int limit) {
        listRepositories(project); // resolve owner existence, cheap
        String resolvedBranch = branch != null ? branch : orgConnector.defaultBranch(project, repo);

        List<GeneratedChangelog> developerEntries = cacheService.getDeveloperEntries(project, repo);
        Map<String, GeneratedChangelog> dbByVersion = developerEntries.stream()
                .filter(e -> e.version != null && !e.version.isBlank() && e.currentText != null)
                .collect(Collectors.toMap(e -> e.version, e -> e, (a, b) -> a));

        List<ChangelogVersion> newVersions = changelogService.listVersions(project, repo, 0, 1000);
        for (ChangelogVersion cv : newVersions) {
            dbByVersion.putIfAbsent(cv.version, null);
        }
        if (!dbByVersion.isEmpty()) {
            return withDrafts(project, repo, resolvedBranch, page,
                    buildHistoryFromDb(project, repo, resolvedBranch, page, limit, dbByVersion));
        }

        var file = orgConnector.fetchChangelogFileCached(project, repo, resolvedBranch);
        List<ChangelogMarkdown.ChangelogEntry> parsed = file != null
                ? new ArrayList<>(orgConnector.parseChangelogEntries(file.content()))
                : new ArrayList<>();
        for (var entry : parsed) {
            if (entry.version() != null && !entry.version().isBlank()) {
                cacheService.saveHistoryEntryIfAbsent(project, repo, entry.version(), entry.date(), entry.body());
            }
        }

        parsed.sort((a, b) -> {
            int byTimestamp = compareDatesDescending(a.date(), b.date());
            return byTimestamp != 0 ? byTimestamp : compareVersionsDescending(a.version(), b.version());
        });
        Set<String> seenVersions = new HashSet<>();
        List<ChangelogMarkdown.ChangelogEntry> deduplicated = new ArrayList<>();
        for (var entry : parsed) {
            String ver = entry.version();
            if (ver != null && !ver.isBlank() && seenVersions.add(ver)) {
                deduplicated.add(entry);
            }
        }
        parsed = deduplicated;
        Map<String, ChangelogMarkdown.ChangelogEntry> gitByVersion = new LinkedHashMap<>();
        for (var ce : parsed) {
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

        List<ChangelogMarkdown.ChangelogEntry> pageGitEntries = pageVersions.stream()
                .map(gitByVersion::get)
                .filter(Objects::nonNull)
                .toList();
        Map<String, GitHubOrgConnector.ChangelogEnrichment> enrichment = file != null && !pageGitEntries.isEmpty()
                ? orgConnector.enrichChangelogEntries(project, repo, file.filename(), pageGitEntries)
                : Map.of();

        List<ChangelogMarkdown.ChangelogEntry> fileEntries = file != null ? parsed : List.of();
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < pageVersions.size(); i++) {
            int globalIndex = from + i;
            String version = pageVersions.get(i);
            var ce = gitByVersion.get(version);
            GeneratedChangelog dbEntry = dbByVersion.get(version);

            if (ce != null) {
                String id = "repo-" + globalIndex + "-" + version + "-" + ce.date();
                var enr = enrichment.get(version);
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
        fileEntries = parsed;
        return withDrafts(project, repo, resolvedBranch, page,
                withUngeneratedPrs(project, repo, resolvedBranch, page, entries, total));
    }

    private HistoryResponse buildHistoryFromDb(String project, String repo, String branch,
            int page, int limit, Map<String, GeneratedChangelog> dbByVersion) {
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

    private static String versionTimestamp(ChangelogMarkdown.ChangelogEntry git, GeneratedChangelog db) {
        if (git != null) {
            return parseChangelogDate(git.date());
        }
        if (db != null && db.currentAt != null) {
            return db.currentAt.toString();
        }
        return null;
    }

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
        List<RecordedPipelineRun> drafts = recordedRunService.listDraftRuns("github", project, repo);
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

    private HistoryResponse withUngeneratedPrs(String project, String repo, String branch,
            int page, List<HistoryEntry> entries, int total) {
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

    // --- generation ---

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
            @QueryParam("buildId") long buildId,
            @QueryParam("commit") @DefaultValue("true") boolean commit) {
        boolean hasManualText = manualText != null && !manualText.isBlank();
        if ((version == null || version.isBlank()) && !hasManualText && buildId <= 0) {
            throw new AiException("A version is required to generate a changelog.");
        }
        if (model == null || model.isBlank()) {
            throw new AiException("A model must be selected to generate a changelog.");
        }
        ReleaseData data;
        if (hasManualText) {
            data = buildReleaseDataFromManual(project, repo, branch, version, manualText);
        } else if (buildId > 0) {
            // Use stored recorded run data as primary source; lazy capture persists the
            // snapshot on a miss so the dashboard never re-fetches GitHub again.
            RunChangeContext runContext = recordedRunService.getOrCaptureGitHubRun(project, repo, buildId)
                    .map(RunFetchResult::runContext)
                    .orElse(null);
            data = runChangeDataReader.toReleaseData(project, repo, branch, "github", runContext);
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
        long start = System.currentTimeMillis();
        String inputHash = InputHash.of(ReleaseNotePreparer.prepare(data.getItems()));
        // A version-free preview can never commit — generated_changelog is keyed on a non-null
        // version, so persisting with a blank one would surface as a raw DB error. Version is
        // only ever filled in later, at push time, by the human in the push modal.
        boolean effectiveCommit = commit && version != null && !version.isBlank();

        if (audience != null && !audience.isBlank()) {
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

    @POST
    @Path("/projects/{project}/repos/{repo}/generate-stream")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Response generateStream(
            @PathParam("project") String project,
            @PathParam("repo") String repo,
            String rawBody) {
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
        long buildId = request.getBuildId() != null ? request.getBuildId() : 0L;
        boolean hasManualText = manualText != null && !manualText.isBlank();
        if ((version == null || version.isBlank()) && !hasManualText && buildId <= 0) {
            throw new AiException("A version is required to generate a changelog.");
        }
        if (model == null || model.isBlank()) {
            throw new AiException("A model must be selected to generate a changelog.");
        }
        ReleaseData data;
        if (manualText != null && !manualText.isBlank()) {
            data = buildReleaseDataFromManual(project, repo, branch, version, manualText);
        } else if (buildId > 0) {
            // Use stored recorded run data as primary source; lazy capture persists the
            // snapshot on a miss so the dashboard never re-fetches GitHub again.
            RunChangeContext runContext = recordedRunService.getOrCaptureGitHubRun(project, repo, buildId)
                    .map(RunFetchResult::runContext)
                    .orElse(null);
            data = runChangeDataReader.toReleaseData(project, repo, branch, "github", runContext);
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
            for (String aud : List.of("developer")) {
                try {
                    AiResult result = generationService.ensureAudience(project, repo, version, aud, model, true, finalData, inputHash, force, false, computed);
                    AiUsage usage = result.getUsage();
                    if (usage != null) {
                        totalTokens += usage.getTotalTokens();
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("audience", aud);
                    payload.put("text", result.getText());
                    payload.put("usage", usage);
                    writer.write("event: audience\ndata: " + SSE_MAPPER.writeValueAsString(payload) + "\n\n");
                    writer.flush();
                } catch (Exception e) {
                    hadError = true;
                    writer.write("event: error\ndata: " + SSE_MAPPER.writeValueAsString(
                            Map.of("error", e.getMessage() != null ? e.getMessage() : "Generation failed")) + "\n\n");
                    writer.flush();
                    break;
                }
            }
            if (!hadError) {
                long durationMs = System.currentTimeMillis() - start;
                writer.write("event: done\ndata: " + SSE_MAPPER.writeValueAsString(Map.of(
                        "durationMs", durationMs,
                        "totalTokens", totalTokens)) + "\n\n");
                writer.flush();
            }
        };
        return Response.ok(stream).type(MediaType.SERVER_SENT_EVENTS).header("Cache-Control", "no-cache").build();
    }

    // --- read-only changelog views ---

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
        return cacheService.getCurrentEntry(project, repo, version, audience)
                .map(e -> new ChangelogMeta(e.currentSource, e.currentModelId, e.currentEditedBy,
                        e.currentAt != null ? e.currentAt.toString() : null, e.previousText != null, e.previousText,
                        e.previousSource, e.previousModelId, e.previousEditedBy,
                        e.previousAt != null ? e.previousAt.toString() : null, false,
                        e.pushedAt != null ? e.pushedAt.toString() : null, e.pushedPullRequestUrl, e.pushedText,
                        finalRevisions, null, null))
                .orElse(new ChangelogMeta(null, null, null, null, false, null, null, null, null, null, false, null, null, null,
                        finalRevisions, null, null));
    }

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

    // --- mutations ---

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
        var target = cv != null ? ChangelogRevision.findBySequence(cv.id, audience, sequence) : null;
        if (target == null) {
            throw new AiException("Revision #" + sequence + " not found for v" + version + " (" + audience + ").");
        }
        cacheService.restoreToRevision(project, repo, version, audience, target.source, target.model, target.editedBy, target.text);
        changelogService.createSnapshot(cv.id, audience, target.text, "restore", target.model, null, null, target.editedBy);
        Map<String, String> response = new LinkedHashMap<>();
        response.put("text", target.text);
        return response;
    }

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
            recordedRunService.saveAiDraft("github", project, repo, buildId, audience,
                    request.getModel(), request.getText(), request.getTokens(), request.getDurationMs());
            return new GenerateResponse(null, null, null, List.of(), 0, true);
        }
        String inputHash = null;
        try {
            ReleaseData rd = orgConnector.fetchRepoChanges(project, repo, null, version, request.getBranch());
            if (!rd.getItems().isEmpty()) {
                inputHash = InputHash.of(ReleaseNotePreparer.prepare(rd.getItems()));
            }
        } catch (Exception e) {
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
        String requestText = request.getText();
        boolean needsCaching = requestText != null && !requestText.isBlank();
        String text = needsCaching
                ? requestText
                : cacheService.getCurrentText(project, repo, version, "developer")
                        .orElseThrow(() -> new AiException("Nothing generated or edited yet for v" + version + " — nothing to push."));

        String prUrl = orgConnector.pushChangelogEdit(project, repo, branch, version, text);

        if (needsCaching) {
            String inputHash = null;
            try {
                ReleaseData data = orgConnector.fetchRepoChanges(project, repo, null, version, branch);
                if (!data.getItems().isEmpty()) {
                    inputHash = InputHash.of(ReleaseNotePreparer.prepare(data.getItems()));
                }
            } catch (Exception e) {
                LOG.warning("Could not resolve release data for " + project + "/" + repo + " v" + version
                        + " while recording a pushed changelog — caching without an input hash: " + e.getMessage());
            }
            String model = request.getModel() != null && !request.getModel().isBlank() ? request.getModel() : "unknown";
            cacheService.put(project, repo, version, "developer", model, text, inputHash);
            long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null, null).id;
            changelogService.createSnapshot(vid, "developer", text, "ai", model, 0, 0, null);
        }
        cacheService.markPushed(project, repo, version, "developer", text, prUrl);

        // Version-free save → push-modal flow: fill the human-chosen version into the recorded
        // run the draft was saved against, and clear the now-obsolete draft.
        if (buildId != null && buildId > 0) {
            recordedRunService.applyPushedVersion("github", project, repo, buildId, version);
        }

        Map<String, String> response = new LinkedHashMap<>();
        response.put("pullRequestUrl", prUrl);
        response.put("commitUrl", prUrl);
        return response;
    }

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

    // --- AI model list (shared) ---

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

    // --- private helpers ---

    private ReleaseData buildReleaseDataFromRaw(String project, String repo, String branch, String version) {
        List<ChangeItem> items = rawReleaseService.findItems(project, repo, version);
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("github");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());
        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    private ReleaseData buildReleaseDataFromChangelog(String project, String repo, String branch, String version) {
        String resolvedBranch = branch != null ? branch : orgConnector.defaultBranch(project, repo);
        var file = orgConnector.fetchChangelogFile(project, repo, resolvedBranch);
        if (file == null) {
            return emptyReleaseData(project, repo, branch);
        }
        List<ChangelogMarkdown.ChangelogEntry> parsed = orgConnector.parseChangelogEntries(file.content());
        if (parsed.isEmpty()) {
            return emptyReleaseData(project, repo, branch);
        }
        String body = parsed.stream()
                .filter(e -> version.equals(e.version()))
                .map(ChangelogMarkdown.ChangelogEntry::body)
                .filter(b -> b != null && !b.isBlank())
                .findFirst()
                .orElse(null);
        if (body == null) {
            return emptyReleaseData(project, repo, branch);
        }
        return buildReleaseDataFromChangelogBody(project, repo, resolvedBranch, body);
    }

    private ReleaseData buildReleaseDataFromSavedDeveloperText(String project, String repo, String branch, String version) {
        String resolvedBranch = branch != null ? branch : orgConnector.defaultBranch(project, repo);
        String body = cacheService.getCurrentText(project, repo, version, "developer").orElse(null);
        if (body == null || body.isBlank()) {
            return emptyReleaseData(project, repo, branch);
        }
        return buildReleaseDataFromChangelogBody(project, repo, resolvedBranch, body);
    }

    private static ReleaseData buildReleaseDataFromChangelogBody(String project, String repo, String branch, String body) {
        List<ChangeItem> items = new ArrayList<>();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String bullet = line.replaceFirst("^[-*]\\s+", "");
            items.add(buildChangeItem(project, repo, bullet, "", List.of()));
        }
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("github");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());
        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    private static ReleaseData emptyReleaseData(String project, String repo, String branch) {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("github");
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
                if (currentTitle != null) {
                    items.add(buildChangeItem(project, repo, currentTitle, currentBody.toString(), currentFiles,
                            currentType, currentId, currentAuthor, currentCategory));
                }
                String rest = trimmed.substring(4).strip();
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
                if (trimmed.contains("/") || trimmed.contains(".")) {
                    currentFiles.add(trimmed);
                } else {
                    if (!currentBody.isEmpty()) currentBody.append("\n");
                    currentBody.append(trimmed);
                }
            }
        }
        if (currentTitle != null) {
            items.add(buildChangeItem(project, repo, currentTitle, currentBody.toString(), currentFiles,
                    currentType, currentId, currentAuthor, currentCategory));
        }
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("github");
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

    private String fetchLiveDeveloperText(String project, String repo, String version, String branch) {
        String resolvedBranch = branch != null ? branch : orgConnector.defaultBranch(project, repo);
        var file = orgConnector.fetchChangelogFile(project, repo, resolvedBranch);
        if (file == null) {
            return null;
        }
        return orgConnector.parseChangelogEntries(file.content()).stream()
                .filter(entry -> version.equals(entry.version()))
                .map(ChangelogMarkdown.ChangelogEntry::body)
                .map(String::strip)
                .findFirst()
                .orElse(null);
    }

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

    private static int compareDatesDescending(String d1, String d2) {
        if (d1 == null && d2 == null) return 0;
        if (d1 == null) return 1;
        if (d2 == null) return -1;
        return d2.compareTo(d1);
    }

    private static String parseChangelogDate(String date) {
        if (date == null) return "0000-01-01T00:00:00Z";
        String trimmed = date.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return trimmed + "T00:00:00Z";
        }
        return trimmed;
    }
}