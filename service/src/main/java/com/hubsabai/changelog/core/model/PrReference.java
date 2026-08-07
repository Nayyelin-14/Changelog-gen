package com.hubsabai.changelog.core.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the PR number a commit message references, when the commit is itself a PR merge. */
public final class PrReference {

    private static final Pattern PR_PATTERN = Pattern.compile(
        "(?:Merged PR|Merge pull request) \\!?(\\d+)|PR \\!?(\\d+)",
        Pattern.CASE_INSENSITIVE
    );

    private PrReference() {}

    public static String extractId(String commitMessage) {
        if (commitMessage == null) return null;
        Matcher m = PR_PATTERN.matcher(commitMessage);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) return m.group(i);
            }
        }
        return null;
    }
}
