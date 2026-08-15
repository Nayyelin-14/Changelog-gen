package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitRef;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.logging.Logger;

/**
 * Scans for release boundaries (tags, version markers) and determines version ranges.
 */
@ApplicationScoped
public class ReleaseBoundaryScanner {

    private static final Pattern ANY_RELEASE_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)*");

    @Inject
    EnrichmentCache enrichmentCache;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    private static final Logger LOG = Logger.getLogger(ReleaseBoundaryScanner.class.getName());

    /**
     * Finds the tag commit SHA for a version (tries common tag patterns).
     */
    public String findTagCommit(String project, String repo, String version) {
        try {
            List<GitRef> tags = enrichmentCache.getTags(project, repo);
            if (tags == null) return null;
            for (GitRef ref : tags) {
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

    /**
     * Highest version tag that sorts below {@code currentVersion}, or null.
     */
    public VersionTag findPreviousTag(String project, String repo, String currentVersion) {
        List<GitRef> tags;
        try {
            tags = enrichmentCache.getTags(project, repo);
        } catch (Exception e) {
            LOG.warning("listRefs failed for " + project + "/" + repo + ": " + e);
            return null;
        }
        if (tags == null) return null;

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

    /**
     * Newest-first scan for the first commit carrying a recognized release marker — a real git
     * tag, or a "Release X.Y.Z [skip ci]"-style commit message — whatever version it reports.
     * Null if nothing recognizable turns up within the scan cap (50 pages), meaning the
     * caller should treat the whole branch as unreleased rather than guess a boundary.
     */
    public String findLatestReleaseBoundaryCommitId(String project, String repo, String branch) {
        Map<String, String> tagVersionsByCommitId = listTagVersionsByCommitId(project, repo);
        int skip = 0;
        for (int page = 0; page < 50; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommitsByRange(
                    org, project, repo, 100, skip, null, null, branch, null, AzureDevOpsRestClient.API_VERSION);
            List<CommitResponse> pageCommits = response.valueOrEmpty();
            if (pageCommits.isEmpty()) break;
            for (CommitResponse c : pageCommits) {
                if (tagVersionsByCommitId.containsKey(c.commitId()) || extractAnyReleaseVersion(c.comment()) != null) {
                    return c.commitId();
                }
            }
            skip += pageCommits.size();
            if (pageCommits.size() < 100) break;
        }
        return null;
    }

    /**
     * Scans the branch ONCE from its tip to find both the target version's boundary and the
     * previous release's boundary within that same scan. Boundaries are recognized by git tag
     * or release-marker commit message (e.g. "Release 1.4.76 [skip ci]").
     */
    public VersionRangeResult findVersionRange(String project, String repo, String branch, String fromVersion, String toVersion) {
        Map<String, String> tagVersionsByCommitId = listTagVersionsByCommitId(project, repo);
        String toTagCommitId = findTagCommitId(tagVersionsByCommitId, toVersion);
        String fromTagCommitId = fromVersion != null && !fromVersion.isBlank()
                ? findTagCommitId(tagVersionsByCommitId, fromVersion) : null;
        int[] requestedVer = parseSemver(toVersion);

        String scanAnchor = toTagCommitId;

        List<CommitResponse> window = new ArrayList<>();
        int skip = 0;
        int targetIdx = -1;
        int boundaryIdx = -1;
        boolean checkedLatestRelease = scanAnchor != null;
        for (int page = 0; page < 50; page++) {
            AzureDevOpsListResponse<CommitResponse> response = client.listCommitsByRange(
                    org, project, repo, 100, skip, null, scanAnchor, branch, null, AzureDevOpsRestClient.API_VERSION);
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
            if (pageCommits.size() < 100) break;
        }
        if (targetIdx == -1) {
            LOG.warning("No tag or release-marker commit found for " + project + "/" + repo + " v" + toVersion);
            return VersionRangeResult.ofNotFound();
        }
        if (boundaryIdx == -1) {
            LOG.warning("No older boundary found for " + project + "/" + repo + " v" + toVersion
                    + " within " + window.size() + " commits scanned (capped at 50 pages) — commit list may be incomplete");
            boundaryIdx = window.size();
        }

        List<CommitResponse> slice = new ArrayList<>(window.subList(targetIdx, boundaryIdx));
        String fromCommitId = boundaryIdx < window.size() ? window.get(boundaryIdx).commitId() : null;
        String toCommitId = window.get(targetIdx).commitId();
        return new VersionRangeResult(slice, fromCommitId, toCommitId, false);
    }

    /** True if {@code commitId} carries a recognized release tag for a version OTHER than {@code toVersion}. */
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
            List<GitRef> tags = enrichmentCache.getTags(project, repo);
            if (tags != null) {
                for (GitRef ref : tags) {
                    String tn = ref.tagName();
                    if (tn == null) continue;
                    String ver = stripVersionTagPrefix(tn);
                    if (parseSemver(ver) != null) {
                        result.put(ref.commitId(), ver);
                    }
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

    /** Common phrasings automated version-bump commits use across this org's repos. */
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

    /**
     * Strips a tag's version-prefix convention ({@code released/v}, {@code release-}, or {@code v}),
     * in that priority order, to recover the bare version string.
     */
    public static String stripVersionTagPrefix(String tagName) {
        if (tagName.startsWith("released/v")) return tagName.substring("released/v".length());
        if (tagName.startsWith("release-")) return tagName.substring("release-".length());
        if (tagName.startsWith("v")) return tagName.substring(1);
        return tagName;
    }

    public record VersionTag(String version, String commitId) {}

    /**
     * {@code notYetShipped=true} means the caller should fall through to the "upcoming release"
     * default (branch tip). {@code false} with empty {@code commits} means the version is
     * confirmed historical (an even older release already exists) but no tag or marker commit
     * was recognized for it — callers must NOT fall through to branch-tip in that case, since
     * showing unrelated recent commits under an old version's name would be actively wrong.
     */
    public record VersionRangeResult(List<CommitResponse> commits, String fromCommitId, String toCommitId, boolean notYetShipped) {
        static VersionRangeResult ofNotYetShipped() { return new VersionRangeResult(List.of(), null, null, true); }
        static VersionRangeResult ofNotFound() { return new VersionRangeResult(List.of(), null, null, false); }
    }
}