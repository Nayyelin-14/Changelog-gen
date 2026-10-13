package com.hubsabai.changelog.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Removes every row one user owns. Used only by "delete my account" — never by "disconnect GitHub",
 * which deliberately leaves the user's changelogs alone.
 *
 * <p>Ownership is the {@code owner_user_id} column on each table (see {@code V20__add_owner_user_id}).
 * Rows with a null owner belong to the service/CI and are never touched here, so deleting one
 * person's account can't take the pipeline's data with it.
 */
@ApplicationScoped
public class OwnedDataCleanupService {

    @Inject
    jakarta.persistence.EntityManager entityManager;

    /**
     * Deletes every row owned by {@code userId} and returns how many rows went away. Children are
     * removed before their parents even though the FKs cascade, so the count is honest and the
     * order doesn't depend on cascade behaviour.
     */
    @Transactional
    public int deleteAllOwnedBy(Long userId) {
        if (userId == null) {
            return 0;
        }
        int deleted = 0;
        deleted += ChangelogRevision.delete("ownerUserId = ?1", userId);
        deleted += ChangelogVersion.delete("ownerUserId = ?1", userId);
        deleted += GeneratedChangelog.delete("ownerUserId = ?1", userId);
        deleted += RawRelease.delete("ownerUserId = ?1", userId);
        deleted += ReleasePr.delete("ownerUserId = ?1", userId);
        deleted += RecordedRunDraft.delete("ownerUserId = ?1", userId);
        deleted += RecordedPipelineRun.delete("ownerUserId = ?1", userId);
        entityManager.flush();
        entityManager.clear();
        return deleted;
    }
}
