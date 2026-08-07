package com.hubsabai.changelog.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.core.model.ChangeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChangelogService {

    private static final Logger LOG = Logger.getLogger(ChangelogService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager entityManager;

    // ----- Version CRUD -----

    @Transactional
    public ChangelogVersion getOrCreateVersion(String project, String repo, String version,
                                                Integer buildId, String buildNumber,
                                                String stage, String branch) {
        ChangelogVersion existing = ChangelogVersion.findEntry(project, repo, version);
        if (existing != null) {
            if (buildId != null) existing.buildId = buildId;
            if (buildNumber != null) existing.buildNumber = buildNumber;
            if (stage != null) existing.stage = stage;
            if (branch != null) existing.branch = branch;
            existing.persist();
            return existing;
        }
        ChangelogVersion v = new ChangelogVersion();
        v.project = project;
        v.repo = repo;
        v.version = version;
        v.buildId = buildId;
        v.buildNumber = buildNumber;
        v.stage = stage;
        v.branch = branch;
        v.createdAt = OffsetDateTime.now();
        v.persist();
        return v;
    }

    @Transactional
    public ChangelogVersion findOrCreate(String project, String repo, String version) {
        return getOrCreateVersion(project, repo, version, null, null, null, null);
    }

    @Transactional
    public void updateRawItems(long versionId, String rawItemsJson) {
        ChangelogVersion v = ChangelogVersion.findById(versionId);
        if (v != null) {
            v.rawItems = rawItemsJson;
            v.persist();
        }
    }

    // ----- Revisions -----

    @Transactional
    public ChangelogRevision addRevision(long versionId, String audience, int sequence,
                                          String text, String source, String model,
                                          Integer tokens, Integer durationMs, String editedBy) {
        ChangelogRevision r = new ChangelogRevision();
        r.version = ChangelogVersion.findById(versionId);
        r.audience = audience;
        r.sequence = sequence;
        r.text = text;
        r.source = source;
        r.model = model;
        r.tokens = tokens;
        r.durationMs = durationMs;
        r.editedBy = editedBy;
        r.createdAt = OffsetDateTime.now();
        r.persist();
        return r;
    }

    @Transactional
    public ChangelogRevision createRawRevision(long versionId, String audience, String text) {
        int seq = ChangelogRevision.nextSequence(versionId, audience);
        return addRevision(versionId, audience, seq, text, "raw", null, null, null, null);
    }

    private static final List<String> AUDIENCES = List.of("developer", "qa", "business");

    /**
     * Appends one shared revision for this version: the audience actually being changed gets the
     * new text, and the other two carry their own latest text forward unchanged (byte-for-byte,
     * including source/model/editedBy) at the SAME sequence number. A "revision" is therefore
     * always a complete snapshot of all three views at a point in time, not one audience's own
     * independent history — see the left history panel, which shows one shared timeline. An
     * audience with nothing yet simply gets no row here either; there's nothing to carry forward.
     */
    @Transactional
    public ChangelogRevision createSnapshot(long versionId, String changedAudience, String text, String source,
            String model, Integer tokens, Integer durationMs, String editedBy) {
        int seq = nextSharedSequence(versionId);
        ChangelogRevision changed = addRevision(versionId, changedAudience, seq, text, source, model, tokens, durationMs, editedBy);
        for (String audience : AUDIENCES) {
            if (audience.equals(changedAudience)) continue;
            ChangelogRevision latest = ChangelogRevision.latest(versionId, audience);
            if (latest != null) {
                addRevision(versionId, audience, seq, latest.text, latest.source, latest.model, latest.tokens, latest.durationMs, latest.editedBy);
            }
        }
        return changed;
    }

    /** The next sequence number for this version, shared across all three audiences — so every
     * revision row, whichever audience it belongs to, lines up under one common timeline. */
    private int nextSharedSequence(long versionId) {
        ChangelogRevision last = ChangelogRevision.find("version.id = ?1 order by sequence desc", versionId).firstResult();
        return last != null ? last.sequence + 1 : 0;
    }

    // ----- Delete revision -----

    /**
     * Deletes one shared revision — whichever of the three audiences actually has a row at this
     * sequence. Renumbering shifts EVERY audience's later rows down together, not just the ones
     * that had something deleted here: sequence numbers are shared across all three (see {@link
     * #createSnapshot}), so an audience that simply had nothing at this exact sequence must still
     * have its later rows shift down in lockstep, or its numbering would drift out of alignment
     * with the other two.
     */
    @Transactional
    public void deleteSharedRevision(long versionId, int sequence) {
        for (String audience : AUDIENCES) {
            ChangelogRevision rev = ChangelogRevision.find(
                    "version.id = ?1 and audience = ?2 and sequence = ?3",
                    versionId, audience, sequence).firstResult();
            if (rev == null) continue;
            rev.delete();
            syncCurrentAfterDelete(versionId, audience, rev.version.project, rev.version.repo, rev.version.version);
        }

        List<ChangelogRevision> toRenumber = ChangelogRevision.find(
                "version.id = ?1 and sequence > ?2 order by sequence asc",
                versionId, sequence).list();
        for (ChangelogRevision r : toRenumber) {
            r.sequence = r.sequence - 1;
            r.persist();
        }
    }

    /** Re-derives generated_changelog's current/previous slots for one audience after a revision
     * of its own was just deleted — the dual-write's own bookkeeping, kept in sync so callers
     * still reading through the old current/previous fields (see ChangelogCacheService) don't see
     * a row that no longer exists in the revision log. */
    private void syncCurrentAfterDelete(long versionId, String audience, String project, String repo, String version) {
        ChangelogRevision latest = ChangelogRevision.latest(versionId, audience);
        GeneratedChangelog entry = GeneratedChangelog.findEntry(project, repo, version, audience);
        if (entry == null) return;
        if (latest != null) {
            entry.currentText = latest.text;
            entry.currentSource = latest.source;
            entry.currentModelId = latest.model;
            entry.currentEditedBy = latest.editedBy;
            entry.currentAt = latest.createdAt;
        } else {
            entry.currentText = null;
            entry.currentSource = null;
            entry.currentModelId = null;
            entry.currentEditedBy = null;
            entry.currentAt = null;
        }
        List<ChangelogRevision> all = ChangelogRevision.findByVersionAndAudience(versionId, audience);
        if (all.size() >= 2) {
            ChangelogRevision prev = all.get(all.size() - 2);
            entry.previousText = prev.text;
            entry.previousSource = prev.source;
            entry.previousModelId = prev.model;
            entry.previousEditedBy = prev.editedBy;
            entry.previousAt = prev.createdAt;
        } else {
            entry.previousText = null;
            entry.previousSource = null;
            entry.previousModelId = null;
            entry.previousEditedBy = null;
            entry.previousAt = null;
        }
        entry.persist();
    }

    // ----- Queries -----

    public String getLatestText(long versionId, String audience) {
        ChangelogRevision r = ChangelogRevision.latest(versionId, audience);
        return r != null ? r.text : null;
    }

    public ChangelogRevision getLatestRevision(long versionId, String audience) {
        return ChangelogRevision.latest(versionId, audience);
    }

    public List<ChangelogRevision> getRevisions(long versionId, String audience) {
        return ChangelogRevision.findByVersionAndAudience(versionId, audience);
    }

    /** Excludes versions with zero revisions in any audience — a row can end up like that after
     * every one of its revisions was deleted (see {@link #deleteSharedRevision}), and listing it
     * would surface a "generated" entry with nothing behind it (null text, everywhere). */
    public List<ChangelogVersion> listVersions(String project, String repo, int page, int limit) {
        List<ChangelogVersion> all = ChangelogVersion.find(
                "project = ?1 and repo = ?2 and id in (select distinct r.version.id from ChangelogRevision r) "
                        + "order by createdAt desc",
                project, repo).list();
        int from = page * limit;
        if (from >= all.size()) return List.of();
        int to = Math.min(from + limit, all.size());
        return all.subList(from, to);
    }

    public long countVersions(String project, String repo) {
        return ChangelogVersion.countByRepo(project, repo);
    }

    // Used by changelog-meta endpoint to return what we know about a version+audience.
    public Map<String, Object> getMeta(long versionId, String audience) {
        ChangelogRevision latest = ChangelogRevision.latest(versionId, audience);
        if (latest == null) return Map.of();
        List<ChangelogRevision> all = ChangelogRevision.findByVersionAndAudience(versionId, audience);
        boolean hasPrevious = all.size() > 1;
        ChangelogRevision previous = hasPrevious ? all.get(all.size() - 2) : null;
        ChangelogVersion v = ChangelogVersion.findById(versionId);
        boolean hasUnpushed = v != null && v.pushedCommitUrl == null && latest.source.equals("edit");

        java.util.HashMap<String, Object> meta = new java.util.HashMap<>();
        meta.put("source", latest.source);
        meta.put("model", latest.model != null ? latest.model : "");
        meta.put("editedBy", latest.editedBy != null ? latest.editedBy : "");
        meta.put("at", latest.createdAt != null ? latest.createdAt.toString() : "");
        meta.put("hasPrevious", hasPrevious);
        meta.put("previousText", previous != null ? previous.text : "");
        meta.put("previousSource", previous != null ? previous.source : "");
        meta.put("previousModel", previous != null ? previous.model : "");
        meta.put("previousEditedBy", previous != null ? previous.editedBy : "");
        meta.put("previousAt", previous != null && previous.createdAt != null ? previous.createdAt.toString() : "");
        meta.put("hasUnpushedChanges", hasUnpushed);
        meta.put("pushedAt", v != null && v.pushedAt != null ? v.pushedAt.toString() : "");
        meta.put("pushedPullRequestUrl", v != null && v.pushedCommitUrl != null ? v.pushedCommitUrl : "");
        meta.put("pushedText", "");
        return meta;
    }

    // ----- Raw items (pipeline data) -----

    public List<ChangeItem> findItems(String project, String repo, String version) {
        ChangelogVersion v = ChangelogVersion.findEntry(project, repo, version);
        if (v == null || v.rawItems == null || v.rawItems.isBlank()) return List.of();
        try {
            return MAPPER.readValue(v.rawItems, new TypeReference<List<ChangeItem>>() {});
        } catch (Exception e) {
            LOG.warning("Failed to deserialize raw items for " + project + "/" + repo + "/" + version + ": " + e);
            return List.of();
        }
    }

    public List<String> findAuthors(String project, String repo, String version) {
        return findItems(project, repo, version).stream()
                .map(ChangeItem::getAuthor)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    // ----- Pipeline ingest helpers -----

    @Transactional
    public void ingestRelease(String project, String repo, String branch, String version,
                               String stage, String itemsJson, Set<Integer> prIds) {
        ChangelogVersion cv = getOrCreateVersion(project, repo, version, null, null, stage, branch);
        cv.rawItems = itemsJson;
        cv.persist();

        for (int prId : prIds) {
            upsertReleasePr(project, repo, prId, version, stage);
        }
    }

    @Transactional
    public void upsertReleasePr(String project, String repo, int prId, String version, String stage) {
        ReleasePr existing = ReleasePr.findEntry(project, repo, prId);
        if (existing != null) {
            if ("release".equals(stage)) {
                existing.version = version;
                existing.stage = stage;
                existing.updatedAt = OffsetDateTime.now();
                existing.persist();
            }
            return;
        }
        ReleasePr rp = new ReleasePr();
        rp.project = project;
        rp.repo = repo;
        rp.prId = prId;
        rp.version = version;
        rp.stage = stage;
        rp.updatedAt = OffsetDateTime.now();
        rp.persist();
    }

    public Optional<ReleasePr> findReleasePr(String project, String repo, int prId) {
        return Optional.ofNullable(ReleasePr.findEntry(project, repo, prId));
    }

    // ----- Push tracking -----

    @Transactional
    public void markPushed(long versionId, String commitUrl) {
        ChangelogVersion v = ChangelogVersion.findById(versionId);
        if (v != null) {
            v.pushedAt = OffsetDateTime.now();
            v.pushedCommitUrl = commitUrl;
            v.persist();
        }
    }

    // ----- Save raw init (used by pipeline resource, only if no revision exists yet) -----

    @Transactional
    public boolean saveRawInitIfAbsent(String project, String repo, String version,
                                        String audience, String text) {
        ChangelogVersion cv = findOrCreate(project, repo, version);
        ChangelogRevision existing = ChangelogRevision.latest(cv.id, audience);
        if (existing != null) return false;
        createRawRevision(cv.id, audience, text);
        return true;
    }

    /**
     * Find or create a version row from a raw item list (no pipeline metadata needed).
     * Used by {@code generateRaw()} (PR-only raw init).
     */
    @Transactional
    public ChangelogVersion ensureVersion(String project, String repo, String version) {
        return findOrCreate(project, repo, version);
    }
}
