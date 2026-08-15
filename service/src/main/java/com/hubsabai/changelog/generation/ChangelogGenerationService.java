package com.hubsabai.changelog.generation;

import com.hubsabai.changelog.ai.AiProvider;
import com.hubsabai.changelog.ai.AiResult;
import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.ReleaseData;
import com.hubsabai.changelog.storage.ChangelogCacheService;
import com.hubsabai.changelog.storage.ChangelogService;
import com.hubsabai.changelog.storage.RawReleaseService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single, provider-independent AI changelog pipeline. Everything downstream of "what changed?"
 * lives here: hashing, cache lookup, the dev→qa→business audience chain, prior-context injection,
 * persistence, and the fallback data builders. Both REST resources delegate to it so the GitHub and
 * Azure DevOps paths never drift.
 */
@ApplicationScoped
public class ChangelogGenerationService {

    @Inject
    AiProvider aiProvider;

    @Inject
    ChangelogCacheService cacheService;

    @Inject
    ChangelogService changelogService;

    @Inject
    RawReleaseService rawReleaseService;

    /** Generate one audience, honoring the cache unless {@code force}. Persists when {@code commit}. */
    public AiResult ensureAudience(String project, String repo, String version, String audience,
            String model, boolean strict, ReleaseData data, String inputHash, boolean force, boolean commit,
            Map<String, AiResult> computed) {
        if (computed.containsKey(audience)) {
            return computed.get(audience);
        }
        if (!force) {
            Optional<String> current = cacheService.getCurrent(project, repo, version, audience, inputHash);
            if (current.isPresent()) {
                AiResult cached = new AiResult(current.get(), null);
                computed.put(audience, cached);
                return cached;
            }
        }
        List<ChangeItem> items = data.getItems();
        if (!"developer".equals(audience)) {
            AiResult developer = ensureAudience(project, repo, version, "developer", model, strict, data, inputHash, false, commit, computed);
            items = withContext(items, project, repo, "developer", developer.getText());
        }
        if ("business".equals(audience)) {
            AiResult qa = ensureAudience(project, repo, version, "qa", model, strict, data, inputHash, false, commit, computed);
            items = withContext(items, project, repo, "qa", qa.getText());
        }
        AiResult result = strict
                ? aiProvider.generateForAudienceStrict(items, data.getRelease(), audience, model)
                : aiProvider.generateForAudience(items, data.getRelease(), audience, model, null);
        if (commit) {
            cacheService.put(project, repo, version, audience, strict ? model : "auto", result.getText(), inputHash);
            long vid = changelogService.getOrCreateVersion(project, repo, version, null, null, null, null, null).id;
            changelogService.createSnapshot(vid, audience, result.getText(), "ai", strict ? model : "auto",
                    result.getUsage() != null ? result.getUsage().getTotalTokens() : 0, 0, null);
        }
        computed.put(audience, result);
        return result;
    }

    /** Whether the current cached entry for (version, audience, hash) exists — for "wasCached" flags. */
    public boolean isCached(String project, String repo, String version, String audience, String inputHash) {
        return cacheService.getCurrent(project, repo, version, audience, inputHash).isPresent();
    }

    /** All three audiences, sharing one computed-results map so qa/business reuse prior text. */
    public AiResult[] generateAll(String project, String repo, String version, String model,
            ReleaseData data, String inputHash, boolean force) {
        Map<String, AiResult> computed = new HashMap<>();
        AiResult developer = ensureAudience(project, repo, version, "developer", model, true, data, inputHash, false, true, computed);
        AiResult qa = ensureAudience(project, repo, version, "qa", model, true, data, inputHash, false, true, computed);
        AiResult business = ensureAudience(project, repo, version, "business", model, true, data, inputHash, false, true, computed);
        return new AiResult[]{developer, qa, business};
    }

    /** Rediscover data for a version through the shared fallback chain. Provider-specific data
     * sources are supplied via {@code source}. */
    public ReleaseData resolveData(String project, String repo, String branch, String version,
            String fromVersion, String manualText, String org, ChangelogDataSource source) {
        boolean hasManualText = manualText != null && !manualText.isBlank();
        ReleaseData data;
        if (hasManualText) {
            data = fromManual(org, project, repo, branch, version, manualText);
        } else {
            data = source.fetchRepoChanges(fromVersion, version, branch);
            if (data.getItems().isEmpty()) {
                data = fromRaw(org, project, repo, branch, version);
            }
        }
        if (data.getItems().isEmpty()) {
            data = fromChangelog(org, project, repo, branch, version, source);
        }
        if (data.getItems().isEmpty()) {
            data = fromSavedDeveloperText(org, project, repo, branch, version);
        }
        return data;
    }

    // ---- fallback data builders (shared) ----

    private ReleaseData fromRaw(String org, String project, String repo, String branch, String version) {
        List<ChangeItem> items = rawReleaseService.findItems(project, repo, version);
        return wrap(org, project, repo, branch, items);
    }

    private ReleaseData fromChangelog(String org, String project, String repo, String branch, String version,
            ChangelogDataSource source) {
        String body = source.fetchChangelogBody(version);
        if (body == null || body.isBlank()) {
            return empty(org, project, repo, branch);
        }
        return fromChangelogBody(org, project, repo, branch, body);
    }

    private ReleaseData fromSavedDeveloperText(String org, String project, String repo, String branch, String version) {
        String body = cacheService.getCurrentText(project, repo, version, "developer").orElse(null);
        if (body == null || body.isBlank()) {
            return empty(org, project, repo, branch);
        }
        return fromChangelogBody(org, project, repo, branch, body);
    }

    private static ReleaseData fromChangelogBody(String org, String project, String repo, String branch, String body) {
        List<ChangeItem> items = new ArrayList<>();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String bullet = line.replaceFirst("^[-*]\\s+", "");
            items.add(changeItem(project, repo, bullet, "", List.of()));
        }
        return wrap(org, project, repo, branch, items);
    }

    private static ReleaseData empty(String org, String project, String repo, String branch) {
        return wrap(org, project, repo, branch, List.of());
    }

    private static ReleaseData wrap(String org, String project, String repo, String branch, List<ChangeItem> items) {
        ReleaseData data = new ReleaseData();
        ReleaseData.ReleaseMeta meta = new ReleaseData.ReleaseMeta();
        meta.setOrg(org);
        meta.setProject(project);
        meta.setRepo(repo);
        meta.setBranch(branch);
        meta.setReleaseDate(LocalDate.now().toString());
        data.setRelease(meta);
        data.setItems(items);
        return data;
    }

    /** Parse the flat block format used for manual/pipeline input. */
    public static ReleaseData fromManual(String org, String project, String repo, String branch, String version, String text) {
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
                    items.add(changeItem(project, repo, currentTitle, currentBody.toString(), currentFiles,
                            currentType, currentId, currentAuthor, currentCategory));
                }
                String rest = trimmed.substring(4).strip();
                if (rest.startsWith("[") && rest.contains("] ")) {
                    int closeBracket = rest.indexOf("] ");
                    String meta = rest.substring(1, closeBracket);
                    if (meta.contains("|")) {
                        String[] parts = meta.split("\\|", -1);
                        currentType = parseType(parts[0]);
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
            items.add(changeItem(project, repo, currentTitle, currentBody.toString(), currentFiles,
                    currentType, currentId, currentAuthor, currentCategory));
        }
        return wrap(org, project, repo, branch, items);
    }

    private static ChangeItem.ItemType parseType(String s) {
        try {
            return ChangeItem.ItemType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ChangeItem.ItemType.COMMIT;
        }
    }

    /** Publishes a prior audience under a label that tells the AI to treat it as source of truth. */
    public static List<ChangeItem> withContext(List<ChangeItem> items, String project, String repo,
            String priorAudience, String priorText) {
        String label = "developer".equals(priorAudience)
                ? "ALREADY-PUBLISHED developer changelog — use as source of truth, do not repeat verbatim"
                : "ALREADY-GENERATED " + priorAudience + " changelog — build on this, do not repeat verbatim";
        List<ChangeItem> withPrior = new ArrayList<>(items.size() + 1);
        withPrior.add(changeItem(project, repo, label, priorText, List.of()));
        withPrior.addAll(items);
        return withPrior;
    }

    public static ChangeItem changeItem(String project, String repo, String title, String body, List<String> filePaths) {
        return changeItem(project, repo, title, body, filePaths,
                ChangeItem.ItemType.COMMIT, null, null, null);
    }

    public static ChangeItem changeItem(String project, String repo, String title, String body, List<String> filePaths,
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
}