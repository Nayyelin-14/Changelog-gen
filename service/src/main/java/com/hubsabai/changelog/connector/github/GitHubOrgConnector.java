package com.hubsabai.changelog.connector.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.connector.github.ChangelogMarkdown.ChangelogEntry;
import com.hubsabai.changelog.connector.github.ChangelogMarkdown.ChangelogFile;
import com.hubsabai.changelog.connector.github.dto.GitHubBlob;
import com.hubsabai.changelog.connector.github.dto.GitHubBranch;
import com.hubsabai.changelog.connector.github.dto.GitHubCommit;
import com.hubsabai.changelog.connector.github.dto.GitHubCommit.GitHubFile;
import com.hubsabai.changelog.connector.github.dto.GitHubCreatedPullRequest;
import com.hubsabai.changelog.connector.github.dto.GitHubCreatePullRequest;
import com.hubsabai.changelog.connector.github.dto.GitHubCreateRef;
import com.hubsabai.changelog.connector.github.dto.GitHubFileContent;
import com.hubsabai.changelog.connector.github.dto.GitHubOrgUser;
import com.hubsabai.changelog.connector.github.dto.GitHubPullRequest;
import com.hubsabai.changelog.connector.github.dto.GitHubRepository;
import com.hubsabai.changelog.connector.github.dto.GitHubTag;
import com.hubsabai.changelog.connector.github.dto.GitHubWorkflowRun;
import com.hubsabai.changelog.connector.github.dto.GitHubWorkflowRunsResponse;
import com.hubsabai.changelog.connector.azuredevops.ChangeCategoryClassifier;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.PipelineRunSummary;
import com.hubsabai.changelog.core.model.RepositorySummary;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.generation.RunChangeContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Org-wide discovery for the GitHub provider. Unlike {@code AzureDevOpsOrgConnector}, GitHub has
 * no "project" tier — the configured owner (an org OR a personal account) is the top container,
 * and repos live directly under it. Serves the dashboard mode for the GitHub provider, mirroring
 * the method surface the dashboard resource needs, but against the GitHub REST API.
 */
@ApplicationScoped
public class GitHubOrgConnector {

    private static final Logger LOG = Logger.getLogger(GitHubOrgConnector.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    @RestClient
    GitHubOrgRestClient client;

    @Inject
    @ConfigProperty(name = "github.owner", defaultValue = "CHANGE_ME")
    String owner;

    @Inject
    @ConfigProperty(name = "github.token", defaultValue = "CHANGE_ME")
    String token;

    @Inject
    jakarta.persistence.EntityManager entityManager;

    // The dashboard's dev view expects a list of "projects" — for GitHub that's always exactly
    // the one configured owner. We return it as a single-entry list so the UI flow is identical.
    public com.hubsabai.changelog.core.model.ProjectSummary listOwnerAsProject() {
        GitHubOrgUser u = resolveOwner();
        return new com.hubsabai.changelog.core.model.ProjectSummary(
                u != null && u.login() != null ? u.login() : owner,
                u != null && u.name() != null ? u.name() : owner,
                "GitHub account/org: " + owner);
    }

    private GitHubOrgUser resolveOwner() {
        return resolveOwnerCached().user();
    }

    private record OwnerCacheEntry(GitHubOrgUser user, long validUntil) { }

    private final Map<String, OwnerCacheEntry> ownerCache = new ConcurrentHashMap<>();

    private OwnerCacheEntry resolveOwnerCached() {
        OwnerCacheEntry cached = ownerCache.get(owner);
        if (cached != null && cached.validUntil() > System.currentTimeMillis()) {
            return cached;
        }
        GitHubOrgUser resolved = resolveOwnerUncached();
        OwnerCacheEntry entry = new OwnerCacheEntry(resolved, System.currentTimeMillis() + REPOS_TTL_MS);
        ownerCache.put(owner, entry);
        return entry;
    }

    private GitHubOrgUser resolveOwnerUncached() {
        try {
            return client.getOrg(owner);
        } catch (Exception e) {
            try {
                return client.getUser(owner);
            } catch (Exception e2) {
                return new GitHubOrgUser(owner, owner, "https://github.com/" + owner);
            }
        }
    }

    /** GitHub has no per-provider "projects" list beyond the configured owner. */
    public List<com.hubsabai.changelog.core.model.ProjectSummary> listProjects() {
        return List.of(listOwnerAsProject());
    }

    /** All repos directly under the configured owner (org or user), plus any the token can also
     * reach (collaborations/org memberships). Cached briefly — see {@link #reposCache}. */
    public List<RepositorySummary> listRepositories(String project) {
        String effectiveOwner = project != null && !project.isBlank() ? project : owner;
        ReposCacheEntry cached = reposCache.get(effectiveOwner);
        if (cached != null && cached.validUntil() > System.currentTimeMillis()) {
            return cached.repos();
        }
        try {
            return listRepositoriesUncached(effectiveOwner);
        } catch (Exception e) {
            // Keep serving the last-known list if the provider hiccups, rather than failing the
            // whole dashboard over one bad fetch.
            if (cached != null) return cached.repos();
            throw e;
        }
    }

    private List<RepositorySummary> listRepositoriesUncached(String effectiveOwner) {
        List<GitHubRepository> repos = fetch(() -> parseList(client.listAuthenticatedRepositories("owner", 100, 1), GitHubRepository.class));

        // /user/repos returns every repo the token can reach (owner + collaborations + org memberships),
        // so narrow it down to repos actually owned by the configured owner. For a personal account the
        // owner is the token itself; for an org, keep output consistent with the org's repos.
        String tokenUser = resolveOwner().login();
        if (repos.isEmpty() || (tokenUser != null && !tokenUser.equalsIgnoreCase(effectiveOwner))) {
            List<GitHubRepository> orgRepos = new ArrayList<>();
            List<GitHubRepository> userRepos = new ArrayList<>();
            try {
                orgRepos = fetch(() -> parseList(client.listOrgRepositories(effectiveOwner, 100, 1), GitHubRepository.class));
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    LOG.warning("Org not found or not accessible: " + effectiveOwner);
                } else if (e.getResponse().getStatus() == 403) {
                    LOG.warning("Access denied to org: " + effectiveOwner + " - check token permissions");
                } else {
                    throw e;
                }
            }
            try {
                userRepos = fetch(() -> parseList(client.listUserRepositories(effectiveOwner, 100, 1), GitHubRepository.class));
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    LOG.warning("User not found: " + effectiveOwner);
                } else if (e.getResponse().getStatus() == 403) {
                    LOG.warning("Access denied to user repos: " + effectiveOwner + " - check token permissions");
                } else {
                    throw e;
                }
            }
            repos = new ArrayList<>();
            repos.addAll(orgRepos);
            repos.addAll(userRepos);
        } else {
            repos = repos.stream().filter(r -> effectiveOwner.equals(r.ownerLogin())).toList();
        }
        // Deduplicate by repo name (same repo can appear in both org and user lists)
        Map<String, GitHubRepository> unique = new LinkedHashMap<>();
        for (GitHubRepository r : repos) {
            unique.putIfAbsent(r.name(), r);
        }
        List<RepositorySummary> result = unique.values().stream().map(r -> new RepositorySummary(
                String.valueOf(r.id()), r.name(), effectiveOwner,
                r.defaultBranch() != null ? r.defaultBranch() : "main",
                r.isPrivate() ? "private" : "public")).toList();
        reposCache.put(effectiveOwner, new ReposCacheEntry(result, System.currentTimeMillis() + REPOS_TTL_MS));
        return result;
    }

    /** Recent GitHub Actions workflow runs for a repo, mapped to the shared {@link PipelineRunSummary}
     * so the same "Pipeline runs" UI component works for both Azure and GitHub. */
    public List<PipelineRunSummary> listWorkflowRuns(String project, String repo, int top) {
        String effectiveOwner = project != null && !project.isBlank() ? project : owner;
        List<GitHubWorkflowRun> allRuns = new ArrayList<>();
        int page = 1;
        int perPage = Math.min(100, top);

        try {
            while (allRuns.size() < top) {
                try (Response response = client.listWorkflowRuns(effectiveOwner, repo, perPage, page)) {
                    if (response.getStatus() == 404) {
                        LOG.warning("Repo not found or not accessible: " + effectiveOwner + "/" + repo);
                        return List.of();
                    }
                    if (response.getStatus() == 403) {
                        LOG.warning("Access denied to repo: " + effectiveOwner + "/" + repo + " - check token permissions");
                        return List.of();
                    }
                    if (response.getStatus() < 200 || response.getStatus() >= 300) {
                        throw new IllegalStateException("GitHub call failed: HTTP " + response.getStatus());
                    }
                    String json = response.readEntity(String.class);
                    GitHubWorkflowRunsResponse wrapper = MAPPER.readValue(json, GitHubWorkflowRunsResponse.class);
                    List<GitHubWorkflowRun> runs = wrapper != null && wrapper.workflowRuns() != null
                            ? wrapper.workflowRuns() : List.of();

                    if (runs.isEmpty()) {
                        break;
                    }

                    allRuns.addAll(runs);
                    if (runs.size() < perPage || allRuns.size() >= top) {
                        break;
                    }
                    page++;
                }
            }
        } catch (Exception e) {
            LOG.warning("GitHub workflow runs fetch failed for " + effectiveOwner + "/" + repo + ": " + e);
            return List.of();
        }

        // Fetch application versions from DB for all buildIds in one query
        Map<Long, String> buildIdToVersion = new HashMap<>();
        if (!allRuns.isEmpty()) {
            List<Long> buildIds = allRuns.stream().map(GitHubWorkflowRun::id).toList();
            buildIdToVersion.putAll(fetchVersionsByBuildIds("github", effectiveOwner, repo, buildIds));
        }

        return allRuns.stream().limit(top).map(run -> {
            String commitTitle = run.headCommit() != null && run.headCommit().message() != null
                    ? run.headCommit().message().split("\n")[0]
                    : null;
            String buildNumber = String.valueOf(run.runNumber());
            String sourceBranch = run.headBranch();
            String sourceVersion = run.headSha() != null ? run.headSha().substring(0, 7) : null;
            String pipelineName = run.workflowName();
            String status = run.status();
            String result = run.conclusion();
            String finishTime = run.completedAt() != null ? run.completedAt() : run.updatedAt();
            if (commitTitle != null && commitTitle.length() > 120) {
                commitTitle = commitTitle.substring(0, 117) + "...";
            }
            Integer prNumber = run.pullRequests() != null && !run.pullRequests().isEmpty()
                    ? run.pullRequests().get(0).number()
                    : null;
            long buildId = run.id();

            return new PipelineRunSummary(
                    buildId,
                    buildNumber,
                    buildNumber, // GitHub's run number IS the buildNumber in the pipeline summary
                    status,
                    result,
                    finishTime,
                    sourceBranch,
                    run.headSha(),
                    run.workflowName(),
                    prNumber,
                    commitTitle);
        }).toList();
    }

    /** Looks up stored application versions for the given buildIds. The DB column is an integer
     * ({@code ChangelogVersion.buildId}), so GitHub run IDs that overflow Integer.MAX_VALUE are
     * skipped up front — they can never match a stored value and would only throw a coercion
     * error on bind. */
    private Map<Long, String> fetchVersionsByBuildIds(String provider, String project, String repo, List<Long> buildIds) {
        List<Long> inRange = buildIds.stream()
                .filter(id -> id >= Integer.MIN_VALUE && id <= Integer.MAX_VALUE)
                .toList();
        if (inRange.isEmpty()) return Map.of();
        try {
            String named = IntStream.range(0, inRange.size())
                    .mapToObj(i -> "?" + (i + 3))
                    .collect(Collectors.joining(","));
            String query = "SELECT cv.buildId, cv.version FROM ChangelogVersion cv WHERE cv.project = ?1 AND cv.repo = ?2 AND cv.buildId IN (" + named + ")";
            jakarta.persistence.Query q = entityManager.createQuery(query);
            q.setParameter(1, project);
            q.setParameter(2, repo);
            int paramIndex = 3;
            for (Long id : inRange) {
                q.setParameter(paramIndex++, id.intValue());
            }
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            Map<Long, String> map = new HashMap<>();
            for (Object[] row : rows) {
                if (row[0] != null && row[1] != null) {
                    map.put(((Number) row[0]).longValue(), (String) row[1]);
                }
            }
            return map;
        } catch (Exception e) {
            LOG.warning("Failed to fetch versions for buildIds: " + e);
            return Map.of();
        }
    }

    private <T> List<T> parseList(Response response, Class<T> type) {
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException("GitHub call failed: HTTP " + response.getStatus());
        }
        try (response) {
            return MAPPER.readValue(
                    response.readEntity(String.class),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("GitHub response parse failed: " + e.getMessage(), e);
        }
    }

    private <T> List<T> fetch(java.util.function.Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            LOG.warning("GitHub fetch failed: " + e);
            return List.of();
        }
    }

    /** The default branch short name for a repo ({@code "main"}, {@code "master"}, ...). */
    public String defaultBranch(String project, String repo) {
        return listRepositories(project).stream()
                .filter(r -> r.name().equals(repo))
                .map(RepositorySummary::defaultBranch)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("main");
    }

    /** All branch short names for a repo, or empty on failure. */
    public List<String> listBranches(String project, String repo) {
        try {
            List<GitHubBranch> branches = parseList(client.listBranches(owner, repo, 100), GitHubBranch.class);
            return branches.stream().map(GitHubBranch::name).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            LOG.warning("listBranches failed for " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    // ---- changelog file ----

    /** Does this repo already have a committed changelog file at its root? */
    public boolean hasChangelogFile(String project, String repo) {
        return hasChangelogFile(project, repo, null);
    }

    public boolean hasChangelogFile(String project, String repo, String branch) {
        return fetchChangelogFile(project, repo, branch) != null;
    }

    public boolean hasChangelogFileSafely(String project, String repo) {
        try {
            return hasChangelogFile(project, repo);
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetches the changelog file — name + raw content — or null when neither casing exists. */
    public ChangelogFile fetchChangelogFile(String project, String repo, String branch) {
        return fetchChangelogFile(project, repo, branch, false);
    }

    private ChangelogFile fetchChangelogFile(String project, String repo, String branch, boolean cached) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);
        String key = project + "/" + repo + "/" + resolvedBranch + "/" + cached;
        if (cached) {
            ChangelogFile hit = changelogFileCache.get(key);
            if (hit != null) return hit;
        }
        for (String filename : ChangelogMarkdown.CHANGELOG_FILENAMES) {
            try {
                Response response = client.getFile(project, repo, filename, resolvedBranch);
                if (response.getStatus() == 200) {
                    GitHubFileContent body = response.readEntity(GitHubFileContent.class);
                    String content = decodeContent(body);
                    ChangelogFile file = new ChangelogFile(filename, content);
                    if (cached) changelogFileCache.put(key, file);
                    return file;
                }
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() != 404) throw e;
            }
        }
        return null;
    }

    public ChangelogFile fetchChangelogFileCached(String project, String repo, String branch) {
        return fetchChangelogFile(project, repo, branch, true);
    }

    private final Map<String, ChangelogFile> changelogFileCache = new ConcurrentHashMap<>();

    /** TTL cache for the org-wide repo list. {@code listRepositories} fans out to 2-3 GitHub API
     * calls (authenticated repos + owner resolution with a 404 round-trip when the owner is a
     * personal account), and it's re-invoked by every {@code defaultBranch} lookup on run-fetch
     * paths — caching it cuts well over a second off each run-load. Repos rarely change; 60s keeps
     * new repos appearing promptly while making the hot loop cheap. */
    private static final long REPOS_TTL_MS = 60_000;

    private record ReposCacheEntry(List<RepositorySummary> repos, long validUntil) { }

    private final Map<String, ReposCacheEntry> reposCache = new ConcurrentHashMap<>();

    /** TTL cache for a repo+branch's closed-PR list. Only the merge-commit mapping is used from it,
     * and merged PRs don't change after merge — a short TTL just keeps new merges appearing soon.*/
    private record PrsCacheEntry(List<GitHubPullRequest> prs, long validUntil) { }

    private final Map<String, PrsCacheEntry> prsCache = new ConcurrentHashMap<>();

    private List<GitHubPullRequest> fetchClosedPrsCached(String project, String repo, String branch) {
        String key = project + "/" + repo + "/" + (branch != null ? branch : "");
        PrsCacheEntry cached = prsCache.get(key);
        if (cached != null && cached.validUntil() > System.currentTimeMillis()) {
            return cached.prs();
        }
        List<GitHubPullRequest> prs = fetch(() -> parseList(client.listPullRequests(project, repo, "closed", branch, 100, 1), GitHubPullRequest.class));
        prsCache.put(key, new PrsCacheEntry(prs, System.currentTimeMillis() + REPOS_TTL_MS));
        return prs;
    }

    /** Clear the provider-side caches. Test hook — {@code @QuarkusTest} shares one connector
     * instance across methods, so each test must start from an empty cache rather than leak
     * state (e.g. a resolved-owner name) into the next one. */
    void clearCaches() {
        reposCache.clear();
        ownerCache.clear();
        prsCache.clear();
        commitFilePathsCache.clear();
        changelogFileCache.clear();
    }

    private static String decodeContent(GitHubFileContent body) {
        if (body.content() == null) return null;
        if ("base64".equalsIgnoreCase(body.encoding())) {
            return new String(java.util.Base64.getMimeDecoder().decode(body.content().replaceAll("\\s", "")), java.nio.charset.StandardCharsets.UTF_8);
        }
        return body.content();
    }

    public List<ChangelogEntry> parseChangelogEntries(String content) {
        return ChangelogMarkdown.parseChangelogEntries(content);
    }

    // ---- repo changes (commits + PRs) ----

    /** Latest tag → default-branch-tip release data, or whole repo if no tag exists. */
    public ReleaseData fetchRepoChanges(String project, String repo) {
        return fetchRepoChanges(project, repo, null, null, null);
    }

    /**
     * Version-based repo changes. {@code toVersion} is the version being released now; when it has
     * a git tag, the range is between the previous tag and it. When the target hasn't shipped yet
     * (no tag), the range runs from the last known release to the branch tip. GitHub's tag list and
     * compare API replace Azure's commit-scan heuristics.
     */
    public ReleaseData fetchRepoChanges(String project, String repo, String fromVersion, String toVersion, String branch) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);

        if (toVersion == null || toVersion.isBlank()) {
            String head = resolveBranchHead(project, repo, resolvedBranch);
            String prev = previousVersionTag(project, repo, null);
            List<ChangeItem> items = new ArrayList<>();
            if (head != null) {
                items.addAll(fetchCommitsUpTo(project, repo, resolvedBranch, head, prev));
            }
            return buildReleaseData(project, repo, resolvedBranch, items);
        }

        List<GitHubTag> tags = listTagRefs(project, repo);
        String toTagSha = findTagSha(tags, toVersion);
        VersionTag prev = findPreviousTag(tags, toVersion);

        String fromSha = prev != null ? prev.commitId() : (fromVersion != null ? findTagSha(tags, fromVersion) : null);

        if (toTagSha != null) {
            // Historical, tagged release: compare(prev..toTag)
            List<ChangeItem> items = new ArrayList<>();
            if (fromSha != null || prev != null) {
                String base = prev != null ? prev.commitId() : fromSha;
                items.addAll(fetchCommitsInCompare(project, repo, base, toTagSha));
            } else {
                items.addAll(fetchCommitsRevisionRange(project, repo, toTagSha));
            }
            return buildReleaseData(project, repo, resolvedBranch, items);
        }

        // Not yet shipped (no tag for toVersion) — walk from previous release to branch tip.
        String head = resolveBranchHead(project, repo, resolvedBranch);
        List<ChangeItem> items = new ArrayList<>();
        if (head != null) {
            items.addAll(fetchCommitsUpTo(project, repo, resolvedBranch, head,
                    fromSha != null ? fromSha : (prev != null ? prev.commitId() : null)));
        }
        return buildReleaseData(project, repo, resolvedBranch, items);
    }

    private String previousVersionTag(String project, String repo, String currentVersion) {
        VersionTag prev = findPreviousTag(listTagRefs(project, repo), currentVersion);
        return prev != null ? prev.commitId() : null;
    }

    private ReleaseData buildReleaseData(String project, String repo, String branch, List<ChangeItem> items) {
        // Pull requests are represented alongside raw commits so the changelog shows them. A merged
        // PR's merge commit appears in the raw commit list; we swap that commit for the PR itself
        // (richer title/body/author/link) the way Azure does, and leave non-merge commits as-is.
        List<ChangeItem> withPrs = mergePrsInto(project, repo, branch, items);
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg("github");
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(java.time.LocalDate.now().toString());

        ReleaseData data = new ReleaseData();
        data.setRelease(meta);
        data.setItems(withPrs);
        return data;
    }

    /** Replaces merge commits with their merged PRs (by {@code merge_commit_sha} match). If the PR
     * list can't be fetched, the raw commits pass through untouched. */
    private List<ChangeItem> mergePrsInto(String project, String repo, String branch, List<ChangeItem> items) {
        if (items == null || items.isEmpty()) return items;
        Map<String, ChangeItem> bySha = new HashMap<>();
        for (ChangeItem item : items) {
            if (ChangeItem.ItemType.COMMIT.equals(item.getType()) && item.getId() != null) {
                bySha.put(item.getId(), item);
            }
        }
        if (bySha.isEmpty()) return items;

        List<GitHubPullRequest> prs = fetchClosedPrsCached(project, repo, branch);
        if (prs.isEmpty()) return items;

        Map<String, GitHubPullRequest> byMergeSha = new HashMap<>();
        for (GitHubPullRequest pr : prs) {
            // GitHub returns "closed" for merged PRs; merged_at is set when merged.
            if (pr.mergeCommitSha() != null && !pr.mergeCommitSha().isBlank() && pr.mergedAt() != null) {
                byMergeSha.put(pr.mergeCommitSha(), pr);
            }
        }

        List<ChangeItem> result = new ArrayList<>(items.size());
        for (ChangeItem item : items) {
            GitHubPullRequest pr = item.getId() != null ? byMergeSha.get(item.getId()) : null;
            if (pr != null) {
                result.add(toChangeItem(project, repo, pr));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private List<GitHubTag> listTagRefs(String project, String repo) {
        return fetch(() -> parseList(client.listTags(project, repo, 100, 1), GitHubTag.class));
    }

    /** Fetches ALL tags for a repo with pagination (GitHub returns max 100 per page). */
    public List<GitHubTag> listAllTags(String project, String repo) {
        List<GitHubTag> allTags = new ArrayList<>();
        try {
            int page = 1;
            while (true) {
                int currentPage = page; // effectively final for lambda
                List<GitHubTag> pageTags = fetch(() -> parseList(client.listTags(project, repo, 100, currentPage), GitHubTag.class));
                if (pageTags.isEmpty()) {
                    break;
                }
                allTags.addAll(pageTags);
                if (pageTags.size() < 100) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            LOG.warning("GitHub listAllTags failed for " + project + "/" + repo + ": " + e);
        }
        return allTags;
    }

    private String findTagSha(List<GitHubTag> tags, String version) {
        if (version == null) return null;
        for (GitHubTag t : tags) {
            if (t.name() != null && (t.name().equals(version) || t.name().equals("v" + version)
                    || t.name().equals("release-" + version) || t.name().equals("released/v" + version))) {
                return t.commit() != null ? t.commit().sha() : null;
            }
        }
        return null;
    }

    public static String stripVersionTagPrefix(String tagName) {
        if (tagName.startsWith("released/v")) return tagName.substring("released/v".length());
        if (tagName.startsWith("release-")) return tagName.substring("release-".length());
        if (tagName.startsWith("v")) return tagName.substring(1);
        return tagName;
    }

    public static int[] parseSemver(String version) {
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

    private VersionTag findPreviousTag(List<GitHubTag> tags, String currentVersion) {
        int[] current = parseSemver(currentVersion);
        VersionTag best = null;
        int[] bestSegments = null;
        for (GitHubTag t : tags) {
            if (t.name() == null) continue;
            String ver = stripVersionTagPrefix(t.name());
            int[] segments = parseSemver(ver);
            if (segments == null) continue;
            if (current != null && compareSegments(segments, current) >= 0) continue;
            if (best == null || compareSegments(segments, bestSegments) > 0) {
                best = new VersionTag(ver, t.commit() != null ? t.commit().sha() : null);
                bestSegments = segments;
            }
        }
        return best;
    }

    public String resolveBranchHead(String project, String repo, String branch) {
        try {
            Response resp = client.listCommits(project, repo, branch, null, null, 1, 1);
            List<GitHubCommit> commits = parseList(resp, GitHubCommit.class);
            return commits.isEmpty() ? null : commits.get(0).sha();
        } catch (Exception e) {
            LOG.warning("resolveBranchHead failed for " + project + "/" + repo + ": " + e);
            return null;
        }
    }

    /** All changes from {@code lowerBoundary} (exclusive) to {@code toCommitId} on a branch; when
     * there's no lower boundary (no prior tag/release), walk the branch's commits newest-first. */
    private List<ChangeItem> fetchCommitsUpTo(String project, String repo, String branch, String toCommitId, String lowerBoundary) {
        if (lowerBoundary != null) {
            return fetchCommitsInCompare(project, repo, lowerBoundary, toCommitId);
        }
        return fetchCommitsRevisionRange(project, repo, toCommitId);
    }

    /** Commits strictly between {@code base} and {@code head} via GitHub's compare API. */
    private List<ChangeItem> fetchCommitsInCompare(String project, String repo, String base, String head) {
        if (base == null || head == null) return List.of();
        try {
            Response resp = client.compare(project, repo, base, head);
            if (resp.getStatus() < 200 || resp.getStatus() >= 300) return List.of();
            JsonNode node;
            try (resp) {
                node = MAPPER.readTree(resp.readEntity(String.class));
            }
            List<ChangeItem> items = new ArrayList<>();
            if (node.has("commits")) {
                for (JsonNode c : node.get("commits")) {
                    items.add(toChangeItem(project, repo, c));
                }
            }
            return items;
        } catch (Exception e) {
            LOG.warning("compare failed for " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    /** Fallback when there's no lower boundary: walk newest-first from {@code toCommitId}. */
    private List<ChangeItem> fetchCommitsRevisionRange(String project, String repo, String toCommitId) {
        try {
            Response commitResp = client.listCommits(project, repo, toCommitId, null, null, 100, 1);
            List<GitHubCommit> commits = parseList(commitResp, GitHubCommit.class);
            return commits.stream().map(c -> toChangeItem(project, repo, c)).toList();
        } catch (Exception e) {
            LOG.warning("listCommits failed for " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    private static final Pattern PR_NUMBER = Pattern.compile("#(\\d+)");

    private ChangeItem toChangeItem(String project, String repo, GitHubCommit c) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setId(c.sha());
        item.setTitle(c.commit() != null && c.commit().message() != null ? c.commit().message() : "");
        item.setDescription(item.getTitle());
        item.setCategory(ChangeCategoryClassifier.fromText(item.getTitle()));
        item.setAuthor(c.commit() != null && c.commit().author() != null ? c.commit().author().name() : null);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(c.commit() != null && c.commit().committer() != null ? c.commit().committer().date() : null);
        item.setLinks(c.htmlUrl() != null ? List.of(c.htmlUrl()) : List.of());
        item.setFilePaths(fetchCommitFilePaths(project, repo, c.sha()));
        return item;
    }

    private ChangeItem toChangeItemJson(String project, String repo, JsonNode commitNode) {
        String sha = commitNode.path("sha").asText(null);
        JsonNode detailNode = commitNode.path("commit");
        String message = detailNode.path("message").asText("");
        JsonNode authorNode = detailNode.path("author");
        String author = authorNode.path("name").asText(null);
        String date = detailNode.path("committer").path("date").asText(null);
        String url = commitNode.path("html_url").asText(null);

        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.COMMIT);
        item.setId(sha);
        item.setTitle(message);
        item.setDescription(message);
        item.setCategory(ChangeCategoryClassifier.fromText(message));
        item.setAuthor(author);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(date);
        item.setLinks(url != null ? List.of(url) : List.of());
        item.setFilePaths(List.of());
        return item;
    }

    private ChangeItem toChangeItem(String project, String repo, JsonNode commit) {
        return toChangeItemJson(project, repo, commit);
    }

    private ChangeItem toChangeItem(String project, String repo, GitHubPullRequest pr) {
        ChangeItem item = new ChangeItem();
        item.setType(ChangeItem.ItemType.PULL_REQUEST);
        item.setId(String.valueOf(pr.number()));
        item.setTitle(pr.title());
        item.setDescription(pr.body());
        item.setCategory(ChangeCategoryClassifier.fromText(pr.title()));
        item.setAuthor(pr.user() != null ? pr.user().login() : null);
        item.setProject(project);
        item.setRepo(repo);
        item.setDate(pr.mergedAt() != null ? pr.mergedAt() : pr.createdAt());
        item.setLinks(pr.htmlUrl() != null ? List.of(pr.htmlUrl()) : List.of());
        item.setFilePaths(List.of());
        return item;
    }

    private final Map<String, List<String>> commitFilePathsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 3000;
                }
            });

    private List<String> fetchCommitFilePaths(String project, String repo, String sha) {
        if (sha == null) return List.of();
        String key = project + "/" + repo + "/" + sha;
        List<String> cached = commitFilePathsCache.get(key);
        if (cached != null) return cached;
        try {
            GitHubCommit commit = getCommitFromClient(project, repo, sha);
            List<String> paths = new ArrayList<>();
            if (commit.files() != null) {
                for (GitHubFile f : commit.files()) {
                    if (f.filename() != null) paths.add(f.filename());
                }
            }
            commitFilePathsCache.put(key, paths);
            return paths;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Commit count for a version — 0 when the tag isn't found or there's no range. */
    public int commitCountForVersion(String project, String repo, String version, String branch) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);
        List<GitHubTag> tags = listTagRefs(project, repo);
        String from = findPreviousTag(tags, version) != null ? findPreviousTag(tags, version).commitId() : null;
        String to = findTagSha(tags, version);
        if (to == null) {
            String head = resolveBranchHead(project, repo, resolvedBranch);
            to = head;
        }
        if (from == null || to == null) return 0;
        try {
            List<GitHubCommit> commits = fetchListCommits(project, repo, to, null, 100, 1);
            int count = 0;
            for (GitHubCommit c : commits) {
                if (c.sha() != null && c.sha().equals(from)) break;
                count++;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<GitHubCommit> fetchListCommits(String project, String repo, String sha, String path, int perPage, int page) {
        return parseList(client.listCommits(project, repo, sha, null, null, perPage, page), GitHubCommit.class);
    }

    /** Everything merged into the branch since the last real tagged release. */
    public ReleaseData fetchChangesSinceLastRelease(String project, String repo, String branch) {
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);
        List<GitHubTag> tags = listTagRefs(project, repo);
        VersionTag latest = null;
        int[] best = null;
        for (GitHubTag t : tags) {
            int[] s = parseSemver(stripVersionTagPrefix(t.name()));
            if (s == null) continue;
            if (latest == null || compareSegments(s, best) > 0) {
                latest = new VersionTag(t.name(), t.commit() != null ? t.commit().sha() : null);
                best = s;
            }
        }
        String head = resolveBranchHead(project, repo, resolvedBranch);
        List<ChangeItem> items = new ArrayList<>();
        if (latest != null && latest.commitId() != null && head != null) {
            items.addAll(fetchCommitsInCompare(project, repo, latest.commitId(), head));
        } else if (head != null) {
            items.addAll(fetchCommitsRevisionRange(project, repo, head));
        }
        return buildReleaseData(project, repo, resolvedBranch, items);
    }

    // ---- Changeless work item analog ----

    /** GitHub has no work items — used by the resource's listWorkItems parity for a non-empty
     * provider response; stays empty (GitHub's change metadata lives on PRs/commits). */
    public List<ChangeItem> listWorkItems(String project) {
        return List.of();
    }

    /** Per-version author/date enrichment for the history page — matched by tag when a version has
     * one, otherwise by the commit that touched CHANGELOG.md nearest to (before) the entry's date.
     * Mirrors {@code AzureDevOpsOrgConnector#enrichChangelogEntries}; returns an empty map (never
     * null) when nothing can be correlated. */
    public Map<String, ChangelogEnrichment> enrichChangelogEntries(
            String project, String repo, String filename, List<ChangelogEntry> entries) {
        if (entries == null || entries.isEmpty()) return Map.of();

        Map<String, ChangelogEnrichment> result = new HashMap<>();
        List<GitHubTag> tags = listTagRefs(project, repo);
        for (ChangelogEntry entry : entries) {
            String ver = entry.version();
            if (ver == null) continue;
            String commitId = findTagSha(tags, ver);
            if (commitId == null) continue;
            GitHubCommit commit = null;
            try {
                commit = getCommitFromClient(project, repo, commitId);
            } catch (Exception e) {
                LOG.warning("getCommit failed for tag of " + ver + " on " + project + "/" + repo + ": " + e);
            }
            if (commit != null && commit.commit() != null && commit.commit().author() != null) {
                result.put(ver, new ChangelogEnrichment(commit.commit().author().name(), commit.commit().author().date()));
            }
        }

        boolean anyUnmatched = entries.stream().anyMatch(e -> e.version() != null && !result.containsKey(e.version()));
        if (anyUnmatched && filename != null) {
            // Fallback: correlate by date against commits that touched the changelog file itself.
            List<GitHubCommit> fileCommits = fetch(() -> parseList(
                    client.listPathCommits(project, repo, filename, null, 100), GitHubCommit.class));
            if (!fileCommits.isEmpty()) {
                for (ChangelogEntry entry : entries) {
                    String ver = entry.version();
                    if (ver == null || result.containsKey(ver)) continue;
                    for (GitHubCommit c : fileCommits) {
                        String date = c.commit() != null && c.commit().committer() != null
                                ? c.commit().committer().date() : null;
                        if (date != null && entry.date() != null && date.substring(0, 10).compareTo(entry.date()) <= 0) {
                            String author = c.commit() != null && c.commit().author() != null
                                    ? c.commit().author().name() : null;
                            result.put(ver, new ChangelogEnrichment(author, date));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    public record ChangelogEnrichment(String author, String timestamp) {}

    public record PullRequestDetails(
            int prId, String title, String description, String author,
            List<String> commitsMessages, List<WorkItemSummary> workItems) {}

    public record WorkItemSummary(int id, String title, String type, String description,
                                  String state, String assignedTo, String url) {}

    /** One GitHub PR's own metadata + its commits — GitHub has no "work items" so the work-item
     * list stays empty (a GitHub analog exists on issues, which this deliberately does not query). */
    public PullRequestDetails fetchPullRequestDetails(String project, String repo, int prId) {
        try {
            GitHubPullRequest pr = client.getPullRequest(project, repo, prId);
            List<String> commitMessages = fetch(() -> parseList(
                    client.listPullRequestCommits(project, repo, prId, 100), GitHubCommit.class)).stream()
                    .map(c -> c.commit() != null && c.commit().message() != null ? c.commit().message() : "")
                    .toList();
            return new PullRequestDetails(
                    (int) pr.number(),
                    pr.title(), pr.body(),
                    pr.user() != null ? pr.user().login() : null,
                    commitMessages, List.of());
        } catch (Exception e) {
            LOG.warning("fetchPullRequestDetails failed for " + project + "/" + repo + " #" + prId + ": " + e);
            return new PullRequestDetails(prId, null, null, null, List.of(), List.of());
        }
    }

    /** Org-wide fetch for the dashboard's "fetch everything" — GitHub has one owner (the project
     * tier) whose repos are each a ReleaseData; work items are always empty. */
    public com.hubsabai.changelog.core.model.OrgFetchResult fetchAll() {
        var ownerSummary = listOwnerAsProject();
        List<com.hubsabai.changelog.core.model.ProjectFetchResult> projects = new ArrayList<>();
        List<com.hubsabai.changelog.core.model.ReleaseData> repos = new ArrayList<>();
        for (com.hubsabai.changelog.core.model.RepositorySummary r : listRepositories(owner)) {
            com.hubsabai.changelog.core.model.ReleaseData rd;
            try {
                rd = fetchRepoChanges(owner, r.name());
            } catch (Exception e) {
                LOG.warning("fetchAll skipped " + owner + "/" + r.name() + ": " + e);
                rd = null;
            }
            if (rd != null) repos.add(rd);
        }
        projects.add(new com.hubsabai.changelog.core.model.ProjectFetchResult(ownerSummary, List.of(), repos));
        return new com.hubsabai.changelog.core.model.OrgFetchResult("github", projects);
    }

    /** GitHub has no pipeline runs — returns empty (parity for the dashboard's builds list). */
    public List<com.hubsabai.changelog.core.model.PipelineRunSummary> listRecentBuilds(String project, String repo, int top) {
        return List.of();
    }

/** Fetches the commits/PRs for a specific GitHub Actions workflow run (by its run ID), as the
     * provider-normalized context the shared pipeline consumes. */
    public RunChangeContext fetchRunContext(String project, String repo, long buildId) {
        String effectiveOwner = project != null && !project.isBlank() ? project : owner;
        RunChangeContext ctx = new RunChangeContext();

        GitHubWorkflowRun run = getWorkflowRun(effectiveOwner, repo, buildId);
        RunChangeContext.RunInfo runInfo = new RunChangeContext.RunInfo();
        runInfo.setRunId(String.valueOf(buildId));
        runInfo.setRunNumber(String.valueOf(run != null ? run.runNumber() : buildId));
        runInfo.setPipelineName(run != null ? run.workflowName() : null);
        runInfo.setStatus(run != null ? run.status() : null);
        runInfo.setResult(run != null ? run.conclusion() : null);
        runInfo.setBranch(run != null ? run.headBranch() : null);
        runInfo.setHeadSha(run != null ? run.headSha() : null);
        runInfo.setStartedAt(run != null ? run.runStartedAt() : null);
        runInfo.setFinishedAt(run != null ? (run.completedAt() != null ? run.completedAt() : run.updatedAt()) : null);
        runInfo.setProviderCommitMessage(run != null && run.headCommit() != null ? run.headCommit().message() : null);
        runInfo.setTriggerUrl(run != null && run.headCommit() != null && run.headCommit().author() != null
                ? "https://github.com/" + effectiveOwner + "/" + repo + "/commit/" + run.headSha()
                : null);
        ctx.setRun(runInfo);

        if (run == null) {
            ctx.setCommits(List.of());
            ctx.setFiles(List.of());
            ctx.setWorkItems(List.of());
            return ctx;
        }

        // PR(s) associated with the run — GitHub Actions runs carry pull_requests refs directly.
        List<GitHubWorkflowRun.PullRequestRef> refs = run.pullRequests();
        if (refs != null && !refs.isEmpty()) {
            try {
                GitHubPullRequest pr = client.getPullRequest(effectiveOwner, repo, refs.get(0).number());
                RunChangeContext.PrInfo prInfo = new RunChangeContext.PrInfo();
                prInfo.setId(String.valueOf(pr.number()));
                prInfo.setTitle(pr.title());
                prInfo.setDescription(pr.body());
                prInfo.setAuthor(pr.user() != null ? pr.user().login() : null);
                prInfo.setState(pr.state());
                prInfo.setUrl(pr.htmlUrl());
                prInfo.setUpdatedAt(pr.mergedAt() != null ? pr.mergedAt() : pr.createdAt());
                ctx.setPr(prInfo);
            } catch (Exception e) {
                LOG.warning("GitHub PR for run " + buildId + " fetch failed: " + e);
            }
        } else {
            // schedule / dynamic / push-triggered runs don't carry PR refs, but the head commit may
            // still be a merge commit of a merged PR — resolve it the same way fetchRunChanges does.
            RunChangeContext.PrInfo prInfo = findPrByMergeCommit(effectiveOwner, repo, run.headSha());
            if (prInfo != null) {
                ctx.setPr(prInfo);
            }
        }

        String headSha = run.headSha();
        String baseSha = getParentSha(effectiveOwner, repo, headSha);
        List<GitHubCommit> commits;
        if (baseSha != null) {
            commits = compareCommits(project, effectiveOwner, repo, baseSha, headSha);
        } else {
            GitHubCommit headCommit = getCommitFromClient(effectiveOwner, repo, headSha);
            commits = headSha != null ? List.of(headCommit) : List.of();
        }

        List<RunChangeContext.CommitInfo> commitInfos = new ArrayList<>();
        for (GitHubCommit commit : commits) {
            RunChangeContext.CommitInfo ci = new RunChangeContext.CommitInfo();
            ci.setSha(commit.sha());
            String message = commit.commit() != null ? commit.commit().message() : "";
            ci.setMessage(message);
            ci.setAuthor(commit.commit() != null && commit.commit().author() != null
                    ? commit.commit().author().name()
                    : commit.commit() != null && commit.commit().committer() != null
                            ? commit.commit().committer().name()
                            : "unknown");
            ci.setDate(commit.commit() != null && commit.commit().committer() != null
                    ? commit.commit().committer().date() : null);
            ci.setFilePaths(fetchCommitFilePaths(effectiveOwner, repo, commit.sha()));
            commitInfos.add(ci);
        }
        ctx.setCommits(commitInfos);
        ctx.setFiles(collectDiffFiles(effectiveOwner, repo, baseSha, headSha));
        ctx.setWorkItems(List.of());
        return ctx;
    }

    /** Per-run total diff summary (files with add/delete stats) for the context panel. */
    private List<RunChangeContext.FileChangeInfo> collectDiffFiles(String effectiveOwner, String repo,
            String baseSha, String headSha) {
        if (baseSha == null || headSha == null) return List.of();
        try (Response response = client.compareCommits(effectiveOwner, repo, baseSha, headSha)) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) return List.of();
            JsonNode node = MAPPER.readTree(response.readEntity(String.class));
            List<RunChangeContext.FileChangeInfo> files = new ArrayList<>();
            if (node.has("files")) {
                for (JsonNode f : node.get("files")) {
                    RunChangeContext.FileChangeInfo info = new RunChangeContext.FileChangeInfo();
                    info.setPath(f.path("filename").asText(null));
                    info.setStatus(f.path("status").asText(null));
                    info.setAdditions(f.path("additions").asInt(0));
                    info.setDeletions(f.path("deletions").asInt(0));
                    files.add(info);
                }
            }
            return files;
        } catch (Exception e) {
            LOG.warning("GitHub diff files fetch failed for " + effectiveOwner + "/" + repo + ": " + e);
            return List.of();
        }
    }

    /** Fetches the commits/PRs for a specific GitHub Actions workflow run (by its run ID). */
    public com.hubsabai.changelog.core.model.ReleaseData fetchRunChanges(String project, String repo, long buildId) {
        String effectiveOwner = project != null && !project.isBlank() ? project : owner;

        // 1. Get the workflow run details
        GitHubWorkflowRun run = getWorkflowRun(effectiveOwner, repo, buildId);
        if (run == null) {
            return buildReleaseData(project, repo, defaultBranch(project, repo), List.of());
        }

        String headSha = run.headSha();
        String baseSha = getParentSha(effectiveOwner, repo, headSha);

        List<GitHubCommit> commits;
        if (baseSha != null) {
            // Normal case: compare base..head
            commits = compareCommits(project, effectiveOwner, repo, baseSha, headSha);
        } else {
            // No parent (initial commit) - fetch the head commit itself
            GitHubCommit headCommit = getCommitFromClient(effectiveOwner, repo, headSha);
            commits = headSha != null ? List.of(headCommit) : List.of();
        }

        // Map to ChangeItem, now with real file paths and category classifier instead of "feat".
        List<com.hubsabai.changelog.core.model.ChangeItem> items = commits.stream().map(commit -> {
            com.hubsabai.changelog.core.model.ChangeItem item = new com.hubsabai.changelog.core.model.ChangeItem();
            item.setType(com.hubsabai.changelog.core.model.ChangeItem.ItemType.COMMIT);
            item.setId(commit.sha());
            String message = commit.commit() != null ? commit.commit().message() : "";
            item.setTitle(message.split("\n")[0]);
            item.setCategory(ChangeCategoryClassifier.fromText(message));
            item.setDescription(message);
            String author = commit.commit() != null && commit.commit().author() != null
                    ? commit.commit().author().name()
                    : commit.commit() != null && commit.commit().committer() != null
                            ? commit.commit().committer().name()
                            : "unknown";
            item.setAuthor(author);
            item.setProject(project);
            item.setRepo(repo);
            String date = commit.commit() != null && commit.commit().author() != null && commit.commit().author().date() != null
                    ? commit.commit().author().date()
                    : commit.commit() != null && commit.commit().committer() != null && commit.commit().committer().date() != null
                            ? commit.commit().committer().date()
                            : null;
            item.setDate(date);
            item.setLinks(List.of("https://github.com/" + effectiveOwner + "/" + repo + "/commit/" + commit.sha()));
            item.setFilePaths(fetchCommitFilePaths(effectiveOwner, repo, commit.sha()));
            item.setProject(project);
            item.setRepo(repo);
            return item;
        }).toList();

        return buildReleaseData(project, repo, defaultBranch(project, repo), items);
    }

    /**
     * Fetches both the normalized {@link ReleaseData} (for changelog generation) and the
     * {@link RunChangeContext} (for dashboard run context) in a single coordinated fetch.
     * This avoids duplicate provider API calls when both pieces of data are needed
     * (e.g., during pipeline ingestion where we need to store both the run snapshot and
     * the normalized change data for changelog generation).
     */
    public RunFetchResult fetchRunData(String project, String repo, long buildId) {
        String effectiveOwner = project != null && !project.isBlank() ? project : owner;

        // 1. Get the workflow run details (single fetch)
        GitHubWorkflowRun run = getWorkflowRun(effectiveOwner, repo, buildId);
        if (run == null) {
            com.hubsabai.changelog.core.model.ReleaseData emptyData = buildReleaseData(project, repo, defaultBranch(project, repo), List.of());
            RunChangeContext emptyCtx = new RunChangeContext();
            return new RunFetchResult(emptyData, emptyCtx);
        }

        String headSha = run.headSha();
        String baseSha = getParentSha(effectiveOwner, repo, headSha);

        List<GitHubCommit> commits;
        if (baseSha != null) {
            // Normal case: compare base..head
            commits = compareCommits(project, effectiveOwner, repo, baseSha, headSha);
        } else {
            // No parent (initial commit) - fetch the head commit itself
            GitHubCommit headCommit = getCommitFromClient(effectiveOwner, repo, headSha);
            commits = headSha != null ? List.of(headCommit) : List.of();
        }

        // Map to ChangeItem, now with real file paths and category classifier instead of "feat".
        List<com.hubsabai.changelog.core.model.ChangeItem> items = commits.stream().map(commit -> {
            com.hubsabai.changelog.core.model.ChangeItem item = new com.hubsabai.changelog.core.model.ChangeItem();
            item.setType(com.hubsabai.changelog.core.model.ChangeItem.ItemType.COMMIT);
            item.setId(commit.sha());
            String message = commit.commit() != null ? commit.commit().message() : "";
            item.setTitle(message.split("\n")[0]);
            item.setCategory(ChangeCategoryClassifier.fromText(message));
            item.setDescription(message);
            String author = commit.commit() != null && commit.commit().author() != null
                    ? commit.commit().author().name()
                    : commit.commit() != null && commit.commit().committer() != null
                            ? commit.commit().committer().name()
                            : "unknown";
            item.setAuthor(author);
            item.setProject(project);
            item.setRepo(repo);
            String date = commit.commit() != null && commit.commit().author() != null && commit.commit().author().date() != null
                    ? commit.commit().author().date()
                    : commit.commit() != null && commit.commit().committer() != null && commit.commit().committer().date() != null
                            ? commit.commit().committer().date()
                            : null;
            item.setDate(date);
            item.setLinks(List.of("https://github.com/" + effectiveOwner + "/" + repo + "/commit/" + commit.sha()));
            item.setFilePaths(fetchCommitFilePaths(effectiveOwner, repo, commit.sha()));
            item.setProject(project);
            item.setRepo(repo);
            return item;
        }).toList();

        // Build ReleaseData
        com.hubsabai.changelog.core.model.ReleaseData releaseData = buildReleaseData(project, repo, defaultBranch(project, repo), items);

        // Build RunChangeContext from the same data (reusing the already-fetched run and commits)
        RunChangeContext context = new RunChangeContext();
        RunChangeContext.RunInfo runInfo = new RunChangeContext.RunInfo();
        runInfo.setRunId(String.valueOf(buildId));
        runInfo.setRunNumber(String.valueOf(run != null ? run.runNumber() : buildId));
        runInfo.setPipelineName(run != null ? run.workflowName() : null);
        runInfo.setStatus(run != null ? run.status() : null);
        runInfo.setResult(run != null ? run.conclusion() : null);
        runInfo.setBranch(run != null ? run.headBranch() : null);
        runInfo.setHeadSha(run != null ? run.headSha() : null);
        runInfo.setStartedAt(run != null ? run.runStartedAt() : null);
        runInfo.setFinishedAt(run != null ? (run.completedAt() != null ? run.completedAt() : run.updatedAt()) : null);
        runInfo.setProviderCommitMessage(run != null && run.headCommit() != null ? run.headCommit().message() : null);
        runInfo.setTriggerUrl(run != null && run.headCommit() != null && run.headCommit().author() != null
                ? "https://github.com/" + effectiveOwner + "/" + repo + "/commit/" + run.headSha()
                : null);
        context.setRun(runInfo);

        // PR(s) associated with the run
        List<GitHubWorkflowRun.PullRequestRef> refs = run.pullRequests();
        if (refs != null && !refs.isEmpty()) {
            for (GitHubWorkflowRun.PullRequestRef ref : refs) {
                try {
                    GitHubPullRequest pr = client.getPullRequest(effectiveOwner, repo, ref.number());
                    RunChangeContext.PrInfo prInfo = new RunChangeContext.PrInfo();
                    prInfo.setId(String.valueOf(pr.number()));
                    prInfo.setTitle(pr.title());
                    prInfo.setDescription(pr.body());
                    prInfo.setAuthor(pr.user() != null ? pr.user().login() : null);
                    prInfo.setState(pr.state());
                    prInfo.setUrl(pr.htmlUrl());
                    prInfo.setUpdatedAt(pr.mergedAt() != null ? pr.mergedAt() : pr.createdAt());
                    context.getPrs().add(prInfo);
                } catch (Exception e) {
                    LOG.warning("GitHub PR for run " + buildId + " fetch failed: " + e);
                }
            }
        } else {
            // Same fallback as fetchRunContext for schedule/push/dynamic-triggered runs.
            RunChangeContext.PrInfo prInfo = findPrByMergeCommit(effectiveOwner, repo, headSha);
            if (prInfo != null) {
                context.getPrs().add(prInfo);
            }
        }

        List<RunChangeContext.CommitInfo> commitInfos = new ArrayList<>();
        for (GitHubCommit commit : commits) {
            RunChangeContext.CommitInfo ci = new RunChangeContext.CommitInfo();
            ci.setSha(commit.sha());
            String message = commit.commit() != null ? commit.commit().message() : "";
            ci.setMessage(message);
            ci.setAuthor(commit.commit() != null && commit.commit().author() != null
                    ? commit.commit().author().name()
                    : commit.commit() != null && commit.commit().committer() != null
                            ? commit.commit().committer().name()
                            : "unknown");
            ci.setDate(commit.commit() != null && commit.commit().committer() != null
                    ? commit.commit().committer().date() : null);
            ci.setFilePaths(fetchCommitFilePaths(effectiveOwner, repo, commit.sha()));
            commitInfos.add(ci);
        }
        context.setCommits(commitInfos);
        context.setFiles(collectDiffFiles(effectiveOwner, repo, baseSha, headSha));
        context.setWorkItems(List.of());

        return new RunFetchResult(releaseData, context);
    }

/** Helper to fetch a single commit from the REST client. */
    private GitHubCommit getCommitFromClient(String owner, String repo, String sha) {
        try {
            return client.getCommit(owner, repo, sha);
        } catch (Exception e) {
            LOG.warning("GitHub get commit failed: " + e);
            return null;
        }
    }

    /** Get the first parent SHA of a commit. Returns null if no parent (initial commit). */
    private String getParentSha(String effectiveOwner, String repo, String sha) {
        GitHubCommit commit = getCommitFromClient(effectiveOwner, repo, sha);
        if (commit != null && commit.parents() != null && !commit.parents().isEmpty()) {
            return commit.parents().get(0).sha();
        }
        return null;
    }

    /** Resolves the merged PR whose {@code merge_commit_sha} equals the given commit, if any.
     * Mirrors {@link #mergePrsInto} so run-context shows the same PR the changes endpoint does,
     * even for schedule/push/dynamic runs that GitHub reports without {@code pull_requests} refs. */
    private RunChangeContext.PrInfo findPrByMergeCommit(String project, String repo, String sha) {
        if (sha == null || sha.isBlank()) return null;
        try {
            List<GitHubPullRequest> prs = fetchClosedPrsCached(project, repo, defaultBranch(project, repo));
            for (GitHubPullRequest pr : prs) {
                if (pr.mergeCommitSha() != null && sha.equals(pr.mergeCommitSha()) && pr.mergedAt() != null) {
                    RunChangeContext.PrInfo info = new RunChangeContext.PrInfo();
                    info.setId(String.valueOf(pr.number()));
                    info.setTitle(pr.title());
                    info.setDescription(pr.body());
                    info.setAuthor(pr.user() != null ? pr.user().login() : null);
                    info.setState(pr.state());
                    info.setUrl(pr.htmlUrl());
                    info.setUpdatedAt(pr.mergedAt() != null ? pr.mergedAt() : pr.createdAt());
                    return info;
                }
            }
        } catch (Exception e) {
            LOG.warning("GitHub PR resolution by merge commit failed for " + project + "/" + repo + ": " + e);
        }
        return null;
    }

    /** Helper to fetch a single workflow run. */
    private GitHubWorkflowRun getWorkflowRun(String effectiveOwner, String repo, long runId) {
        try (Response response = client.getWorkflowRun(effectiveOwner, repo, runId)) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return null;
            }
            return response.readEntity(GitHubWorkflowRun.class);
        } catch (Exception e) {
            LOG.warning("GitHub get workflow run failed: " + e);
            return null;
        }
    }

    /** Compare commits between base and head. */
    private List<GitHubCommit> compareCommits(String project, String effectiveOwner, String repo, String baseSha, String headSha) {
        try (Response response = client.compareCommits(effectiveOwner, repo, baseSha, headSha)) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return List.of();
            }
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(response.readEntity(String.class));
            if (node.has("commits")) {
                return MAPPER.readValue(node.get("commits").toString(),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, GitHubCommit.class));
            }
            return List.of();
        } catch (Exception e) {
            LOG.warning("GitHub compare failed for " + project + "/" + repo + ": " + e);
            return List.of();
        }
    }

    // ---- push as PR ----

    /**
     * Pushes the Developer changelog as a branch + PR (GitHub has no direct-commit push for a
     * foreign file, unlike Azure's single-commit API). Re-fetches the live CHANGELOG.md, applies
     * the same edit/create logic as Azure, then opens a PR back into {@code branch}.
     *
     * @return the PR's html_url. Failures propagate as unchecked exceptions.
     */
    public String pushChangelogEdit(String project, String repo, String branch, String version, String newBody) {
        // 1. Re-fetch current file at the target branch tip.
        String resolvedBranch = branch != null ? branch : defaultBranch(project, repo);
        ChangelogFile file = fetchChangelogFile(project, repo, resolvedBranch);
        boolean fileExists = file != null;
        String filename = fileExists ? file.filename() : ChangelogMarkdown.CHANGELOG_FILENAMES.get(0);
        String existingContent = fileExists ? file.content() : null;

        Optional<String> replaced = fileExists ? ChangelogMarkdown.replaceChangelogEntryBody(existingContent, version, newBody) : Optional.empty();
        boolean isNewEntry = replaced.isEmpty();
        String updated;
        if (isNewEntry) {
            if (!ChangelogMarkdown.VALID_NEW_VERSION.matcher(version).matches()) {
                throw new IllegalStateException("'" + version + "' doesn't look like a real version — refusing to push.");
            }
            if (newBody == null || newBody.isBlank()) {
                throw new IllegalStateException("Nothing to push — the changelog text is empty.");
            }
            updated = ChangelogMarkdown.insertNewChangelogEntry(existingContent, version, newBody);
        } else {
            updated = replaced.get();
        }

        String baseBranchSha = resolveBranchHead(project, repo, resolvedBranch);
        if (baseBranchSha == null) {
            throw new IllegalStateException("Branch '" + resolvedBranch + "' not found on " + project + "/" + repo + ".");
        }

        String branchName = "changelog/" + version + "-" + baseBranchSha.substring(0, Math.min(8, baseBranchSha.length()));
        String fullRef = "refs/heads/" + branchName;

        try {
            // 2. Create a branch off the target branch's current tip (409 if it already exists —
            // e.g. an earlier push for the same version; that's a re-push, so just continue).
            try (Response create = client.createRef(project, repo, new GitHubCreateRef(fullRef, baseBranchSha))) {
                if (create.getStatus() >= 300 && create.getStatus() != 422) {
                    throw new IllegalStateException("Failed to create branch '" + branchName
                            + "' (HTTP " + create.getStatus() + ").");
                }
            }

            // 3. Create a blob carrying the new CHANGELOG.md content.
            String contentB64 = java.util.Base64.getEncoder().encodeToString(updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            GitHubBlob blob = client.createBlob(project, repo,
                    MAPPER.writeValueAsString(Map.of("content", contentB64, "encoding", "base64")));
            if (blob == null || blob.sha() == null) {
                throw new IllegalStateException("Failed to create blob: response was null or missing sha");
            }
            String blobSha = blob.sha();

            // 4. Build a tree: the base commit's own tree plus the changed CHANGELOG.md.
            String baseTreeSha = fetchBaseTreeSha(project, repo, baseBranchSha);
            if (baseTreeSha == null) {
                throw new IllegalStateException("Failed to fetch base tree sha for commit " + baseBranchSha);
            }
            if (filename == null) {
                throw new IllegalStateException("Filename is null");
            }
            String treeJson = MAPPER.writeValueAsString(Map.of(
                    "base_tree", baseTreeSha,
                    "tree", List.of(Map.of("path", filename, "mode", "100644", "type", "blob", "sha", blobSha))));
            String treeSha = client.createTree(project, repo, treeJson).sha();

            // 5. Create a commit on that tree with the target branch tip as parent.
            String commitJson = MAPPER.writeValueAsString(Map.of(
                    "message", "Add v" + version + " developer changelog (via dashboard)",
                    "tree", treeSha,
                    "parents", List.of(baseBranchSha)));
            String commitSha = client.createCommit(project, repo, commitJson).sha();

            // 6. Point the new branch at the new commit.
            try (Response update = client.updateRef(project, repo, branchName,
                    MAPPER.writeValueAsString(Map.of("sha", commitSha, "force", true)))) {
                if (update.getStatus() >= 300) {
                    throw new IllegalStateException("Failed to update GitHub branch '" + branchName
                            + "' (HTTP " + update.getStatus() + ").");
                }
            }

            // 7. Open a PR back into the source branch.
            GitHubCreatedPullRequest created = client.createPullRequest(project, repo, new GitHubCreatePullRequest(
                    "Add v" + version + " developer changelog",
                    "Automated changelog update via Changelog Composer.",
                    branchName, resolvedBranch));
            return created.htmlUrl();
        } catch (WebApplicationException e) {
            throw new IllegalStateException("GitHub push failed for " + project + "/" + repo + ": " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException("GitHub push failed for " + project + "/" + repo + ": " + e.getMessage(), e);
        }
    }

    /** The tree sha a commit's tree points at — read from the commit detail. */
    private String fetchBaseTreeSha(String project, String repo, String commitSha) {
        if (commitSha == null) return null;
        try {
            GitHubCommit commit = getCommitFromClient(project, repo, commitSha);
            if (commit == null) return null;
            return commit.tree() != null ? commit.tree().sha() : null;
        } catch (Exception e) {
            LOG.warning("fetchBaseTreeSha failed for " + project + "/" + repo + ": " + e);
            return null;
        }
    }
}
