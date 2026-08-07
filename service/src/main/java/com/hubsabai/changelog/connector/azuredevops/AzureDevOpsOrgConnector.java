package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.BuildChange;
import com.hubsabai.changelog.connector.azuredevops.dto.BuildResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitChangesResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitItemResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitPushRequest;
import com.hubsabai.changelog.connector.azuredevops.dto.GitPushResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitRef;
import com.hubsabai.changelog.connector.azuredevops.dto.ProjectResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.PullRequestResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.RepositoryResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlQuery;
import com.hubsabai.changelog.connector.azuredevops.dto.WiqlResult;
import com.hubsabai.changelog.connector.azuredevops.dto.WorkItemResponse;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.OrgFetchResult;
import com.hubsabai.changelog.core.model.PipelineRunSummary;
import com.hubsabai.changelog.core.model.PrReference;
import com.hubsabai.changelog.core.model.ProjectFetchResult;
import com.hubsabai.changelog.core.model.ProjectSummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.core.model.RepositorySummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Org-wide discovery: {@code org → projects → repos → commits/PRs} plus per-project work items
 * (WIQL). Serves the dashboard mode (cf. {@link com.hubsabai.changelog.connector.AzureDevOpsConnector}
 * for pipeline mode). Every fetch is best-effort — a single failure is captured per-project/per-repo
 * rather than aborting the whole org.
 */
@ApplicationScoped
public class AzureDevOpsOrgConnector {

    private static final int PROJECT_PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final int COMMIT_PAGE_SIZE = 100;
    private static final int PULL_REQUEST_PAGE_SIZE = 100;
    private static final int WORK_ITEM_BATCH_SIZE = 200;
    private static final int PR_MERGE_LOOKUP_MAX_PAGES = 5;

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    @Inject
    @ConfigProperty(name = "azure.devops.pat", defaultValue = "CHANGE_ME")
    String pat;

    @Inject
    @ConfigProperty(name = "azure-devops/mp-rest/url", defaultValue = "https://dev.azure.com")
    String baseUrl;

    // Different process templates (and customized ones) name their "finished" state differently
    // (CMMI: Closed, Scrum: Done, Agile: Closed, some custom processes: Completed) — configurable
    // per-project/customer rather than hardcoded, so a changelog never includes work that hasn't
    // actually shipped (e.g. still "Proposed"/"Active") just because we guessed the wrong state name.
    @Inject
    @ConfigProperty(name = "azure.devops.done-states", defaultValue = "Closed,Done,Resolved,Completed")
    String doneStatesConfig;

    private static final Logger LOG = Logger.getLogger(AzureDevOpsOrgConnector.class.getName());

    // ---- Changelog-entry enrichment caches (matchByTag/matchByFileHistory) ----
    //
    // Without caching, every /history page load re-fetches immutable version metadata from Azure
    // DevOps — the dominant cost of page load. Tags/file-history get a short TTL (new tags can
    // appear); individual commits are immutable once fetched and cached without expiry.
    private static final Duration ENRICHMENT_CACHE_TTL = Duration.ofMinutes(5);

    private record TagsCacheEntry(List<GitRef> tags, long fetchedAtMillis) {
        boolean expired() { return System.currentTimeMillis() - fetchedAtMillis > ENRICHMENT_CACHE_TTL.toMillis(); }
    }
    private final Map<String, TagsCacheEntry> tagsCache = new ConcurrentHashMap<>();

    private record FileHistoryCacheEntry(List<CommitResponse> commits, long fetchedAtMillis) {
        boolean expired() { return System.currentTimeMillis() - fetchedAtMillis > ENRICHMENT_CACHE_TTL.toMillis(); }
    }
    private final Map<String, FileHistoryCacheEntry> fileHistoryCache = new ConcurrentHashMap<>();

    // Bounded LRU, not ConcurrentHashMap: commits are immutable so entries never expire, but
    // without a cap this grows for the app's entire uptime (one entry per commit ever looked up,
    // across every repo) — the actual cause of a long-running instance slowly using more memory.
    private static final int COMMIT_CACHE_MAX_ENTRIES = 5000;
    private final Map<String, CommitResponse> commitCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CommitResponse> eldest) {
                    return size() > COMMIT_CACHE_MAX_ENTRIES;
                }
            });

    /** Lists every project in the org, following the {@code x-ms-continuationtoken} header until it's absent. */
    public List<ProjectSummary> listProjects() {
        List<ProjectSummary> projects = new ArrayList<>();
        String continuationToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            try (Response response = client.listProjectsPage(org, continuationToken, PROJECT_PAGE_SIZE, AzureDevOpsRestClient.API_VERSION)) {
                int status = response.getStatus();
                if (status < 200 || status >= 300) {
                    String body = response.readEntity(String.class);
                    String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
                    String hint = status == 401 || status == 203
                            ? " — check that the PAT is valid, not expired, and has the required scopes."
                            : "";
                    throw new IllegalStateException(
                            "Azure DevOps call failed while listing projects for org '" + org + "': HTTP "
                                    + status + hint + "\nBody: " + snippet);
                }
                AzureDevOpsListResponse<ProjectResponse> body =
                        response.readEntity(new GenericType<AzureDevOpsListResponse<ProjectResponse>>() {});
                for (ProjectResponse p : body.valueOrEmpty()) {
                    projects.add(new ProjectSummary(p.id(), p.name(), p.description()));
                }
                continuationToken = response.getHeaderString("x-ms-continuationtoken");
            } catch (jakarta.ws.rs.WebApplicationException e) {
                throw new IllegalStateException("Azure DevOps call failed while listing projects for org '" + org + "': " + e.getMessage(), e);
            }
            if (continuationToken == null || continuationToken.isBlank()) {
                break;
            }
        }
        return projects;
    }

    /**
     * Azure DevOps answers auth failures (bad/expired PAT, missing scope, org sign-in redirect) with an
     * HTML page instead of JSON. Jackson's "could not be mapped" exception on that HTML hides the real
     * cause — this surfaces the actual status code and a body snippet instead.
     */
    private void requireJson(Response response, String action) {
        String contentType = response.getHeaderString("Content-Type");
        boolean looksLikeJson = contentType != null && contentType.toLowerCase().contains("json");
        if (response.getStatus() >= 200 && response.getStatus() < 300 && looksLikeJson) {
            return;
        }

        String body = response.readEntity(String.class);
        String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        String hint = response.getStatus() == 401 || response.getStatus() == 203
                ? " — check that the PAT is valid, not expired, and has the required scopes (Code: Read, Work Items: Read, Project and Team: Read, Build: Read)."
                : "";
        throw new IllegalStateException(
                "Azure DevOps call failed while " + action + ": HTTP " + response.getStatus()
                        + ", content-type " + contentType + hint + "\nBody: " + snippet);
    }

    /** Repositories don't paginate on Azure DevOps — one call returns all of them for the project. */
    public List<RepositorySummary> listRepositories(String project) {
        AzureDevOpsListResponse<RepositoryResponse> response =
                client.listRepositories(org, project, AzureDevOpsRestClient.API_VERSION);
        return response.valueOrEmpty().stream()
                .map(r -> new RepositorySummary(r.id(), r.name(), project, r.defaultBranch()))
                .toList();
    }

    /** Recent pipeline runs across every pipeline definition that builds this repo — filtered by
     * repository, not by a specific pipeline id, so the dashboard never needs a repo→pipeline
     * mapping configured anywhere. Each run's own {@code buildId} is what the "Generate new"
     * page's build-changes fetch keys off (see {@link #fetchRunChanges}). */
    public List<PipelineRunSummary> listRecentBuilds(String project, String repo, int top) {
        RepositorySummary repository = listRepositories(project).stream()
                .filter(r -> r.name().equals(repo))
                .findFirst()
                .orElse(null);
        if (repository == null) {
            return List.of();
        }
        // queryOrder=finishTimeDescending silently excludes any build that hasn't finished yet (no
        // finishTime to sort by) — a currently-running pipeline would never show up on the
        // dashboard at all. startTimeDescending sorts by a field every started build already has.
        AzureDevOpsListResponse<BuildResponse> response = client.listBuildsForRepository(
                org, project, repository.id(), "TfsGit", top, "startTimeDescending", AzureDevOpsRestClient.API_VERSION);
        return response.valueOrEmpty().stream()
                .map(b -> new PipelineRunSummary(
                        b.id(),
                        b.buildNumber(),
                        b.status(),
                        b.result(),
                        b.finishTime(),
                        b.sourceBranch(),
                        b.sourceVersion(),
                        b.definition() != null ? b.definition().name() : null,
                        parsePrNumber(b.triggerInfo()),
                        fetchCommitTitle(project, repository.id(), b.sourceVersion())))
                .toList();
    }

    /** The triggering commit's own message (first line only) — this is what Azure DevOps's own
     * "Runs" list shows as each run's description (e.g. "Merged PR 1277: Revert ..."), and without
     * it every run just shows the same version/branch and is impossible to tell apart at a glance.
     * Best-effort: a commit that's since been GC'd or otherwise unreachable shouldn't blank out
     * the whole runs list, just that one run's title. */
    private String fetchCommitTitle(String project, String repositoryId, String commitId) {
        if (commitId == null) return null;
        try {
            CommitResponse commit = client.getCommit(org, project, repositoryId, commitId, AzureDevOpsRestClient.API_VERSION);
            String comment = commit.comment();
            if (comment == null || comment.isBlank()) return null;
            int newline = comment.indexOf('\n');
            return (newline >= 0 ? comment.substring(0, newline) : comment).trim();
        } catch (WebApplicationException e) {
            return null;
        }
    }

    private static Integer parsePrNumber(Map<String, String> triggerInfo) {
        if (triggerInfo == null) return null;
        String raw = triggerInfo.get("pr.number");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Unbounded — walks the repo's entire history. Prefer the {@code since}/{@code until} overload for user-triggered generation. */
    public ReleaseData fetchRepoChanges(String project, String repo) {
        // Auto-detect: find the latest tag and use it as the previous version
        return fetchRepoChanges(project, repo, null, null, null);
    }

    /**
     * Version-based repo changes — matches the pipeline's tag-range approach.
     * {@code fromVersion} is the previous release version (auto-detected if null/empty).
     * {@code toVersion} is the version being released now.
     * {@code branch} defaults to the repo's default branch.
     */
    public ReleaseData fetchRepoChanges(String project, String repo, String fromVersion, String toVersion, String branch) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);

        if (toVersion == null || toVersion.isBlank()) {
            // No target version at all — just walk from the branch tip, unbounded.
            String head = resolveBranchHead(project, repo, resolvedBranch);
            return buildReleaseData(project, repo, resolvedBranch,
                    fetchCommitsByRange(project, repo, null, head, resolvedBranch), null, head);
        }

        VersionRangeResult range = findVersionRange(project, repo, resolvedBranch, fromVersion, toVersion);
        if (!range.notYetShipped()) {
            // Confirmed historical either way — don't fall through to branch-tip, since showing
            // unrelated recent commits under an old version's name would be actively wrong.
            if (range.commits().isEmpty()) {
                // No tag or marker recognized for this version — no boundary to correlate PRs
                // against either, so an empty result is the honest answer, not "recent commits".
                return emptyReleaseData(project, repo, resolvedBranch);
            }
            List<ChangeItem> items = new ArrayList<>();
            for (CommitResponse c : range.commits()) items.add(toChangeItem(project, repo, c));
            return buildReleaseData(project, repo, resolvedBranch, items, range.fromCommitId(), range.toCommitId());
        }

        // Hasn't shipped yet (no tag, no marker commit) — this is the upcoming release, so the
        // range runs from the last known release up to the branch tip.
        String toCommitId = resolveBranchHead(project, repo, resolvedBranch);
        VersionTag prev = findPreviousTag(project, repo, toVersion);
        String fromCommitId = prev != null ? prev.commitId() : null;
        return buildReleaseData(project, repo, resolvedBranch,
                fetchCommitsByRange(project, repo, fromCommitId, toCommitId, resolvedBranch), fromCommitId, toCommitId);
    }

    private ReleaseData.ReleaseMeta buildMeta(String project, String repo, String branch) {
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg(org);
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(LocalDate.now().toString());
        return meta;
    }

    private ReleaseData emptyReleaseData(String project, String repo, String resolvedBranch) {
        ReleaseData data = new ReleaseData();
        data.setRelease(buildMeta(project, repo, resolvedBranch));
        data.setItems(List.of());
        return data;
    }

    private ReleaseData buildReleaseData(String project, String repo, String resolvedBranch,
                                          List<ChangeItem> commitItems, String fromCommitId, String toCommitId) {
        List<ChangeItem> items = new ArrayList<>(commitItems);
        items.addAll(fetchPullRequestsForRange(project, repo, fromCommitId, toCommitId, resolvedBranch));
        dedupeMergeCommits(items, project, repo);

        ReleaseData data = new ReleaseData();
        data.setRelease(buildMeta(project, repo, resolvedBranch));
        data.setItems(items);
        return data;
    }

    /**
     * Everything merged into {@code branch} since the last release this repo's OWN git history
     * actually recognizes (a real tag, or its release-marker-commit convention) — found by
     * scanning from the branch tip, never by asking "what version has this app's own DB
     * generated a changelog for". That distinction matters: this app's own AI-generation flow can
     * produce a changelog for a version number nothing in git has reached yet (no tag, no marker
     * commit merged for it), and asking {@link #fetchRepoChanges} for "since [that version]" then
     * falls through {@link #findPreviousTag}'s tag-only lookup, which finds nothing and silently
     * treats the ENTIRE repo history as "since the last release" instead of failing loudly. Used
     * by the dashboard's pending-PR list, which needs to stay correct independent of how far
     * ahead this app's own generated changelogs have gotten.
     */
    public ReleaseData fetchChangesSinceLastRelease(String project, String repo, String branch) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);
        String fromCommitId = findLatestReleaseBoundaryCommitId(project, repo, resolvedBranch);
        String toCommitId = resolveBranchHead(project, repo, resolvedBranch);
        return buildReleaseData(project, repo, resolvedBranch,
                fetchCommitsByRange(project, repo, fromCommitId, toCommitId, resolvedBranch), fromCommitId, toCommitId);
    }

    /** Newest-first scan for the first commit carrying a recognized release marker — a real git
     * tag, or a "Release X.Y.Z [skip ci]"-style commit message — whatever version it reports.
     * Null if nothing recognizable turns up within the scan cap ({@link #MAX_PAGES}), meaning the
     * caller should treat the whole branch as unreleased rather than guess a boundary. */
    private String findLatestReleaseBoundaryCommitId(String project, String repo, String branch) {
        Map<String, String> tagVersionsByCommitId = listTagVersionsByCommitId(project, repo);
        int skip = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommitsByRange(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, null, null, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> pageCommits = response.valueOrEmpty();
            if (pageCommits.isEmpty()) break;
            for (CommitResponse c : pageCommits) {
                if (tagVersionsByCommitId.containsKey(c.commitId()) || extractAnyReleaseVersion(c.comment()) != null) {
                    return c.commitId();
                }
            }
            skip += pageCommits.size();
            if (pageCommits.size() < COMMIT_PAGE_SIZE) break;
        }
        return null;
    }

    /**
     * Builds a ReleaseData straight from one pipeline run's own commits/work items/PRs, for the
     * pipeline-run changelog flow. Azure DevOps' Build API already scopes {@code /changes} and
     * {@code /workitems} to "since the previous build of this same pipeline definition" — exactly
     * a per-release delta — so unlike {@link #fetchRepoChanges} this needs no tag lookup, semver
     * parsing, or history scan. PR ids are recovered from "Merged PR N" commit messages (the same
     * {@link PrReference} used by {@link #dedupeMergeCommits}) plus the run's own {@code
     * pr.number} trigger info when the run itself was PR-triggered.
     */
    public ReleaseData fetchRunChanges(String project, String repo, int buildId) {
        BuildResponse build = client.getBuild(org, project, buildId, AzureDevOpsRestClient.API_VERSION);
        List<BuildChange> changes = client.getBuildChanges(org, project, buildId, 500, AzureDevOpsRestClient.API_VERSION).valueOrEmpty();
        List<WiqlResult.WorkItemReference> workItemRefs =
                client.getBuildWorkItems(org, project, buildId, 500, AzureDevOpsRestClient.API_VERSION).valueOrEmpty();

        Set<String> prIds = new LinkedHashSet<>();
        // Tracks which merge commit produced which PR id — lets the sourceVersion check below
        // single out "the PR this build's own commit landed", as opposed to every PR merge that
        // happens to fall in Azure's "changes since previous build" window (see that check for why
        // this window can span more than one PR).
        Map<String, String> mergeCommitToPr = new HashMap<>();
        List<BuildChange> unresolvedChanges = new ArrayList<>();
        for (BuildChange change : changes) {
            String prId = PrReference.extractId(change.message());
            if (prId != null) {
                // The merge-commit summary itself — represented as a PULL_REQUEST item below
                // instead of a raw commit bullet.
                prIds.add(prId);
                mergeCommitToPr.put(change.id(), prId);
            } else {
                unresolvedChanges.add(change);
            }
        }

        // A squash/rebase completion (or a repo with a customized default-commit-message
        // template) lands a commit that never mentions "Merged PR N" even though it's still
        // exactly one PR's landing commit — fall back to matching by commit ID against completed
        // PRs' own lastMergeCommit, which Azure DevOps sets regardless of merge strategy.
        Map<String, PullRequestResponse> prByMergeCommitId = unresolvedChanges.isEmpty()
                ? Map.of()
                : findCompletedPrsByMergeCommit(project, repo, build.sourceBranch(),
                        unresolvedChanges.stream().map(BuildChange::id).collect(Collectors.toSet()));

        List<ChangeItem> items = new ArrayList<>();
        // Collect commit IDs for batch file-path fetching after the loop
        List<String> commitIdsForPaths = new ArrayList<>();
        for (BuildChange change : unresolvedChanges) {
            PullRequestResponse matched = prByMergeCommitId.get(change.id());
            if (matched != null) {
                String matchedPrId = String.valueOf(matched.pullRequestId());
                prIds.add(matchedPrId);
                mergeCommitToPr.put(change.id(), matchedPrId);
                continue;
            }
            ChangeItem item = new ChangeItem();
            item.setType(ChangeItem.ItemType.COMMIT);
            item.setId(change.id());
            item.setTitle(change.message());
            item.setDescription(change.message());
            item.setCategory(ChangeCategoryClassifier.fromText(change.message()));
            item.setAuthor(change.author() != null ? change.author().displayName() : null);
            item.setProject(project);
            item.setRepo(repo);
            item.setLinks(change.displayUri() != null ? List.of(change.displayUri()) : List.of());
            items.add(item);
            commitIdsForPaths.add(change.id());
        }
        // Fetch file-level changes for each unresolved commit in parallel
        if (!commitIdsForPaths.isEmpty()) {
            Map<String, List<String>> pathCache = new ConcurrentHashMap<>();
            commitIdsForPaths.forEach(id ->
                    pathCache.put(id, fetchCommitFilePaths(project, repo, id)));
            for (ChangeItem item : items) {
                List<String> paths = pathCache.get(item.getId());
                if (paths != null) item.setFilePaths(paths);
            }
        }

        String triggeredPr = build.triggerInfo() != null ? build.triggerInfo().get("pr.number") : null;
        if (triggeredPr != null && !triggeredPr.isBlank()) {
            prIds.add(triggeredPr);
        }

        // Azure DevOps' "changes since previous build" is relative to the last build record, full
        // stop — it does NOT skip over a build that was canceled before completing. A PR merge
        // that triggers a build which then gets canceled (a very live scenario: someone pushes
        // again before CI finishes) leaves that PR's merge commit sitting in the NEXT build's own
        // delta too, so this run's "changes" can span more than one PR even though the Pipeline
        // runs table (built from this same build's own sourceVersion, see listRecentBuilds) shows
        // it as being about exactly one.
        //
        // But more than one PR in the window isn't always that — a release/promotion pipeline can
        // legitimately batch several already-merged PRs into one build on purpose (e.g. "QA to
        // Prod"), and those all belong in the changelog. Only narrow down to sourceVersion's own
        // PR when the immediately preceding build of this same definition was itself canceled —
        // that's the specific condition that inflates the window, not merely its size.
        String ownPrId = mergeCommitToPr.get(build.sourceVersion());
        if (ownPrId != null && prIds.size() > 1 && build.definition() != null
                && previousBuildWasCanceled(project, build.definition().id(), build.id())) {
            prIds = new LinkedHashSet<>(Set.of(ownPrId));
        }

        // Work items referenced by the build's own commits (from /workitems above) PLUS work
        // items linked directly to each PR in this run — a PR can have a work item linked via
        // its "Development" panel without any commit message ever mentioning it, and the build's
        // own /workitems endpoint only sees commit-referenced ones, so it misses those.
        Set<Integer> workItemIds = workItemRefs.stream()
                .map(WiqlResult.WorkItemReference::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String prId : prIds) {
            try {
                int id = Integer.parseInt(prId);
                PullRequestResponse pr = client.getPullRequestById(org, id, AzureDevOpsRestClient.API_VERSION);
                items.add(toChangeItem(project, repo, pr));
                client.getPullRequestWorkItems(org, project, repo, id, AzureDevOpsRestClient.API_VERSION)
                        .valueOrEmpty()
                        .forEach(ref -> workItemIds.add(ref.id()));
            } catch (NumberFormatException | WebApplicationException e) {
                LOG.warning("Failed to resolve PR " + prId + " from build " + buildId + " changes: " + e);
            }
        }

        if (!workItemIds.isEmpty()) {
            String idsParam = workItemIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            AzureDevOpsListResponse<WorkItemResponse> batch =
                    client.getWorkItemsBatch(org, project, idsParam, AzureDevOpsRestClient.API_VERSION);
            for (WorkItemResponse wi : batch.valueOrEmpty()) {
                items.add(toChangeItem(project, wi));
            }
        }

        dedupeMergeCommits(items, project, repo);

        String branch = build.sourceBranch();
        if (branch != null && branch.startsWith("refs/heads/")) {
            branch = branch.substring("refs/heads/".length());
        }

        ReleaseData data = new ReleaseData();
        data.setRelease(buildMeta(project, repo, branch));
        data.setItems(items);
        return data;
    }

    /** True when the build immediately preceding {@code buildId} in this same pipeline definition
     * was canceled — that's the specific condition under which Azure's "changes since previous
     * build" computes against an OLDER checkpoint than the Pipeline runs table implies (see the
     * narrowing check in {@link #fetchRunChanges}), so it's the only case worth checking for.
     * Best-effort: any lookup failure (rate limit, transient error) is treated as "no", since
     * trusting the full accumulated PR list is the safer default when this can't be determined. */
    private boolean previousBuildWasCanceled(String project, int definitionId, int buildId) {
        try {
            AzureDevOpsListResponse<BuildResponse> response = client.listBuildsForDefinition(
                    org, project, definitionId, 5, "queueTimeDescending", AzureDevOpsRestClient.API_VERSION);
            return response.valueOrEmpty().stream()
                    .filter(b -> b.id() < buildId)
                    .max(Comparator.comparingInt(BuildResponse::id))
                    .map(b -> "canceled".equalsIgnoreCase(b.result()))
                    .orElse(false);
        } catch (WebApplicationException e) {
            return false;
        }
    }

    /**
     * ID-based counterpart to {@link PrReference}: maps each of {@code commitIds} to the completed
     * PR whose {@code lastMergeCommit} landed it on {@code targetBranch}. Azure DevOps sets
     * {@code lastMergeCommit} for every completed PR no matter which merge strategy (merge / squash
     * / rebase) was used or how the repo's commit-message template is configured, so this catches
     * PRs that {@link PrReference}'s text match on "Merged PR N" misses. Completed PRs come back
     * newest-first and a build's own delta is always recent, so this stops as soon as every
     * requested commit is accounted for rather than scanning the repo's whole PR history.
     */
    private Map<String, PullRequestResponse> findCompletedPrsByMergeCommit(
            String project, String repo, String targetBranch, Set<String> commitIds) {
        Map<String, PullRequestResponse> byCommitId = new HashMap<>();
        if (targetBranch == null || commitIds.isEmpty()) {
            return byCommitId;
        }
        int skip = 0;
        for (int page = 0; page < PR_MERGE_LOOKUP_MAX_PAGES; page++) {
            AzureDevOpsListResponse<PullRequestResponse> response = client.listPullRequests(
                    org, project, repo, "completed", targetBranch, PULL_REQUEST_PAGE_SIZE, skip, AzureDevOpsRestClient.API_VERSION);
            List<PullRequestResponse> prs = response.valueOrEmpty();
            if (prs.isEmpty()) break;
            for (PullRequestResponse pr : prs) {
                String commitId = pr.lastMergeCommit() != null ? pr.lastMergeCommit().commitId() : null;
                if (commitId != null && commitIds.contains(commitId)) {
                    byCommitId.put(commitId, pr);
                }
            }
            skip += prs.size();
            if (byCommitId.size() >= commitIds.size() || prs.size() < PULL_REQUEST_PAGE_SIZE) break;
        }
        return byCommitId;
    }

    /**
     * Drops the noise, restores the real content. The squash-merge "Merged PR N" commit (caught
     * by {@link PrReference#extractId}) adds nothing beyond the PR entry already in {@code items},
     * so it's removed. But a PR's own source commits are NOT interchangeable noise — for a squash
     * merge they never land on the target branch's own history at all (only the merge commit
     * does), so without re-fetching them via Azure DevOps' PR→commits link, a squash-merged PR
     * shows zero commits even though real per-commit content exists (verified: PR #1268 in
     * hubsabai-vscode has exactly one such commit, invisible from the build's own /changes list).
     * For a regular (non-squash) merge those commits are already present from the raw build/range
     * changes, so re-adding is skipped by id to avoid a duplicate row.
     */
    private void dedupeMergeCommits(List<ChangeItem> items, String project, String repo) {
        Set<String> prIds = items.stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.PULL_REQUEST)
                .map(ChangeItem::getId)
                .collect(Collectors.toSet());
        items.removeIf(i -> i.getType() == ChangeItem.ItemType.COMMIT
                && prIds.contains(PrReference.extractId(i.getTitle())));

        Set<String> existingCommitIds = items.stream()
                .filter(i -> i.getType() == ChangeItem.ItemType.COMMIT)
                .map(ChangeItem::getId)
                .collect(Collectors.toCollection(HashSet::new));
        for (String prId : prIds) {
            for (CommitResponse c : fetchPullRequestCommits(project, repo, prId)) {
                if (existingCommitIds.add(c.commitId())) {
                    items.add(toChangeItem(project, repo, c));
                }
            }
        }
    }

    private List<CommitResponse> fetchPullRequestCommits(String project, String repo, String prId) {
        try {
            AzureDevOpsListResponse<CommitResponse> response = client.listPullRequestCommits(
                    org, project, repo, Integer.parseInt(prId), AzureDevOpsRestClient.API_VERSION);
            return response.valueOrEmpty();
        } catch (Exception e) {
            LOG.warning("listPullRequestCommits failed for PR " + prId + " in " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    /** One work item linked to a PR — just enough to render a plain bullet, not a full {@link ChangeItem}.
     * {@code description} is the work item's own field (System.Description) — Azure DevOps stores it as
     * HTML, so callers that show/send it as plain text need to strip tags first. {@code state},
     * {@code assignedTo} and {@code url} are process metadata for the UI only — not meaningful
     * changelog content, so callers shouldn't feed them to the AI. */
    public record WorkItemSummary(int id, String title, String type, String description,
                                   String state, String assignedTo, String url) {}

    /** Everything needed to build a raw, non-AI changelog entry for one PR: its own
     * title/description/author, its commit messages, and its linked work items' titles/types. */
    public record PullRequestDetails(
            int prId, String title, String description, String author,
            List<String> commitMessages, List<WorkItemSummary> workItems) {}

    /**
     * Fetches one PR's metadata, commits, and work items by PR number (pipeline raw-init flow).
     * Pure data fetch — no AI involved. {@code getPullRequestById} is org-wide (PR id is unique
     * per org); project/repo-scoped calls (commits, work items) fail independently if the
     * caller-supplied project/repo don't match where the PR actually lives, but the PR's own
     * title/description is still returned rather than failing the whole lookup.
     */
    public PullRequestDetails fetchPullRequestDetails(String project, String repo, int prId) {
        PullRequestResponse pr = client.getPullRequestById(org, prId, AzureDevOpsRestClient.API_VERSION);

        List<String> commitMessages = new ArrayList<>();
        try {
            AzureDevOpsListResponse<CommitResponse> commits = client.listPullRequestCommits(
                    org, project, repo, prId, AzureDevOpsRestClient.API_VERSION);
            for (CommitResponse c : commits.valueOrEmpty()) {
                if (c.comment() != null && !c.comment().isBlank()) {
                    commitMessages.add(c.comment());
                }
            }
        } catch (Exception e) {
            LOG.warning("listPullRequestCommits failed for PR " + prId + " in " + project + "/" + repo + ": " + e);
        }

        List<WorkItemSummary> workItems = new ArrayList<>();
        try {
            AzureDevOpsListResponse<WiqlResult.WorkItemReference> refs = client.getPullRequestWorkItems(
                    org, project, repo, prId, AzureDevOpsRestClient.API_VERSION);
            List<Integer> ids = refs.valueOrEmpty().stream().map(WiqlResult.WorkItemReference::id).toList();
            if (!ids.isEmpty()) {
                String idsParam = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
                AzureDevOpsListResponse<WorkItemResponse> batch =
                        client.getWorkItemsBatch(org, project, idsParam, AzureDevOpsRestClient.API_VERSION);
                for (WorkItemResponse wi : batch.valueOrEmpty()) {
                    // getWorkItemsBatch doesn't return _links (that needs $expand, unlike the single-item
                    // GET), so htmlUrl(wi) is always null here — build the web URL ourselves instead.
                    workItems.add(new WorkItemSummary(wi.getId(),
                            WorkItemFields.string(wi, "System.Title"),
                            WorkItemFields.string(wi, "System.WorkItemType"),
                            WorkItemFields.string(wi, "System.Description"),
                            WorkItemFields.string(wi, "System.State"),
                            WorkItemFields.string(wi, "System.AssignedTo"),
                            "https://dev.azure.com/" + org + "/" + project + "/_workitems/edit/" + wi.getId()));
                }
            }
        } catch (Exception e) {
            LOG.warning("Fetching linked work items failed for PR " + prId + " in " + project + "/" + repo + ": " + e);
        }

        return new PullRequestDetails(
                pr.pullRequestId(), pr.title(), pr.description(),
                pr.createdBy() != null ? pr.createdBy().displayName() : null,
                commitMessages, workItems);
    }

    /** Counts commits in the tag range for a version. Returns 0 if the tag isn't found or there's no range. */
    public int commitCountForVersion(String project, String repo, String version, String branch) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);
        String toCommitId = resolveBranchHead(project, repo, resolvedBranch);
        if (toCommitId == null) return 0;

        String fromCommitId = findTagCommit(project, repo, version);
        if (fromCommitId == null) {
            // Try the previous tag instead
            VersionTag prev = findPreviousTag(project, repo, version);
            fromCommitId = prev != null ? prev.commitId() : null;
        }
        if (fromCommitId == null) return 0;

        return countCommitsInRange(project, repo, fromCommitId, toCommitId, resolvedBranch);
    }

    private int countCommitsInRange(String project, String repo, String fromCommitId, String toCommitId, String branch) {
        return fetchCommitsUpTo(project, repo, branch, toCommitId, fromCommitId).size();
    }

    /** Resolves the branch tip (HEAD commit SHA) for a given branch. */
    private String resolveBranchHead(String project, String repo, String branch) {
        // Fetch the latest commit on the branch — Azure DevOps commit list with $top=1 and no from/to returns the tip
        AzureDevOpsListResponse<CommitResponse> resp = client.listCommitsByRange(
                org, project, repo, 1, 0, null, null, branch, null, AzureDevOpsRestClient.API_VERSION);
        List<CommitResponse> commits = resp.valueOrEmpty();
        return commits.isEmpty() ? null : commits.get(0).commitId();
    }

    /** Fetches every tag ref for a repo — the shared fetch behind every tag-based version lookup below. */
    private List<GitRef> listTagRefs(String project, String repo) {
        String key = project + "/" + repo;
        TagsCacheEntry cached = tagsCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.tags();
        }
        Response response = client.listRefs(org, project, repo, "tags/", true, AzureDevOpsRestClient.API_VERSION);
        List<GitRef> tags;
        try {
            AzureDevOpsListResponse<GitRef> body =
                    response.readEntity(new GenericType<AzureDevOpsListResponse<GitRef>>() {});
            tags = body.valueOrEmpty();
        } finally {
            response.close();
        }
        tagsCache.put(key, new TagsCacheEntry(tags, System.currentTimeMillis()));
        return tags;
    }

    /** Commit metadata (author, date, ...) never changes once a commit exists — cached without
     * expiry, keyed by repo + commit id. */
    private CommitResponse getCommitCached(String project, String repo, String commitId) {
        return commitCache.computeIfAbsent(project + "/" + repo + "/" + commitId,
                k -> client.getCommit(org, project, repo, commitId, AzureDevOpsRestClient.API_VERSION));
    }

    /** Strips a tag's version-prefix convention ({@code released/v}, {@code release-}, or {@code v}),
     * in that priority order, to recover the bare version string. */
    private static String stripVersionTagPrefix(String tagName) {
        if (tagName.startsWith("released/v")) return tagName.substring("released/v".length());
        if (tagName.startsWith("release-")) return tagName.substring("release-".length());
        if (tagName.startsWith("v")) return tagName.substring(1);
        return tagName;
    }

    /** Finds the tag commit SHA for a version (tries common tag patterns). */
    private String findTagCommit(String project, String repo, String version) {
        try {
            for (GitRef ref : listTagRefs(project, repo)) {
                String tn = ref.tagName();
                if (tn == null) continue;
                if (tn.equals(version) || tn.equals("v" + version) || tn.equals("release-" + version)
                        || tn.equals("released/v" + version)) {
                    return ref.commitId();
                }
            }
        } catch (Exception e) {
            LOG.warning("findTagCommit failed for " + project + "/" + repo + " v" + version + ": " + e);
        }
        return null;
    }

    /** Highest version tag that sorts below {@code currentVersion}, or null. */
    private VersionTag findPreviousTag(String project, String repo, String currentVersion) {
        List<GitRef> tags;
        try {
            tags = listTagRefs(project, repo);
        } catch (Exception e) {
            LOG.warning("listRefs failed for " + project + "/" + repo + ": " + e);
            return null;
        }

        int[] current = parseSemver(currentVersion);
        if (current == null) return null;

        VersionTag best = null;
        int[] bestSegments = null;

        for (GitRef ref : tags) {
            String tn = ref.tagName();
            if (tn == null) continue;
            String ver = stripVersionTagPrefix(tn);

            int[] segments = parseSemver(ver);
            if (segments == null) continue;

            if (compareSegments(segments, current) >= 0) continue; // not previous

            if (best == null || compareSegments(segments, bestSegments) > 0) {
                best = new VersionTag(ver, ref.commitId());
                bestSegments = segments;
            }
        }
        return best;
    }

    private static int[] parseSemver(String version) {
        if (version == null) return null;
        String[] parts = version.split("\\.");
        if (parts.length < 2) return null;
        int[] segments = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                segments[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return segments;
    }

    private static int compareSegments(int[] a, int[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return Integer.compare(a.length, b.length);
    }

    private record VersionTag(String version, String commitId) {}

    /**
     * {@code notYetShipped=true} means the caller should fall through to the "upcoming release"
     * default (branch tip). {@code false} with empty {@code commits} means the version is
     * confirmed historical (an even older release already exists) but no tag or marker commit
     * was recognized for it — callers must NOT fall through to branch-tip in that case, since
     * showing unrelated recent commits under an old version's name would be actively wrong.
     */
    private record VersionRangeResult(List<CommitResponse> commits, String fromCommitId, String toCommitId, boolean notYetShipped) {
        static VersionRangeResult ofNotYetShipped() { return new VersionRangeResult(List.of(), null, null, true); }
        static VersionRangeResult ofNotFound() { return new VersionRangeResult(List.of(), null, null, false); }
    }

    private static final Pattern ANY_RELEASE_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)*");

    /**
     * Scans the branch ONCE from its tip to find both the target version's boundary and the
     * previous release's boundary within that same scan. Boundaries are recognized by git tag
     * or release-marker commit message (e.g. "Release 1.4.76 [skip ci]").
     *
     * <p>Never re-fetches anchored at a different commit — on repos with non-linear history
     * (back-merges), a commit found in one scan may not appear at all in another anchored scan,
     * silently turning "stop at X" into a full-history traversal.
     */
    private VersionRangeResult findVersionRange(String project, String repo, String branch, String fromVersion, String toVersion) {
        Map<String, String> tagVersionsByCommitId = listTagVersionsByCommitId(project, repo);
        String toTagCommitId = findTagCommitId(tagVersionsByCommitId, toVersion);
        String fromTagCommitId = fromVersion != null && !fromVersion.isBlank()
                ? findTagCommitId(tagVersionsByCommitId, fromVersion) : null;
        int[] requestedVer = parseSemver(toVersion);

        // When the target version has a real git tag, its commit ID is already known at this
        // point (resolved above via the unbounded tag listing) — anchor the paged scan there
        // instead of always walking back from the branch tip. Without this, a release buried
        // past MAX_PAGES worth of newer commits would never be reached at all (see MAX_PAGES
        // javadoc): the tip-anchored scan would exhaust its cap before ever seeing it. Versions
        // that only have a release-marker commit message (no tag) have no known anchor, so they
        // still scan from the tip — the harder, non-linear-history-sensitive case documented above.
        String scanAnchor = toTagCommitId;

        List<CommitResponse> window = new ArrayList<>();
        int skip = 0;
        int targetIdx = -1;
        int boundaryIdx = -1;
        // A real tag can never be "not yet shipped", so an anchored scan skips that fast-exit
        // check entirely rather than run it against a window that no longer starts at the tip.
        boolean checkedLatestRelease = scanAnchor != null;
        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommitsByRange(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, null, scanAnchor, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> pageCommits = response.valueOrEmpty();
            if (pageCommits.isEmpty()) break;

            for (CommitResponse c : pageCommits) {
                window.add(c);
                int idx = window.size() - 1;
                boolean isTarget = (toTagCommitId != null && toTagCommitId.equals(c.commitId()))
                        || isReleaseMarkerFor(c.comment(), toVersion);
                if (targetIdx == -1 && isTarget) {
                    targetIdx = idx;
                }
                // Fast exit: check ONLY the first (most recent) release we ever see, since
                // scanning is newest-first. If even the latest known release is older than the
                // requested version, it hasn't shipped yet — no point scanning further. A newer
                // release existing just confirms the requested version is historical and further
                // back in the same scan, so this check must never fire again after the first hit.
                if (!checkedLatestRelease && requestedVer != null) {
                    int[] seenVer = tagVersionsByCommitId.containsKey(c.commitId())
                            ? parseSemver(tagVersionsByCommitId.get(c.commitId()))
                            : extractAnyReleaseVersion(c.comment());
                    if (seenVer != null) {
                        checkedLatestRelease = true;
                        if (compareSegments(seenVer, requestedVer) < 0) {
                            return VersionRangeResult.ofNotYetShipped();
                        }
                    }
                }
                // Boundary search continues in the same scan after target is found — a tag alone
                // doesn't prove "older release" since a version can appear on multiple commits.
                if (targetIdx != -1 && idx > targetIdx) {
                    boolean isBoundary = fromTagCommitId != null
                            ? fromTagCommitId.equals(c.commitId())
                            : (isOlderTaggedRelease(c.commitId(), tagVersionsByCommitId, toVersion)
                                    || isOlderReleaseMarker(c.comment(), toVersion));
                    if (isBoundary) {
                        boundaryIdx = idx;
                        break;
                    }
                }
            }
            if (boundaryIdx != -1) break;
            skip += pageCommits.size();
            if (pageCommits.size() < COMMIT_PAGE_SIZE) break;
        }
        if (targetIdx == -1) {
            LOG.warning("No tag or release-marker commit found for " + project + "/" + repo + " v" + toVersion);
            return VersionRangeResult.ofNotFound();
        }
        if (boundaryIdx == -1) {
            LOG.warning("No older boundary found for " + project + "/" + repo + " v" + toVersion
                    + " within " + window.size() + " commits scanned (capped at " + MAX_PAGES + " pages) — commit list may be incomplete");
            boundaryIdx = window.size();
        }

        List<CommitResponse> slice = new ArrayList<>(window.subList(targetIdx, boundaryIdx));
        String fromCommitId = boundaryIdx < window.size() ? window.get(boundaryIdx).commitId() : null;
        String toCommitId = window.get(targetIdx).commitId();
        return new VersionRangeResult(slice, fromCommitId, toCommitId, false);
    }

    /** True if {@code commitId} carries a recognized release tag for a version OTHER than {@code toVersion} —
     * some repos tag both a release's merge commit and its own "Release X [skip ci]" commit with the SAME
     * version, so a tag match alone doesn't prove this is an older, distinct release. */
    private static boolean isOlderTaggedRelease(String commitId, Map<String, String> tagVersionsByCommitId, String toVersion) {
        String tag = tagVersionsByCommitId.get(commitId);
        return tag != null && !tag.equals(toVersion);
    }

    /** Same "not the same release" guard as {@link #isOlderTaggedRelease}, for release-marker commit messages. */
    private static boolean isOlderReleaseMarker(String message, String toVersion) {
        return isAnyReleaseMarker(message) && !isReleaseMarkerFor(message, toVersion);
    }

    /** All tag refs that look like a version, keyed by the commit they point to. */
    private Map<String, String> listTagVersionsByCommitId(String project, String repo) {
        Map<String, String> result = new HashMap<>();
        try {
            for (GitRef ref : listTagRefs(project, repo)) {
                String tn = ref.tagName();
                if (tn == null) continue;
                String ver = stripVersionTagPrefix(tn);
                if (parseSemver(ver) != null) {
                    result.put(ref.commitId(), ver);
                }
            }
        } catch (Exception e) {
            LOG.warning("listRefs failed for " + project + "/" + repo + ": " + e);
        }
        return result;
    }

    private static String findTagCommitId(Map<String, String> tagVersionsByCommitId, String version) {
        for (Map.Entry<String, String> e : tagVersionsByCommitId.entrySet()) {
            if (e.getValue().equals(version)) return e.getKey();
        }
        return null;
    }

    /** Common phrasings automated version-bump commits use across this org's repos. Not every
     * repo (or every era of the same repo) says "release" — e.g. hubsabai-vscode briefly used
     * "chore: bump version to X [skip ci]" for versions 1.4.41-1.4.45 before reverting to
     * "Release X [skip ci]". This app is centralized across every project/repo, so the marker
     * check recognizes the other common wordings too rather than being tuned to one convention. */
    private static boolean looksLikeVersionMarker(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("release") || lower.contains("bump version") || lower.contains("version bump");
    }

    /** Extracts the version number from a release-marker message, or null if it doesn't look like one. */
    private static int[] extractAnyReleaseVersion(String message) {
        if (!looksLikeVersionMarker(message)) return null;
        Matcher m = ANY_RELEASE_VERSION.matcher(message);
        return m.find() ? parseSemver(m.group()) : null;
    }

    private static boolean isReleaseMarkerFor(String message, String version) {
        if (!looksLikeVersionMarker(message)) return false;
        return Pattern.compile("\\b" + Pattern.quote(version) + "\\b").matcher(message).find();
    }

    private static boolean isAnyReleaseMarker(String message) {
        if (!looksLikeVersionMarker(message)) return false;
        return ANY_RELEASE_VERSION.matcher(message).find();
    }

    private List<ChangeItem> fetchCommitsByRange(String project, String repo, String fromCommitId, String toCommitId, String branch) {
        List<CommitResponse> raw = fetchCommitsUpTo(project, repo, branch, toCommitId, fromCommitId);
        List<ChangeItem> items = new ArrayList<>(raw.size());
        for (CommitResponse c : raw) {
            items.add(toChangeItem(project, repo, c));
        }
        // Fetch file-level changes for each commit.  CDI / @RestClient proxies don't work in
        // ForkJoinPool (parallelStream) threads and may not propagate to ad-hoc virtual threads,
        // so this runs sequentially.  The outer service already parallelizes across repos.
        for (ChangeItem item : items) {
            if (item.getId() != null) {
                item.setFilePaths(fetchCommitFilePaths(project, repo, item.getId()));
            }
        }
        return items;
    }

    /** Hard cap on how far {@link #fetchCommitsUpTo} scans looking for a lower boundary that,
     * on a repo with non-linear branch history, may never actually appear in this traversal —
     * see that method's Javadoc. Without a cap that case silently returns the entire repo
     * history instead of failing loudly. */
    private static final int RANGE_SCAN_PAGES = 5;

    /**
     * Walks backward from {@code toCommitId} until {@code lowerBoundaryCommitId} (exclusive)
     * or {@link #RANGE_SCAN_PAGES} pages. Azure DevOps' combined fromCommitId+toCommitId range
     * query is unreliable on repos with non-linear history — slicing a single-bound fetch
     * client-side avoids that. The lower boundary may be absent from this traversal entirely
     * (non-linear topology), so the scan is capped rather than unbounded.
     */
    private List<CommitResponse> fetchCommitsUpTo(String project, String repo, String branch, String toCommitId, String lowerBoundaryCommitId) {
        List<CommitResponse> commits = new ArrayList<>();
        int skip = 0;
        for (int page = 0; page < RANGE_SCAN_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommitsByRange(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, null, toCommitId, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> pageCommits = response.valueOrEmpty();
            if (pageCommits.isEmpty()) break;
            for (CommitResponse c : pageCommits) {
                if (lowerBoundaryCommitId != null && lowerBoundaryCommitId.equals(c.commitId())) {
                    return commits;
                }
                commits.add(c);
            }
            skip += pageCommits.size();
            if (pageCommits.size() < COMMIT_PAGE_SIZE) break;
            if (page == RANGE_SCAN_PAGES - 1 && lowerBoundaryCommitId != null) {
                LOG.warning("fetchCommitsUpTo for " + project + "/" + repo + " never reached lower boundary "
                        + lowerBoundaryCommitId + " within " + RANGE_SCAN_PAGES + " pages — list may be incomplete");
            }
        }
        return commits;
    }

    /**
     * Fetches PRs whose creation date falls within the time span of the commit range.
     * Uses the commit dates of the boundary SHAs as an approximate filter.
     */
    private List<ChangeItem> fetchPullRequestsForRange(String project, String repo, String fromCommitId, String toCommitId, String branch) {
        // Get dates for the boundary commits to approximate the PR time window
        String since = null;
        if (fromCommitId != null) {
            try {
                CommitResponse c = getCommitCached(project, repo, fromCommitId);
                if (c != null && c.author() != null) since = c.author().date();
            } catch (Exception e) {
                LOG.warning("getCommit failed for " + fromCommitId + ": " + e);
            }
        }
        String until = null;
        if (toCommitId != null) {
            try {
                CommitResponse c = getCommitCached(project, repo, toCommitId);
                if (c != null && c.author() != null) until = c.author().date();
            } catch (Exception e) {
                LOG.warning("getCommit failed for " + toCommitId + ": " + e);
            }
        }
        return fetchPullRequests(project, repo, since, until, branch);
    }

    private static final List<String> CHANGELOG_FILENAMES = List.of("CHANGELOG.md", "changelog.md");

    /** Does this repo already have a committed changelog file at its root? Checked by filename, not content. */
    public boolean hasChangelogFile(String project, String repo) {
        return hasChangelogFile(project, repo, null);
    }

    /** Same as {@link #hasChangelogFile(String, String)}, scoped to a specific branch instead of the repo's default. */
    public boolean hasChangelogFile(String project, String repo, String branch) {
        return fetchChangelogContent(project, repo, branch) != null;
    }

    /** Same as {@link #hasChangelogFile(String, String)} but never throws — returns false and logs
     * on failure, for bulk scans across many repos where one repo's API error shouldn't sink the
     * whole list. */
    public boolean hasChangelogFileSafely(String project, String repo) {
        try {
            return hasChangelogFile(project, repo);
        } catch (Exception e) {
            LOG.warning("hasChangelogFile failed for " + project + "/" + repo + ": " + e);
            return false;
        }
    }

    /**
     * Parsed entry from a repo's existing CHANGELOG.md. Lightweight — we store the raw markdown
     * body so the frontend can display it without reconstructing from structured data.
     */
    public record ChangelogEntry(String version, String date, String body) {}

    /** The changelog file's own name (CHANGELOG.md vs changelog.md) plus its raw content. */
    public record ChangelogFile(String filename, String content) {}

    /** Fetches the repo's changelog file — name and raw content — or null when neither casing exists. */
    public ChangelogFile fetchChangelogFile(String project, String repo) {
        return fetchChangelogFile(project, repo, null);
    }

    /**
     * Same as {@link #fetchChangelogFile(String, String)} but reads the file as it exists on
     * {@code branch} rather than the repo's default branch — {@code null} falls back to the default.
     */
    private record ChangelogFileCacheEntry(ChangelogFile file, long fetchedAtMillis) {
        boolean expired() { return System.currentTimeMillis() - fetchedAtMillis > ENRICHMENT_CACHE_TTL.toMillis(); }
    }
    private final Map<String, ChangelogFileCacheEntry> changelogFileCache = new ConcurrentHashMap<>();

    /** Same as {@link #fetchChangelogFile(String, String, String)}, short-TTL cached — for
     * read-only display paths (the dashboard's history endpoint hits this on every page load,
     * previously live every time). Never use this for a push: it needs the file exactly as it is
     * right now to push against the correct base commit, not a stale cached read. */
    public ChangelogFile fetchChangelogFileCached(String project, String repo, String branch) {
        String key = project + "/" + repo + "/" + (branch != null ? branch : "");
        ChangelogFileCacheEntry cached = changelogFileCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.file();
        }
        ChangelogFile file = fetchChangelogFile(project, repo, branch);
        changelogFileCache.put(key, new ChangelogFileCacheEntry(file, System.currentTimeMillis()));
        return file;
    }

    public ChangelogFile fetchChangelogFile(String project, String repo, String branch) {
        for (String filename : CHANGELOG_FILENAMES) {
            try {
                Response response = client.getItem(
                        org, project, repo, "/" + filename,
                        branch, branch != null ? "branch" : null,
                        AzureDevOpsRestClient.API_VERSION, true);
                try {
                    if (response.getStatus() == 200) {
                        return new ChangelogFile(filename, response.readEntity(GitItemResponse.class).content());
                    }
                } finally {
                    response.close();
                }
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() != 404) {
                    throw e;
                }
            }
        }
        return null;
    }

    /** Fetches the raw CHANGELOG.md content from the repo root, or null when no file exists. */
    public String fetchChangelogContent(String project, String repo) {
        return fetchChangelogContent(project, repo, null);
    }

    /** Same as {@link #fetchChangelogContent(String, String)}, scoped to a specific branch. */
    public String fetchChangelogContent(String project, String repo, String branch) {
        ChangelogFile file = fetchChangelogFile(project, repo, branch);
        return file != null ? file.content() : null;
    }

    // Matches either the "Keep a Changelog" convention ("## [1.2.3] - date", any bracket content)
    // or this app's own generated format ("## v1.2.3 — date", no brackets, digit-led version) —
    // kept as two alternatives (not one loosened pattern) so a plain, non-version "## Word - text"
    // heading can't accidentally be mistaken for a bracketless version header.
    private static final Pattern CHANGELOG_VERSION_HEADER = Pattern.compile(
            "^## \\[([^\\]]+)\\]\\s*[-–—]\\s*(.+)$|^## v?([0-9][\\w.-]*)\\s*[-–—]\\s*(.+)$",
            Pattern.MULTILINE
    );

    /** Normalizes a date string to YYYY-MM-DD. Handles M/D/YYYY and YYYY-MM-DD formats. */
    static String normalizeDate(String date) {
        if (date == null) return null;
        String trimmed = date.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) return trimmed;
        java.util.regex.Matcher m = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matcher(trimmed);
        if (m.find()) {
            return String.format("%04d-%02d-%02d", Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return trimmed;
    }

    /**
     * Parses CHANGELOG.md into version entries by {@code ## [version] - date} headers. If no
     * headers match, the whole file is returned as a single unversioned entry (our flat-bullet
     * format doesn't use the "Keep a Changelog" convention).
     */
    public List<ChangelogEntry> parseChangelogEntries(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        Matcher matcher = CHANGELOG_VERSION_HEADER.matcher(markdown);
        List<ChangelogEntry> entries = new ArrayList<>();
        int prevStart = -1;
        String prevVersion = null;
        String prevDate = null;

        while (matcher.find()) {
            if (prevVersion != null) {
                String body = extractBody(markdown, prevStart, matcher.start());
                entries.add(new ChangelogEntry(prevVersion, prevDate, body));
            }
            prevVersion = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            prevDate = normalizeDate(matcher.group(1) != null ? matcher.group(2) : matcher.group(4));
            prevStart = matcher.start();
        }
        if (prevVersion != null) {
            String body = extractBody(markdown, prevStart, markdown.length());
            entries.add(new ChangelogEntry(prevVersion, prevDate, body));
        }
        if (entries.isEmpty()) {
            return List.of(new ChangelogEntry(null, null, markdown.strip()));
        }
        Collections.reverse(entries);
        return entries;
    }

    /** Grabs the raw text between two positions, skipping the header line itself. */
    private static String extractBody(String markdown, int headerStart, int nextHeaderStart) {
        int afterNewline = markdown.indexOf('\n', headerStart);
        if (afterNewline < 0 || afterNewline >= nextHeaderStart) {
            return "";
        }
        int bodyStart = afterNewline + 1;
        return bodyStart < nextHeaderStart ? markdown.substring(bodyStart, nextHeaderStart).strip() : "";
    }

    /**
     * Replaces one version's body text in CHANGELOG.md, leaving all other entries untouched.
     * Uses the same regex matcher as {@link #parseChangelogEntries} for consistency. Preserves
     * exact leading/trailing whitespace around the body (cf. {@link #extractBody} which strips).
     *
     * @return the full file with that entry replaced, or empty if no header matches {@code version}
     *         (callers should refuse, not guess).
     */
    public Optional<String> replaceChangelogEntryBody(String markdown, String version, String newBody) {
        if (markdown == null || version == null) {
            return Optional.empty();
        }
        Matcher matcher = CHANGELOG_VERSION_HEADER.matcher(markdown);
        int targetLineEnd = -1;
        int targetEntryEnd = -1;
        String prevVersion = null;
        int prevLineEnd = -1;

        while (matcher.find()) {
            if (version.equals(prevVersion)) {
                targetLineEnd = prevLineEnd;
                targetEntryEnd = matcher.start();
                break;
            }
            // Bracket ("## [1.2.3] - date") or bracketless ("## v1.2.3 — date") — whichever
            // alternative matched. Reading only group(1) here (as this used to) meant every
            // bracketless entry — this app's OWN generated format — silently matched nothing,
            // so replace/push always fell through as "no entry found" even when one existed.
            prevVersion = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            int afterNewline = markdown.indexOf('\n', matcher.start());
            prevLineEnd = afterNewline >= 0 ? afterNewline + 1 : markdown.length();
        }
        if (targetLineEnd < 0 && version.equals(prevVersion)) {
            // The matched header was the last one in the file — its entry runs to EOF.
            targetLineEnd = prevLineEnd;
            targetEntryEnd = markdown.length();
        }
        if (targetLineEnd < 0) {
            return Optional.empty();
        }

        String rawSpan = markdown.substring(targetLineEnd, targetEntryEnd);
        int contentStart = 0;
        while (contentStart < rawSpan.length() && Character.isWhitespace(rawSpan.charAt(contentStart))) {
            contentStart++;
        }
        int contentEnd = rawSpan.length();
        while (contentEnd > contentStart && Character.isWhitespace(rawSpan.charAt(contentEnd - 1))) {
            contentEnd--;
        }
        String leadingGap = rawSpan.substring(0, contentStart);
        String trailingGap = rawSpan.substring(contentEnd);

        String replacement = leadingGap + newBody.strip() + trailingGap;
        return Optional.of(markdown.substring(0, targetLineEnd) + replacement + markdown.substring(targetEntryEnd));
    }

    /** Prepends a brand-new {@code ## vX — date} section, in this app's own bracketless format,
     * ahead of whatever's already in the file — matching the newest-first convention every entry
     * here already follows. Caller ({@link #pushChangelogEdit}) validates the version and body
     * first; this just does the insertion. */
    private static String insertNewChangelogEntry(String markdown, String version, String body) {
        String header = "## v" + version + " — " + LocalDate.now();
        String entry = header + "\n\n" + body.strip() + "\n\n";
        return markdown == null || markdown.isBlank() ? entry : entry + markdown;
    }

    /** The commit SHA a branch currently points to, or null if the branch can't be found. */
    private String resolveBranchCommitSha(String project, String repo, String branch) {
        Response response = client.listRefs(org, project, repo, "heads/", true, AzureDevOpsRestClient.API_VERSION);
        try {
            AzureDevOpsListResponse<GitRef> body =
                    response.readEntity(new GenericType<AzureDevOpsListResponse<GitRef>>() {});
            String target = "refs/heads/" + branch;
            return body.valueOrEmpty().stream()
                    .filter(r -> target.equals(r.name()))
                    .findFirst()
                    .map(GitRef::commitId)
                    .orElse(null);
        } finally {
            response.close();
        }
    }

    // A brand-new entry's version must look like a real version (digit-led, word/dot/hyphen
    // chars only — the same shape the bracketless header itself requires) before it's allowed
    // to be written into a real CHANGELOG.md. Never applies to an edit of an existing entry —
    // that one's already proven real by having a matching header.
    private static final Pattern VALID_NEW_VERSION = Pattern.compile("^[0-9][\\w.-]*$");

    /**
     * Pushes a Developer changelog straight onto {@code branch} — a direct commit via the
     * service's own PAT (shows up in git history as a bot commit), never a branch + PR: replaces
     * the version's existing entry if one exists, creates a new entry at the top of the file
     * (newest-first, matching this file's own convention) if not, and creates {@code
     * CHANGELOG.md} itself from scratch if the branch has no changelog file at all yet (e.g. a
     * repo whose pipeline has never committed one — nothing in this app ever originates the file
     * on its own otherwise). Re-fetches the file fresh to reflect the repo's current state, not
     * whatever the caller's page loaded earlier.
     *
     * @return a link to the resulting commit. Failures — a new entry whose version doesn't look
     *         like a real version, empty text, the branch not existing, or the branch having
     *         moved since the file was fetched (Azure DevOps rejects a push whose base commit is
     *         stale) — propagate as unchecked exceptions.
     */
    public String pushChangelogEdit(String project, String repo, String branch, String version, String newBody) {
        ChangelogFile file = fetchChangelogFile(project, repo, branch);
        boolean fileExists = file != null;
        String filename = fileExists ? file.filename() : CHANGELOG_FILENAMES.get(0);
        String existingContent = fileExists ? file.content() : null;

        Optional<String> replaced = fileExists
                ? replaceChangelogEntryBody(existingContent, version, newBody)
                : Optional.empty();
        boolean isNewEntry = replaced.isEmpty();
        String updated;
        if (isNewEntry) {
            if (!VALID_NEW_VERSION.matcher(version).matches()) {
                throw new AiException("'" + version + "' doesn't look like a real version (expected something"
                        + " like 1.2.3) — refusing to create a new CHANGELOG.md entry for it.");
            }
            if (newBody == null || newBody.isBlank()) {
                throw new AiException("Nothing to push — the changelog text is empty.");
            }
            updated = insertNewChangelogEntry(existingContent, version, newBody);
        } else {
            updated = replaced.get();
        }

        String baseCommitSha = resolveBranchCommitSha(project, repo, branch);
        if (baseCommitSha == null) {
            throw new IllegalStateException("Branch '" + branch + "' not found on " + project + "/" + repo + ".");
        }

        String action = !fileExists ? "Create" : (isNewEntry ? "Add" : "Update");
        GitPushRequest pushRequest = fileExists
                ? GitPushRequest.editFile("refs/heads/" + branch, baseCommitSha, "/" + filename, updated,
                        action + " v" + version + " developer changelog (via dashboard)")
                : GitPushRequest.addFile("refs/heads/" + branch, baseCommitSha, "/" + filename, updated,
                        action + " v" + version + " developer changelog (via dashboard)");
        String commitId;
        try {
            Response pushResponse = client.push(org, project, repo, pushRequest, AzureDevOpsRestClient.API_VERSION);
            try {
                if (pushResponse.getStatus() >= 400) {
                    // Most common cause: someone else committed to this branch between this
                    // method's fetchChangelogFile call and now, so baseCommitSha is no longer the
                    // branch's tip — Azure DevOps refuses a push whose base is stale rather than
                    // silently rebasing. Kept for the (return-type-Response) case where the client
                    // doesn't throw on its own; see the catch below for when it does.
                    throw new IllegalStateException("Could not push to '" + branch + "' on " + project + "/" + repo
                            + " — it may have moved since this page loaded. Refresh and try again.");
                }
                GitPushResponse body = pushResponse.readEntity(GitPushResponse.class);
                commitId = body.commits() != null && !body.commits().isEmpty() ? body.commits().get(0).commitId() : null;
            } finally {
                pushResponse.close();
            }
        } catch (jakarta.ws.rs.WebApplicationException e) {
            // In practice the REST client throws here rather than returning the >=400 Response
            // above, which was swallowing Azure DevOps' actual reason behind a generic "request
            // failed" message — log it so a real cause (bad payload, policy, stale base) is
            // diagnosable instead of guessed at.
            String detail = null;
            if (e.getResponse() != null) {
                try {
                    detail = e.getResponse().readEntity(String.class);
                } catch (Exception ignored) {
                    // Body already consumed/unreadable — fall through with detail == null.
                }
            }
            LOG.warning("Push to '" + branch + "' on " + project + "/" + repo + " failed: " + e.getMessage()
                    + (detail != null && !detail.isBlank() ? " — response body: " + detail : ""));
            throw new IllegalStateException("Could not push to '" + branch + "' on " + project + "/" + repo
                    + (detail != null && !detail.isBlank() ? " — Azure DevOps said: " + detail
                            : " — it may have moved since this page loaded. Refresh and try again."));
        }

        return "https://dev.azure.com/" + org + "/" + project + "/_git/" + repo
                + (commitId != null ? "/commit/" + commitId : "");
    }

    /**
     * Enriched metadata for a version extracted from CHANGELOG.md. When a matching git tag exists,
     * {@code author} and {@code timestamp} reflect the tagged commit; otherwise they remain null/empty.
     */
    public record ChangelogEnrichment(String author, String timestamp) {}

    /**
     * Builds a version → enrichment map two ways: first by matching git tags ({@code v1.2.3},
     * {@code 1.2.3}, {@code release-1.2.3}) against each parsed version and fetching the tagged
     * commit's author/date; then, for whatever's left unmatched (most repos here don't tag every
     * release — they publish to Nexus/Azure Artifacts instead), by walking {@code filename}'s own
     * commit history and matching a commit to a version by date.
     */
    public Map<String, ChangelogEnrichment> enrichChangelogEntries(
            String project, String repo, String filename, List<ChangelogEntry> entries) {
        if (entries.isEmpty()) return Map.of();

        Map<String, ChangelogEnrichment> result = new HashMap<>();
        matchByTag(project, repo, entries, result);
        matchByFileHistory(project, repo, filename, entries, result);

        LOG.info("Enriched " + result.size() + "/" + entries.size() + " changelog entries for " + project + "/" + repo);
        return result;
    }

    private void matchByTag(String project, String repo, List<ChangelogEntry> entries, Map<String, ChangelogEnrichment> result) {
        List<GitRef> tags;
        try {
            tags = listTagRefs(project, repo);
        } catch (Exception e) {
            LOG.warning("listRefs failed for " + project + "/" + repo + ": " + e);
            return;
        }
        if (tags.isEmpty()) {
            return;
        }

        for (ChangelogEntry entry : entries) {
            if (entry.version() == null) continue;
            String ver = entry.version();

            GitRef match = null;
            for (GitRef ref : tags) {
                String tn = ref.tagName();
                if (tn == null) continue;
                if (tn.equals(ver) || tn.equals("v" + ver) || tn.equals("release-" + ver)) {
                    match = ref;
                    break;
                }
            }
            if (match == null) continue;

            try {
                CommitResponse commit = getCommitCached(project, repo, match.commitId());
                if (commit != null && commit.author() != null) {
                    result.put(ver, new ChangelogEnrichment(commit.author().name(), commit.author().date()));
                }
            } catch (Exception e) {
                LOG.warning("getCommit failed for tag " + match.tagName() + " (" + match.commitId() + "): " + e);
            }
        }
    }

    /** Fallback for versions no tag matched: correlate by date against commits that touched the changelog file. */
    private void matchByFileHistory(
            String project, String repo, String filename, List<ChangelogEntry> entries, Map<String, ChangelogEnrichment> result) {
        boolean anyUnmatched = entries.stream().anyMatch(e -> e.version() != null && !result.containsKey(e.version()));
        if (!anyUnmatched || filename == null) {
            return;
        }

        List<CommitResponse> commits;
        try {
            commits = fetchCommitsForPath(project, repo, "/" + filename);
        } catch (Exception e) {
            LOG.warning("fetchCommitsForPath failed for " + project + "/" + repo + "/" + filename + ": " + e);
            return;
        }
        if (commits.isEmpty()) return;

        for (ChangelogEntry entry : entries) {
            if (entry.version() == null || result.containsKey(entry.version()) || entry.date() == null) continue;
            String dateOnly = entry.date().strip();
            if (dateOnly.length() > 10) dateOnly = dateOnly.substring(0, 10);
            for (CommitResponse commit : commits) {
                if (commit.author() == null || commit.author().date() == null) continue;
                String commitDateOnly = commit.author().date().substring(0, Math.min(10, commit.author().date().length()));
                if (commitDateOnly.equals(dateOnly)) {
                    result.put(entry.version(), new ChangelogEnrichment(commit.author().name(), commit.author().date()));
                    break;
                }
            }
        }
    }

    /** All commits that touched a specific file path (e.g. {@code "/CHANGELOG.md"}), newest first. */
    public List<CommitResponse> fetchCommitsForPath(String project, String repo, String path) {
        String key = project + "/" + repo + path;
        FileHistoryCacheEntry cached = fileHistoryCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.commits();
        }
        List<CommitResponse> items = new ArrayList<>();
        int skip = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommits(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, null, null, null, path, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> commits = response.valueOrEmpty();
            if (commits.isEmpty()) break;
            items.addAll(commits);
            skip += commits.size();
            if (commits.size() < COMMIT_PAGE_SIZE) break;
        }
        fileHistoryCache.put(key, new FileHistoryCacheEntry(items, System.currentTimeMillis()));
        return items;
    }

    /** The repo's default branch as a short name (e.g. {@code "main"}), or null if the repo can't be found. */
    public String defaultBranch(String project, String repo) {
        return listRepositories(project).stream()
                .filter(r -> r.name().equals(repo))
                .findFirst()
                .map(RepositorySummary::defaultBranch)
                .map(ref -> ref != null && ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref)
                .orElse(null);
    }

    /** All branch short names for a repo (e.g. {@code "main"}, {@code "develop"}), or empty if none/on failure. */
    public List<String> listBranches(String project, String repo) {
        try {
            Response response = client.listRefs(org, project, repo, "heads/", true, AzureDevOpsRestClient.API_VERSION);
            try {
                AzureDevOpsListResponse<GitRef> body =
                        response.readEntity(new GenericType<AzureDevOpsListResponse<GitRef>>() {});
                return body.valueOrEmpty().stream()
                        .map(GitRef::name)
                        .filter(Objects::nonNull)
                        .map(name -> name.startsWith("refs/heads/") ? name.substring("refs/heads/".length()) : name)
                        .toList();
            } finally {
                response.close();
            }
        } catch (Exception e) {
            LOG.warning("listBranches failed for " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    /** Work items belong to the project, not a specific repo — {@code repo} stays unset (README §11.2 / TODO §3). */
    public List<ChangeItem> fetchProjectWorkItems(String project) {
        String stateList = Arrays.stream(doneStatesConfig.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> "'" + s.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
        String wiql = """
                SELECT [System.Id] FROM WorkItems
                WHERE [System.TeamProject] = @project
                AND [System.State] IN (%s)
                ORDER BY [System.ChangedDate] DESC""".formatted(stateList);

        WiqlResult result = client.queryWorkItems(org, project, new WiqlQuery(wiql), AzureDevOpsRestClient.API_VERSION);
        List<Integer> ids = result.workItemsOrEmpty().stream()
                .map(WiqlResult.WorkItemReference::id)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<ChangeItem> items = new ArrayList<>();
        for (List<Integer> batch : partition(ids, WORK_ITEM_BATCH_SIZE)) {
            String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
            AzureDevOpsListResponse<WorkItemResponse> batchResponse =
                    client.getWorkItemsBatch(org, project, idsParam, AzureDevOpsRestClient.API_VERSION);
            for (WorkItemResponse wi : batchResponse.valueOrEmpty()) {
                items.add(toChangeItem(project, wi));
            }
        }
        return items;
    }

    /** Walks every project and every repo in the org. Repos within a project, and projects within the org, fetch concurrently. */
    public OrgFetchResult fetchAll() {
        List<ProjectSummary> projects = listProjects();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ProjectFetchResult>> futures = projects.stream()
                    .map(project -> executor.submit(() -> fetchProject(project)))
                    .toList();

            List<ProjectFetchResult> results = new ArrayList<>();
            for (Future<ProjectFetchResult> future : futures) {
                results.add(future.get());
            }
            return new OrgFetchResult(org, results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching organization " + org, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to fetch organization " + org, e.getCause());
        }
    }

    private ProjectFetchResult fetchProject(ProjectSummary project) {
        List<RepositorySummary> repos;
        try {
            repos = listRepositories(project.name());
        } catch (Exception e) {
            return new ProjectFetchResult(project, List.of(errorItem(project.name(), null, e)), List.of());
        }

        List<ChangeItem> workItems;
        try {
            workItems = fetchProjectWorkItems(project.name());
        } catch (Exception e) {
            workItems = List.of(errorItem(project.name(), null, e));
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ReleaseData>> futures = repos.stream()
                    .map(repo -> executor.submit(() -> fetchRepoChangesSafely(project.name(), repo.name())))
                    .toList();

            List<ReleaseData> repoData = new ArrayList<>();
            for (Future<ReleaseData> future : futures) {
                repoData.add(future.get());
            }
            return new ProjectFetchResult(project, workItems, repoData);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching project " + project.name(), e);
        } catch (ExecutionException e) {
            // fetchRepoChangesSafely never throws, so this only fires on an unexpected executor failure.
            throw new IllegalStateException("Failed to fetch project " + project.name(), e.getCause());
        }
    }

    private ReleaseData fetchRepoChangesSafely(String project, String repo) {
        try {
            return fetchRepoChanges(project, repo);
        } catch (Exception e) {
            ReleaseData data = new ReleaseData();
            data.setRelease(buildMeta(project, repo, null));
            data.setItems(List.of(errorItem(project, repo, e)));
            return data;
        }
    }

    /** {@code since}/{@code until} are pushed down to Azure DevOps's own date filter, so out-of-range pages are never fetched. */
    private List<ChangeItem> fetchCommits(String project, String repo, String since, String until, String branch) {
        List<ChangeItem> items = new ArrayList<>();
        int skip = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommits(
                    org, project, repo, COMMIT_PAGE_SIZE, skip, since, until, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> commits = response.valueOrEmpty();
            if (commits.isEmpty()) {
                break;
            }
            for (CommitResponse c : commits) {
                items.add(toChangeItem(project, repo, c));
            }
            skip += commits.size();
            if (commits.size() < COMMIT_PAGE_SIZE) {
                break;
            }
        }
        return items;
    }

    /**
     * The most recently completed PRs on a branch, newest first — used by the history endpoint to
     * find merges nobody has generated a changelog for yet. Only the first page is fetched: a PR
     * old enough to have fallen off it is assumed to already have a changelog, so it's not worth
     * the extra Azure DevOps calls to keep paginating.
     */
    public List<PullRequestResponse> listRecentCompletedPullRequests(String project, String repo, String branch) {
        String targetRefName = branch != null ? "refs/heads/" + branch : null;
        AzureDevOpsListResponse<PullRequestResponse> response = client.listPullRequests(
                org, project, repo, "completed", targetRefName, PULL_REQUEST_PAGE_SIZE, 0, AzureDevOpsRestClient.API_VERSION);
        return response.valueOrEmpty();
    }

    /**
     * Azure DevOps's List Pull Requests API has no date filter, so {@code since} is applied client-side.
     * Pages come back newest-first (default order), so once a page's oldest PR is before {@code since}
     * we stop paginating — no need to walk the rest of the repo's PR history.
     */
    private List<ChangeItem> fetchPullRequests(String project, String repo, String since, String until, String branch) {
        List<ChangeItem> items = new ArrayList<>();
        int skip = 0;
        String targetRefName = branch != null ? "refs/heads/" + branch : null;

        for (int page = 0; page < MAX_PAGES; page++) {
            AzureDevOpsListResponse<PullRequestResponse> response =
                    client.listPullRequests(org, project, repo, "all", targetRefName, PULL_REQUEST_PAGE_SIZE, skip, AzureDevOpsRestClient.API_VERSION);
            List<PullRequestResponse> prs = response.valueOrEmpty();
            if (prs.isEmpty()) {
                break;
            }
            for (PullRequestResponse pr : prs) {
                if (isWithinRange(pr.creationDate(), since, until)) {
                    items.add(toChangeItem(project, repo, pr));
                }
            }
            skip += prs.size();
            boolean pastSince = since != null && prs.stream()
                    .anyMatch(pr -> pr.creationDate() != null && pr.creationDate().compareTo(since) < 0);
            if (prs.size() < PULL_REQUEST_PAGE_SIZE || pastSince) {
                break;
            }
        }
        return items;
    }

    private static boolean isWithinRange(String timestamp, String since, String until) {
        if (timestamp == null) {
            return true;
        }
        if (since != null && timestamp.compareTo(since) < 0) {
            return false;
        }
        return until == null || timestamp.compareTo(until) <= 0;
    }

    private ChangeItem toChangeItem(String project, String repo, CommitResponse c) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setId(c.commitId());
        item.setTitle(c.comment());
        item.setDescription(c.comment());
        item.setCategory(ChangeCategoryClassifier.fromText(c.comment()));
        item.setAuthor(c.author() != null ? c.author().name() : null);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(c.author() != null ? c.author().date() : null);
        item.setLinks(c.url() != null ? List.of(c.url()) : List.of());
        item.setFilePaths(List.of());
        return item;
    }

    private ChangeItem toChangeItem(String project, String repo, PullRequestResponse pr) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.PULL_REQUEST);
        item.setId(String.valueOf(pr.pullRequestId()));
        item.setTitle(pr.title());
        item.setDescription(pr.description());
        item.setCategory(ChangeCategoryClassifier.fromText(pr.title()));
        item.setAuthor(pr.createdBy() != null ? pr.createdBy().displayName() : null);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(pr.creationDate());
        item.setLinks(List.of("https://dev.azure.com/%s/%s/_git/%s/pullrequest/%d".formatted(org, project, repo, pr.pullRequestId())));
        item.setFilePaths(List.of());
        return item;
    }

    private ChangeItem toChangeItem(String project, WorkItemResponse wi) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.WORK_ITEM);
        item.setId(String.valueOf(wi.getId()));
        item.setTitle(WorkItemFields.string(wi, "System.Title"));
        item.setCategory(ChangeCategoryClassifier.fromWorkItemType(WorkItemFields.string(wi, "System.WorkItemType")));
        item.setDescription(WorkItemFields.string(wi, "System.Description"));
        item.setAuthor(WorkItemFields.string(wi, "System.AssignedTo"));
        item.setProject(project);
        // Same manual construction as the PR link below, not WorkItemFields.htmlUrl(wi) — the
        // Work Items batch call never requests $expand=links, so wi.getLinks() is always null
        // and the dashboard's work-item cards showed no navigable link at all.
        item.setLinks(List.of("https://dev.azure.com/%s/%s/_workitems/edit/%s".formatted(org, project, wi.getId())));
        item.setFilePaths(List.of());
        return item;
    }

    private ChangeItem errorItem(String project, String repo, Exception e) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setTitle("Fetch failed: " + e.getMessage());
        item.setCategory("chore");
        item.setProject(project);
        item.setRepo(repo);
        item.setLinks(List.of());
        item.setFilePaths(List.of());
        return item;
    }

    // Bounded LRU, same reasoning as commitCache: a commit's changed file paths never change once
    // it exists. Without this, walking a repo's whole history (fetchCommitsByRange, unbounded when
    // no toVersion is given) paid this same per-commit network round trip again on every single
    // page load — confirmed taking 48s on a real repo with a lot of history.
    private static final int FILE_PATHS_CACHE_MAX_ENTRIES = 5000;
    private final Map<String, List<String>> commitFilePathsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > FILE_PATHS_CACHE_MAX_ENTRIES;
                }
            });

    /** Fetches the list of file paths changed in one commit. Returns empty on error. */
    private List<String> fetchCommitFilePaths(String project, String repo, String commitId) {
        String key = project + "/" + repo + "/" + commitId;
        List<String> cached = commitFilePathsCache.get(key);
        if (cached != null) return cached;
        try {
            CommitChangesResponse resp = client.getCommitChanges(org, project, repo, commitId, AzureDevOpsRestClient.API_VERSION);
            List<String> paths = resp == null || resp.changes() == null
                    ? List.of()
                    : resp.changes().stream()
                            .map(c -> c.item() != null ? c.item().path() : null)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
            commitFilePathsCache.put(key, paths);
            return paths;
        } catch (Exception e) {
            LOG.warning("Failed to fetch changes for commit " + commitId + " in " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
