package com.hubsabai.changelog.storage;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubsabai.changelog.core.model.ChangeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class RawReleaseService {

    @Inject
    Instance<com.hubsabai.changelog.auth.CurrentUser> currentUser;

    /**
     * The owner to stamp on rows this request creates, and the filter every read applies.
     * {@code null} means "service-owned" — which is also what a caller outside any request
     * context (a unit test, or a worker thread) gets, deliberately: a missing context must
     * never widen a read to someone else's rows.
     */
    private Long owner() {
        try {
            return currentUser.get().ownerUserId();
        } catch (RuntimeException noRequestContext) {
            return null;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Transactional
    public void ingest(String project, String repo, String branch, String version, String stage,
            String itemsJson, Set<Integer> prIds) {
        RawRelease entry = RawRelease.findEntry(project, repo, version, owner());
        if (entry != null) {
            entry.branch = branch;
            entry.stage = stage;
            entry.items = itemsJson;
            entry.updatedAt = OffsetDateTime.now();
            entry.persist();
        } else {
            RawRelease r = new RawRelease();
            r.ownerUserId = owner();
            r.project = project;
            r.repo = repo;
            r.branch = branch;
            r.version = version;
            r.stage = stage;
            r.items = itemsJson;
            r.createdAt = OffsetDateTime.now();
            r.updatedAt = OffsetDateTime.now();
            r.persist();
        }

        for (Integer prId : prIds) {
            upsertReleasePr(project, repo, prId, version, stage);
        }
    }

    private void upsertReleasePr(String project, String repo, int prId, String version, String stage) {
        ReleasePr existing = ReleasePr.findEntry(project, repo, prId, owner());
        if (existing != null) {
            if (!"release".equals(existing.stage)) {
                existing.version = version;
                existing.stage = stage;
                existing.updatedAt = OffsetDateTime.now();
                existing.persist();
            }
        } else {
            ReleasePr rp = new ReleasePr();
            rp.ownerUserId = owner();
            rp.project = project;
            rp.repo = repo;
            rp.prId = prId;
            rp.version = version;
            rp.stage = stage;
            rp.updatedAt = OffsetDateTime.now();
            rp.persist();
        }
    }

    @Transactional
    public Optional<ReleasePr> findLocation(String project, String repo, int prId) {
        return Optional.ofNullable(ReleasePr.findEntry(project, repo, prId, owner()));
    }

    @Transactional
    public List<ChangeItem> findItems(String project, String repo, String version) {
        RawRelease entry = RawRelease.findEntry(project, repo, version, owner());
        if (entry == null || entry.items == null || entry.items.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(entry.items, new TypeReference<List<ChangeItem>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
