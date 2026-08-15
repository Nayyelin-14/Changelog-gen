package com.hubsabai.changelog.util;

import java.util.regex.Pattern;

/**
 * Strict semantic version validation and manipulation utilities.
 *
 * <p>A valid release version follows the {@code MAJOR.MINOR.PATCH} format, for example:
 * <ul>
 *   <li>{@code 0.1.0}</li>
 *   <li>{@code 1.0.0}</li>
 *   <li>{@code 1.4.29}</li>
 *   <li>{@code 2.0.0}</li>
 * </ul>
 *
 * <p>The canonical stored value does not include a leading {@code v}. The display layer may prepend {@code v}
 * for presentation (e.g. {@code v1.4.30}).
 *
 * <p>Values such as {@code 9}, {@code v9}, {@code 1.4}, and prerelease versions like
 * {@code 1.4.30-beta.1} are rejected in this first implementation.
 */
public final class VersionUtils {

    private VersionUtils() {
    }

    /** Strict {@code MAJOR.MINOR.PATCH} pattern: three dot-separated numeric components without leading zeros. */
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$"
    );

    /** A simple integer such as {@code 9} or {@code 42}, optionally with a leading {@code v}. */
    private static final Pattern SIMPLE_INT_PATTERN = Pattern.compile("^[0-9]+$");
    private static final Pattern SIMPLE_INT_V_PREFIX_PATTERN = Pattern.compile("^v[0-9]+$");

    /**
     * Returns {@code true} if the given value is a valid strict semantic version ({@code MAJOR.MINOR.PATCH}),
     * without a leading {@code v} and without prerelease or build metadata.
     */
    public static boolean isValidReleaseVersion(String version) {
        return version != null && !version.isBlank() && SEMVER_PATTERN.matcher(version.trim()).matches();
    }

    /**
     * Returns the canonical normalized form of the version: trimmed of surrounding whitespace, without
     * a leading {@code v}. Returns {@code null} if the value is not a valid semantic version.
     */
    public static String normalizeVersion(String version) {
        if (version == null) return null;
        String trimmed = version.trim();
        if (trimmed.startsWith("v")) {
            trimmed = trimmed.substring(1).trim();
        }
        return isValidReleaseVersion(trimmed) ? trimmed : null;
    }

    /**
     * Increments the patch component of a valid semantic version.
     * For example, {@code 1.4.29} becomes {@code 1.4.30}.
     *
     * @param version the semantic version to increment
     * @return the incremented version
     * @throws IllegalArgumentException if {@code version} is not a valid semantic version
     */
    public static String incrementPatch(String version) {
        String normalized = normalizeVersion(version);
        if (normalized == null) {
            throw new IllegalArgumentException("Cannot increment patch: '" + version + "' is not a valid semantic version");
        }
        String[] parts = normalized.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        return major + "." + minor + "." + (patch + 1);
    }

    /**
     * Increments the minor component and resets patch to zero.
     * For example, {@code 1.4.29} becomes {@code 1.5.0}.
     *
     * @param version the semantic version to increment
     * @return the incremented version
     * @throws IllegalArgumentException if {@code version} is not a valid semantic version
     */
    public static String incrementMinor(String version) {
        String normalized = normalizeVersion(version);
        if (normalized == null) {
            throw new IllegalArgumentException("Cannot increment minor: '" + version + "' is not a valid semantic version");
        }
        String[] parts = normalized.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        return major + "." + (minor + 1) + ".0";
    }

    /**
     * Increments the major component and resets minor/patch to zero.
     * For example, {@code 1.4.29} becomes {@code 2.0.0}.
     *
     * @param version the semantic version to increment
     * @return the incremented version
     * @throws IllegalArgumentException if {@code version} is not a valid semantic version
     */
    public static String incrementMajor(String version) {
        String normalized = normalizeVersion(version);
        if (normalized == null) {
            throw new IllegalArgumentException("Cannot increment major: '" + version + "' is not a valid semantic version");
        }
        String[] parts = normalized.split("\\.");
        int major = Integer.parseInt(parts[0]);
        return (major + 1) + ".0.0";
    }

    /**
     * Classifies a version string as either a semantic release version or a pipeline run number.
     *
     * <p>Classification rules:
     * <ul>
     *   <li>If it matches strict {@code MAJOR.MINOR.PATCH} -> {@link Classification#RELEASE_VERSION}</li>
     *   <li>If it is a simple integer (e.g., {@code 9}, {@code 42}) or its leading-v form ({@code v9}) -> {@link Classification#PIPELINE_RUN_NUMBER}</li>
     *   <li>Otherwise -> {@link Classification#AMBIGUOUS}</li>
     * </ul>
     */
    public static Classification classifyVersion(String version) {
        if (version == null || version.isBlank()) {
            return Classification.AMBIGUOUS;
        }
        String trimmed = version.trim();
        if (isValidReleaseVersion(trimmed)) {
            return Classification.RELEASE_VERSION;
        }
        if (SIMPLE_INT_PATTERN.matcher(trimmed).matches() || SIMPLE_INT_V_PREFIX_PATTERN.matcher(trimmed).matches()) {
            return Classification.PIPELINE_RUN_NUMBER;
        }
        return Classification.AMBIGUOUS;
    }

    /**
     * Returns {@code true} if the given version looks like a pipeline run number (simple integer, optionally
     * with a leading {@code v}).
     */
    public static boolean isPipelineRunNumber(String version) {
        return version != null
                && (SIMPLE_INT_PATTERN.matcher(version.trim()).matches()
                    || SIMPLE_INT_V_PREFIX_PATTERN.matcher(version.trim()).matches());
    }

    /**
     * Extracts the numeric part of a pipeline run number, or {@code null} if it is not one.
     */
    public static String extractRunNumberValue(String version) {
        if (version == null) return null;
        String trimmed = version.trim();
        if (SIMPLE_INT_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (SIMPLE_INT_V_PREFIX_PATTERN.matcher(trimmed).matches()) {
            return trimmed.substring(1);
        }
        return null;
    }

    /**
     * Compares two semantic versions for ordering purposes.
     *
     * @return a negative integer, zero, or a positive integer as the first version is less than,
     *         equal to, or greater than the second.
     * @throws IllegalArgumentException if either version is not a valid semantic version
     */
    public static int compareVersions(String v1, String v2) {
        String n1 = normalizeVersion(v1);
        String n2 = normalizeVersion(v2);
        if (n1 == null || n2 == null) {
            throw new IllegalArgumentException("Cannot compare invalid versions: '" + v1 + "' and '" + v2 + "'");
        }
        String[] parts1 = n1.split("\\.");
        String[] parts2 = n2.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(parts1[i]), Integer.parseInt(parts2[i]));
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /**
     * Finds the latest (highest) semantic version from a list of versions.
     * Invalid versions are skipped. Returns {@code null} if no valid version is found.
     */
    public static String findLatestVersion(java.util.List<String> versions) {
        if (versions == null || versions.isEmpty()) return null;
        return versions.stream()
                .map(VersionUtils::normalizeVersion)
                .filter(java.util.Objects::nonNull)
                .max(VersionUtils::compareVersions)
                .orElse(null);
    }

    public enum Classification {
        /** A valid semantic version such as {@code 1.4.30}. */
        RELEASE_VERSION,
        /** A simple integer pipeline run number such as {@code 9} or {@code v9}. */
        PIPELINE_RUN_NUMBER,
        /** Ambiguous value that cannot be safely classified. */
        AMBIGUOUS
    }
}
