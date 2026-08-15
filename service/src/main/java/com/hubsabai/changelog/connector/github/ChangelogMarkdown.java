package com.hubsabai.changelog.connector.github;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-agnostic CHANGELOG.md parsing/mutation — the same "Keep a Changelog" + bracketless
 * conventions the Azure connector handles, factored out so the GitHub connector shares one copy
 * instead of each owning a divergent re-implementation. {@code ChangelogEntry} mirrors the Azure
 * connector's record by the same name.
 */
public final class ChangelogMarkdown {

    private ChangelogMarkdown() {}

    public record ChangelogEntry(String version, String date, String body) {}

    public record ChangelogFile(String filename, String content) {}

    public static final List<String> CHANGELOG_FILENAMES = List.of("CHANGELOG.md", "changelog.md");

    private static final Pattern CHANGELOG_VERSION_HEADER = Pattern.compile(
            "^## \\[([^\\]]+)\\]\\s*[-–—]\\s*(.+)$|^## v?([0-9][\\w.-]*)\\s*[-–—]\\s*(.+)$",
            Pattern.MULTILINE
    );

    /** Normalizes a date string to YYYY-MM-DD. */
    public static String normalizeDate(String date) {
        if (date == null) return null;
        String trimmed = date.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) return trimmed;
        Matcher m = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matcher(trimmed);
        if (m.find()) {
            return String.format("%04d-%02d-%02d", Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return trimmed;
    }

    public static List<ChangelogEntry> parseChangelogEntries(String markdown) {
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

    private static String extractBody(String markdown, int headerStart, int nextHeaderStart) {
        int afterNewline = markdown.indexOf('\n', headerStart);
        if (afterNewline < 0 || afterNewline >= nextHeaderStart) {
            return "";
        }
        int bodyStart = afterNewline + 1;
        return bodyStart < nextHeaderStart ? markdown.substring(bodyStart, nextHeaderStart).strip() : "";
    }

    /** Replaces one version's entry body, preserving surrounding whitespace. Empty if no header
     * matches {@code version}. */
    public static Optional<String> replaceChangelogEntryBody(String markdown, String version, String newBody) {
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

    /** Prepends a brand-new {@code ## vX — date} section ahead of existing content, newest-first. */
    public static String insertNewChangelogEntry(String markdown, String version, String body) {
        String header = "## v" + version + " — " + LocalDate.now();
        String entry = header + "\n\n" + body.strip() + "\n\n";
        return markdown == null || markdown.isBlank() ? entry : entry + markdown;
    }

    public static final Pattern VALID_NEW_VERSION = Pattern.compile("^[0-9][\\w.-]*$");
}