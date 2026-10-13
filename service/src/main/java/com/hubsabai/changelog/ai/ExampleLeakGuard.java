package com.hubsabai.changelog.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic safety net against few-shot example leakage.
 *
 * <p>{@link PromptComposer} teaches the required output shape with example inputs and example
 * bullets. Small models regularly copy those example bullets straight into a real answer even
 * when the release being written has nothing to do with them — a session-refresh fix appearing in
 * a changelog whose commits are all CI workflow changes, for instance. The system prompt now
 * forbids that explicitly, but a prompt is a request, not a guarantee: this class enforces the
 * rule mechanically on whatever the model actually returned.
 *
 * <p>A bullet is dropped only when BOTH hold:
 * <ol>
 *   <li>it is a near-verbatim copy of one of the {@link PromptComposer#EXAMPLE_OUTPUTS}, and</li>
 *   <li>no change in the real input supports it (its words do not describe any supplied title).</li>
 * </ol>
 * So a bullet that merely looks like an example but is backed by an actual commit survives, and a
 * bullet that has nothing to do with any example is never touched. Comparison is deliberately
 * conservative — exact/very-close copies only — because the cost of wrongly deleting a legitimate
 * change is far higher than the cost of letting a paraphrased leak through.
 */
public final class ExampleLeakGuard {

    /** Bullets shorter than this (normalized) are never considered for removal: the example
     *  bullets are all long, and a short generic bullet could otherwise collide by chance. */
    private static final int MIN_BULLET_CHARS = 20;

    /** Token recall an input title must reach inside a bullet before that bullet counts as
     *  "supported by the real input". */
    private static final double SUPPORT_RECALL = 0.6;

    /** Minimum number of input-title tokens that must appear in the bullet — a one- or two-word
     *  title (e.g. "fix login") must not certify an unrelated bullet on its own. */
    private static final int MIN_SUPPORT_MATCHES = 2;

    /** Token Jaccard similarity at which two strings count as the same text. */
    private static final double NEAR_COPY_JACCARD = 0.8;

    /** Prefix rule only applies to strings at least this long — a short shared prefix like
     *  "auth" is far too generic to establish that two sentences are the same text. */
    private static final int MIN_PREFIX_CHARS = 20;

    private ExampleLeakGuard() {}

    /** Returns {@code text} with example bullets that the real input does not support removed. */
    public static String stripUnsupportedExamples(String text, List<ReleaseNoteEntry> input) {
        if (text == null || text.isBlank() || !looksLikeBullet(text)) {
            return text;
        }
        List<ReleaseNoteEntry> entries = input != null ? input : List.of();

        List<String> lines = List.of(text.split("\n", -1));
        List<Block> blocks = group(lines.toArray(new String[0]));

        boolean removedAny = false;
        List<Block> kept = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            if (block.kind == Kind.BULLET && isUnsupportedExample(block.joined(), entries)) {
                removedAny = true;
                continue;
            }
            kept.add(block);
        }
        if (!removedAny) return text;

        kept = dropOrphanHeadings(kept);
        StringBuilder sb = new StringBuilder();
        for (Block block : kept) {
            for (String line : block.lines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private enum Kind { BLANK, HEADING, BULLET, TEXT }

    private record Block(Kind kind, List<String> lines, String joined) {}

    private static boolean looksLikeBullet(String text) {
        for (String line : text.split("\n", -1)) {
            if (isBulletLine(line)) return true;
        }
        return false;
    }

    private static List<Block> group(String[] lines) {
        List<Block> blocks = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isBulletLine(line)) {
                List<String> own = new ArrayList<>();
                StringBuilder joined = new StringBuilder(line.strip());
                own.add(line);
                while (i + 1 < lines.length && isContinuation(lines[i + 1])) {
                    i++;
                    own.add(lines[i]);
                    joined.append(' ').append(lines[i].strip());
                }
                blocks.add(new Block(Kind.BULLET, List.copyOf(own), joined.toString()));
            } else if (line.isBlank()) {
                blocks.add(new Block(Kind.BLANK, List.of(line), ""));
            } else if (isHeading(line)) {
                blocks.add(new Block(Kind.HEADING, List.of(line), line.strip()));
            } else {
                blocks.add(new Block(Kind.TEXT, List.of(line), line.strip()));
            }
        }
        return blocks;
    }

    private static boolean isBulletLine(String line) {
        String stripped = line.strip();
        if (stripped.length() < 2) return false;
        char c = stripped.charAt(0);
        if (c != '-' && c != '*' && c != '\u2022') return false;
        return stripped.length() > 1 && Character.isWhitespace(stripped.charAt(1));
    }

    /** A wrapped bullet continues on the next line only when that line is indented and is not
     *  itself a bullet or a heading. */
    private static boolean isContinuation(String line) {
        if (line.isBlank()) return false;
        if (line.charAt(0) != ' ' && line.charAt(0) != '\t') return false;
        return !isBulletLine(line) && !isHeading(line);
    }

    private static boolean isHeading(String line) {
        String stripped = line.strip();
        if (!stripped.startsWith("#")) return false;
        // "## What's New" style; also accept an underlined ATX-lookalike only when #-prefixed.
        return stripped.length() > 1;
    }

    private static boolean isUnsupportedExample(String bullet, List<ReleaseNoteEntry> entries) {
        String normalized = normalize(bullet);
        if (normalized.length() < MIN_BULLET_CHARS) return false;
        for (String example : PromptComposer.EXAMPLE_OUTPUTS) {
            if (!isNearCopy(normalized, normalize(example))) continue;
            return !supportedByInput(normalized, entries);
        }
        return false;
    }

    private static boolean isNearCopy(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) >= MIN_PREFIX_CHARS
                && (a.startsWith(b) || b.startsWith(a))) {
            return true;
        }
        return jaccard(tokens(a), tokens(b)) >= NEAR_COPY_JACCARD;
    }

    private static boolean supportedByInput(String bulletNormalized, List<ReleaseNoteEntry> entries) {
        Set<String> bulletTokens = tokens(bulletNormalized);
        if (bulletTokens.isEmpty()) return false;
        for (ReleaseNoteEntry entry : entries) {
            Set<String> titleTokens = tokens(normalize(entry.title()));
            if (titleTokens.isEmpty()) continue;
            int matched = 0;
            for (String titleToken : titleTokens) {
                if (containsToken(bulletTokens, titleToken)) matched++;
            }
            double recall = (double) matched / titleTokens.size();
            if (matched >= MIN_SUPPORT_MATCHES && recall >= SUPPORT_RECALL) return true;
        }
        return false;
    }

    private static boolean containsToken(Set<String> haystack, String needle) {
        for (String candidate : haystack) {
            if (tokenEquals(candidate, needle)) return true;
        }
        return false;
    }

    private static boolean tokenEquals(String a, String b) {
        if (a.equals(b)) return true;
        // Light stemming: "prevent"/"prevented", "upload"/"uploads" must still match, but only
        // once the shared prefix is long enough to be meaningful.
        int min = Math.min(a.length(), b.length());
        if (min < 4) return false;
        return a.startsWith(b) || b.startsWith(a);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int shared = 0;
        for (String left : a) {
            for (String right : b) {
                if (tokenEquals(left, right)) {
                    shared++;
                    break;
                }
            }
        }
        int union = a.size() + b.size() - shared;
        return union == 0 ? 1.0 : (double) shared / union;
    }

    private static Set<String> tokens(String normalized) {
        if (normalized.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    /** Lowercase, drop Markdown emphasis, collapse every run of non-alphanumerics to one space. */
    static String normalize(String raw) {
        if (raw == null) return "";
        String lowered = raw.toLowerCase(Locale.ROOT)
                .replace("**", "")
                .replace('`', ' ')
                .replace('*', ' ')
                .replace('_', ' ');
        String collapsed = lowered.replaceAll("[^a-z0-9]+", " ");
        return collapsed.trim().replaceAll("\\s+", " ");
    }

    /** Removes a heading that ended up with no bullets under it because its bullets were dropped
     *  — a dangling "## Fixes & Improvements" with nothing below it is worse than no heading. */
    private static List<Block> dropOrphanHeadings(List<Block> blocks) {
        boolean[] keep = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) keep[i] = true;

        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).kind != Kind.HEADING || !keep[i]) continue;
            boolean hasBullet = false;
            for (int j = i + 1; j < blocks.size() && blocks.get(j).kind != Kind.HEADING; j++) {
                if (blocks.get(j).kind == Kind.BULLET && keep[j]) {
                    hasBullet = true;
                    break;
                }
            }
            if (!hasBullet) keep[i] = false;
        }

        List<Block> out = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            if (keep[i]) out.add(blocks.get(i));
        }
        return out;
    }
}
