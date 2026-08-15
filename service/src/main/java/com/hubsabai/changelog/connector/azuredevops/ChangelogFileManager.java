package com.hubsabai.changelog.connector.azuredevops;

import com.hubsabai.changelog.ai.AiException;
import com.hubsabai.changelog.connector.azuredevops.dto.AzureDevOpsListResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitItemResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitPushRequest;
import com.hubsabai.changelog.connector.azuredevops.dto.GitPushResponse;
import com.hubsabai.changelog.connector.azuredevops.dto.GitRef;
import com.hubsabai.changelog.connector.azuredevops.dto.CommitChangesResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Manages changelog file operations: fetch, parse, replace, push.
 */
@ApplicationScoped
public class ChangelogFileManager {

    private static final List<String> CHANGELOG_FILENAMES = List.of("CHANGELOG.md", "changelog.md");

    @Inject
    @RestClient
    AzureDevOpsRestClient client;

    @Inject
    EnrichmentCache enrichmentCache;

    @Inject
    @ConfigProperty(name = "azure.devops.org", defaultValue = "CHANGE_ME")
    String org;

    private static final Logger LOG = Logger.getLogger(ChangelogFileManager.class.getName());

    // Matches either the "Keep a Changelog" convention ("## [1.2.3] - date", any bracket content)
    // or this app's own generated format ("## v1.2.3 — date", no brackets, digit-led version)
    private static final Pattern CHANGELOG_VERSION_HEADER = Pattern.compile(
            "^## \\[([^\\]]+)\\]\\s*[-–—]\\s*(.+)$|^## v?([0-9][\\w.-]*)\\s*[-–—]\\s*(.+)$",
            Pattern.MULTILINE
    );

    // A brand-new entry's version must be a strict semantic version (MAJOR.MINOR.PATCH) before it
    // is allowed to be written into a real CHANGELOG.md. Rejects bare integers like "9" and
    // leading-v integers like "v9".
    private static final Pattern VALID_NEW_VERSION = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");

    /**
     * Does this repo already have a committed changelog file at its root? Checked by filename, not content.
     */
    public boolean hasChangelogFile(String project, String repo) {
        return hasChangelogFile(project, repo, null);
    }

    /**
     * Same as {@link #hasChangelogFile(String, String)}, scoped to a specific branch instead of the repo's default.
     */
    public boolean hasChangelogFile(String project, String repo, String branch) {
        return fetchChangelogContent(project, repo, branch) != null;
    }

    /**
     * Same as {@link #hasChangelogFile(String, String)} but never throws — returns false and logs
     * on failure, for bulk scans across many repos where one repo's API error shouldn't sink the
     * whole list.
     */
    public boolean hasChangelogFileSafely(String project, String repo) {
        try {
            return hasChangelogFile(project, repo);
        } catch (Exception e) {
            LOG.warning("hasChangelogFile failed for " + project + "/" + repo + ": " + e);
            return false;
        }
    }

    /**
     * Fetches the repo's changelog file — name and raw content — or null when neither casing exists.
     */
    public EnrichmentCache.ChangelogFile fetchChangelogFile(String project, String repo) {
        return fetchChangelogFile(project, repo, null);
    }

    /**
     * Same as {@link #fetchChangelogFile(String, String)} but reads the file as it exists on
     * {@code branch} rather than the repo's default branch — {@code null} falls back to the default.
     */
    public EnrichmentCache.ChangelogFile fetchChangelogFileCached(String project, String repo, String branch) {
        return enrichmentCache.getChangelogFile(project, repo, branch);
    }

    public EnrichmentCache.ChangelogFile fetchChangelogFile(String project, String repo, String branch) {
        for (String filename : CHANGELOG_FILENAMES) {
            try {
                Response response = client.getItem(
                        org, project, repo, "/" + filename,
                        branch, branch != null ? "branch" : null,
                        AzureDevOpsRestClient.API_VERSION, true);
                try {
                    if (response.getStatus() == 200) {
                        GitItemResponse gitItem = response.readEntity(GitItemResponse.class);
                        return new EnrichmentCache.ChangelogFile(filename, gitItem.content());
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

    /**
     * Fetches the raw CHANGELOG.md content from the repo root, or null when no file exists.
     */
    public String fetchChangelogContent(String project, String repo) {
        return fetchChangelogContent(project, repo, null);
    }

    /**
     * Same as {@link #fetchChangelogContent(String, String)}, scoped to a specific branch.
     */
    public String fetchChangelogContent(String project, String repo, String branch) {
        EnrichmentCache.ChangelogFile file = fetchChangelogFile(project, repo, branch);
        return file != null ? file.content() : null;
    }

    /**
     * Normalizes a date string to YYYY-MM-DD. Handles M/D/YYYY and YYYY-MM-DD formats.
     */
    static String normalizeDate(String date) {
        if (date == null) return null;
        String trimmed = date.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) return trimmed;
        Matcher m = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matcher(trimmed);
        if (m.find()) {
            return String.format("%04d-%02d-%02d", Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return trimmed;
    }

    /**
     * Parses CHANGELOG.md into version entries by {@code ## [version] - date} headers. If no
     * headers match, the whole file is returned as a single unversioned entry.
     */
    public List<EnrichmentCache.ChangelogEntry> parseChangelogEntries(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        Matcher matcher = CHANGELOG_VERSION_HEADER.matcher(markdown);
        List<EnrichmentCache.ChangelogEntry> entries = new ArrayList<>();
        int prevStart = -1;
        String prevVersion = null;
        String prevDate = null;

        while (matcher.find()) {
            if (prevVersion != null) {
                String body = extractBody(markdown, prevStart, matcher.start());
                entries.add(new EnrichmentCache.ChangelogEntry(prevVersion, prevDate, body));
            }
            prevVersion = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            prevDate = normalizeDate(matcher.group(1) != null ? matcher.group(2) : matcher.group(4));
            prevStart = matcher.start();
        }
        if (prevVersion != null) {
            String body = extractBody(markdown, prevStart, markdown.length());
            entries.add(new EnrichmentCache.ChangelogEntry(prevVersion, prevDate, body));
        }
        if (entries.isEmpty()) {
            return List.of(new EnrichmentCache.ChangelogEntry(null, null, markdown.strip()));
        }
        Collections.reverse(entries);
        return entries;
    }

    /**
     * Grabs the raw text between two positions, skipping the header line itself.
     */
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
     *
     * @return the full file with that entry replaced, or empty if no header matches {@code version}
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
            prevVersion = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            int afterNewline = markdown.indexOf('\n', matcher.start());
            prevLineEnd = afterNewline >= 0 ? afterNewline + 1 : markdown.length();
        }
        if (targetLineEnd < 0 && version.equals(prevVersion)) {
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

    /**
     * Prepends a brand-new {@code ## vX — date} section, in this app's own bracketless format,
     * ahead of whatever's already in the file.
     */
    private static String insertNewChangelogEntry(String markdown, String version, String body) {
        String header = "## v" + version + " — " + LocalDate.now();
        String entry = header + "\n\n" + body.strip() + "\n\n";
        return markdown == null || markdown.isBlank() ? entry : entry + markdown;
    }

    /**
     * The commit SHA a branch currently points to, or null if the branch can't be found.
     */
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

    /**
     * Pushes a Developer changelog straight onto {@code branch} — a direct commit via the
     * service's own PAT (shows up in git history as a bot commit), never a branch + PR.
     *
     * @return a link to the resulting commit
     */
    public String pushChangelogEdit(String project, String repo, String branch, String version, String newBody) {
        EnrichmentCache.ChangelogFile file = fetchChangelogFile(project, repo, branch);
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
                    throw new IllegalStateException("Could not push to '" + branch + "' on " + project + "/" + repo
                            + " — it may have moved since this page loaded. Refresh and try again.");
                }
                GitPushResponse body = pushResponse.readEntity(GitPushResponse.class);
                commitId = body.commits() != null && !body.commits().isEmpty() ? body.commits().get(0).commitId() : null;
            } finally {
                pushResponse.close();
            }
        } catch (jakarta.ws.rs.WebApplicationException e) {
            String detail = null;
            if (e.getResponse() != null) {
                try {
                    detail = e.getResponse().readEntity(String.class);
                } catch (Exception ignored) {
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
     * Enriched metadata for a version extracted from CHANGELOG.md.
     */
    public record ChangelogEnrichment(String author, String timestamp) {}

    /**
     * Builds a version → enrichment map two ways: first by matching git tags, then by
     * walking {@code filename}'s own commit history and matching a commit to a version by date.
     */
    public java.util.Map<String, ChangelogEnrichment> enrichChangelogEntries(
            String project, String repo, String filename, List<EnrichmentCache.ChangelogEntry> entries) {
        if (entries.isEmpty()) return java.util.Map.of();

        java.util.Map<String, ChangelogEnrichment> result = new java.util.HashMap<>();
        matchByTag(project, repo, entries, result);
        matchByFileHistory(project, repo, filename, entries, result);

        LOG.info("Enriched " + result.size() + "/" + entries.size() + " changelog entries for " + project + "/" + repo);
        return result;
    }

    private void matchByTag(String project, String repo, List<EnrichmentCache.ChangelogEntry> entries, java.util.Map<String, ChangelogEnrichment> result) {
        List<GitRef> tags;
        try {
            tags = enrichmentCache.getTags(project, repo);
        } catch (Exception e) {
            LOG.warning("listRefs failed for " + project + "/" + repo + ": " + e);
            return;
        }
        if (tags == null || tags.isEmpty()) {
            return;
        }

        for (EnrichmentCache.ChangelogEntry entry : entries) {
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
                CommitResponse commit = commitFetcher.getCommitCached(project, repo, match.commitId());
                if (commit != null && commit.author() != null) {
                    result.put(ver, new ChangelogEnrichment(commit.author().name(), commit.author().date()));
                }
            } catch (Exception e) {
                LOG.warning("getCommit failed for tag " + match.tagName() + " (" + match.commitId() + "): " + e);
            }
        }
    }

    /**
     * Fallback for versions no tag matched: correlate by date against commits that touched the changelog file.
     */
    private void matchByFileHistory(
            String project, String repo, String filename, List<EnrichmentCache.ChangelogEntry> entries, java.util.Map<String, ChangelogEnrichment> result) {
        boolean anyUnmatched = entries.stream().anyMatch(e -> e.version() != null && !result.containsKey(e.version()));
        if (!anyUnmatched || filename == null) {
            return;
        }

        List<CommitResponse> commits;
        try {
            commits = commitFetcher.fetchCommitsForPath(project, repo, "/" + filename);
        } catch (Exception e) {
            LOG.warning("fetchCommitsForPath failed for " + project + "/" + repo + "/" + filename + ": " + e);
            return;
        }
        if (commits.isEmpty()) return;

        for (EnrichmentCache.ChangelogEntry entry : entries) {
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

    @Inject
    CommitFetcher commitFetcher;
}