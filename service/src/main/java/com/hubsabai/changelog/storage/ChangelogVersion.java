package com.hubsabai.changelog.storage;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(
    name = "changelog_version",
    uniqueConstraints = @UniqueConstraint(name = "uq_changelog_version", columnNames = {"owner_user_id", "provider", "project", "repo", "version", "build_id"})
)
public class ChangelogVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * The internal app user who owns this row ({@code github_user.id}), or {@code null} when the
     * row belongs to the service/CI rather than to any one person. This is the ownership key:
     * never the GitHub login (mutable, and can be recycled by someone else) and never the GitHub
     * numeric id (an identifier this app does not control).
     */
    @Column(name = "owner_user_id")
    public Long ownerUserId;

    /** The source provider this version row belongs to — {@code "azure"} or {@code "github"}
     * (see {@link com.hubsabai.changelog.storage.GeneratedChangelog#provider}). */
    @Column(length = 20)
    public String provider = "azure";

    public String project;
    public String repo;

    @Column(length = 100)
    public String version;

    @Column(name = "build_id")
    public Integer buildId;

    @Column(name = "build_number", length = 100)
    public String buildNumber;

    /** The pipeline run number from CI (e.g. '9', '12'). Separate from {@link #version}, which is
     * the canonical semantic release version. May be null when only a release version is known. */
    @Column(name = "pipeline_run_number", length = 100)
    public String pipelineRunNumber;

    @Column(length = 20)
    public String stage;

    @Column(name = "raw_items", columnDefinition = "text")
    public String rawItems;

    @Column(length = 255)
    public String branch;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "pushed_at")
    public OffsetDateTime pushedAt;

    @Column(name = "pushed_commit_url", columnDefinition = "text")
    public String pushedCommitUrl;

    /**
     * Every finder takes {@code ownerUserId} — the acting user's id, or {@code null} for a
     * service/CI caller. A row is visible when it is owned by the caller <em>or</em> owned by the
     * service, and the caller's own rows sort first, so a service-created row for the same
     * repository never shadows the user's copy.
     */
    public static final String OWNER_VISIBLE = " and (ownerUserId = ?%d or ownerUserId is null)";

    /** Sorts the caller's own rows ahead of service-owned rows for the same business key. */
    public static final String OWNER_FIRST = " order by case when ownerUserId = ?%d then 0 else 1 end";

    public static ChangelogVersion findEntry(String project, String repo, String version, Long ownerUserId) {
        return findEntry("azure", project, repo, version, ownerUserId);
    }

    public static ChangelogVersion findEntry(String provider, String project, String repo, String version, Long ownerUserId) {
        return find("provider = ?1 and project = ?2 and repo = ?3 and version = ?4"
                        + String.format(OWNER_VISIBLE + OWNER_FIRST, 5, 5),
                provider, project, repo, version, ownerUserId).firstResult();
    }

    /** Finds a record by project/repo and non-null version — used by push/conflict detection. */
    public static ChangelogVersion findEntryNotNullVersion(String project, String repo, String version, Long ownerUserId) {
        if (version == null || version.isBlank()) return null;
        return findEntry(project, repo, version, ownerUserId);
    }

    public static List<ChangelogVersion> listByRepo(String project, String repo, Long ownerUserId) {
        return listByRepo("azure", project, repo, ownerUserId);
    }

    public static List<ChangelogVersion> listByRepo(String provider, String project, String repo, Long ownerUserId) {
        return find("provider = ?1 and project = ?2 and repo = ?3"
                        + String.format(OWNER_VISIBLE + OWNER_FIRST, 4, 4) + ", createdAt desc",
                provider, project, repo, ownerUserId).list();
    }

    public static long countByRepo(String project, String repo, Long ownerUserId) {
        return countByRepo("azure", project, repo, ownerUserId);
    }

    public static long countByRepo(String provider, String project, String repo, Long ownerUserId) {
        return count("provider = ?1 and project = ?2 and repo = ?3 and (ownerUserId = ?4 or ownerUserId is null)",
                provider, project, repo, ownerUserId);
    }
}
