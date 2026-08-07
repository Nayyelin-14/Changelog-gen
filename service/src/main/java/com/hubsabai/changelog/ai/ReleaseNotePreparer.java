package com.hubsabai.changelog.ai;

import com.hubsabai.changelog.core.model.ChangeItem;
import com.hubsabai.changelog.core.model.PrReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI-agnostic preprocessing: classifies, normalizes, and maps {@link ChangeItem}
 * (the internal ingestion model) into {@link ReleaseNoteEntry}
 * (the AI-facing DTO). Deterministic — no LLM involvement.
 *
 * <p>Responsibility boundary:
 * <ul>
 *   <li>{@code ReleaseNotePreparer} — knows about change data only (never prompts)</li>
 *   <li>{@code PromptComposer} — knows about prompts only (renders entries into a prompt)</li>
 * </ul>
 */
public final class ReleaseNotePreparer {

    private static final Set<String> PLACEHOLDER_DESCRIPTIONS = Set.of("asdf", "tmp", "wip", "test", "n/a");
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile("^[.\\-?_!]{3,}$");

    private ReleaseNotePreparer() {}

    public static List<ReleaseNoteEntry> prepare(List<ChangeItem> items) {
        List<ReleaseNoteEntry> entries = new ArrayList<>(items.size());
        for (ChangeItem item : items) {
            entries.add(toEntry(item));
        }
        return entries;
    }

    static ReleaseNoteEntry toEntry(ChangeItem item) {
        String type = detectType(item.getFilePaths(), item.getCategory());
        String scope = detectScope(item.getFilePaths());
        String title = item.getTitle() != null ? item.getTitle() : "";
        String description = cleanDescription(item.getDescription());
        Integer prNumber = extractPrNumber(item);
        List<String> workItems = extractWorkItems(item);
        return new ReleaseNoteEntry(type, scope, title, description, prNumber, workItems);
    }

    /**
     * Determines type from file paths first (deterministic), falling back to the entry's
     * existing category. Path-based rules:
     * <ol>
     *   <li>Path under {@code .azure/} or {@code .github/} → {@code ci}</li>
     *   <li>{@code .md} file, or path under {@code docs/} → {@code docs}</li>
     *   <li>{@code pom.xml}, {@code build.gradle}, {@code gradle.properties},
     *       {@code package.json} dependency/version bump → {@code build}</li>
     *   <li>Path containing {@code test} or {@code spec} (any case) → {@code test}</li>
     * </ol>
     */
    static String detectType(List<String> filePaths, String existingCategory) {
        if (filePaths != null) {
            for (String path : filePaths) {
                if (path == null) continue;
                String lower = path.toLowerCase();
                if (lower.startsWith(".azure/") || lower.startsWith(".github/")) return "ci";
                if (lower.endsWith(".md") || lower.startsWith("docs/") || lower.contains("/docs/")) return "docs";
                if (lower.equals("pom.xml") || lower.equals("build.gradle")
                        || lower.equals("gradle.properties") || lower.equals("package.json")
                        || lower.contains("package-lock.json")) return "build";
                if (lower.contains("test") || lower.contains("spec")) return "test";
            }
        }
        return existingCategory != null ? existingCategory : "chore";
    }

    /**
     * Derives scope from file paths: the most specific shared module name among all paths,
     * ignoring generic segments (src, main, test, docs, modules, packages, apps, services, libraries).
     * Returns {@code null} if no meaningful scope can be determined.
     */
    static String detectScope(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return null;
        String shared = null;
        for (String path : filePaths) {
            if (path == null) continue;
            String scope = extractScopeSegment(path);
            if (scope == null) continue;
            if (shared == null) {
                shared = scope;
            } else if (!shared.equals(scope)) {
                shared = null;
                break;
            }
        }
        return shared;
    }

    private static String extractScopeSegment(String path) {
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");
        for (String seg : segments) {
            String lower = seg.toLowerCase();
            if (GENERIC_SEGMENTS.contains(lower)) continue;
            if (seg.endsWith(".java") || seg.endsWith(".kt") || seg.endsWith(".ts")
                    || seg.endsWith(".tsx") || seg.endsWith(".js") || seg.endsWith(".css")) {
                continue;
            }
            return seg;
        }
        return null;
    }

    private static final Set<String> GENERIC_SEGMENTS = Set.of(
            "src", "main", "test", "tests", "docs", "documentation",
            "modules", "packages", "apps", "services", "libraries",
            "resources", "assets", "config", "conf", "scripts"
    );

    /** Removes empty, placeholder, or garbage descriptions. */
    static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.length() < 3) return null;
        if (PLACEHOLDER_DESCRIPTIONS.contains(trimmed.toLowerCase())) return null;
        if (REPEATED_PUNCTUATION.matcher(trimmed).matches()) return null;
        return trimmed;
    }

    private static Integer extractPrNumber(ChangeItem item) {
        if (item.getType() == ChangeItem.ItemType.PULL_REQUEST && item.getId() != null) {
            try {
                return Integer.parseInt(item.getId());
            } catch (NumberFormatException e) {
                // fall through to text extraction
            }
        }
        String prId = PrReference.extractId(item.getTitle());
        if (prId != null) {
            try {
                return Integer.parseInt(prId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> extractWorkItems(ChangeItem item) {
        if (item.getType() == ChangeItem.ItemType.WORK_ITEM && item.getId() != null) {
            return List.of(item.getId());
        }
        return List.of();
    }
}
