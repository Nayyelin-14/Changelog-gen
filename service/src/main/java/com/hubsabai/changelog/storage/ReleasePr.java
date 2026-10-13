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

/** Indexes which version a PR shipped in — one row per PR, upserted as the pipeline reports it so
 * a PR can move from {@code prerelease} to {@code release} over time without a second row. This is
 * what makes "which version did PR !N ship in" a direct lookup instead of a text search over
 * generated changelog prose. */
@Entity
@Table(
    name = "release_pr",
    uniqueConstraints = @UniqueConstraint(name = "uq_release_pr", columnNames = {"owner_user_id", "project", "repo", "pr_id"})
)
public class ReleasePr extends PanacheEntityBase {

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

    public String project;
    public String repo;

    @Column(name = "pr_id")
    public Integer prId;

    @Column(length = 100)
    public String version;

    /** {@code prerelease} or {@code release} — the most advanced stage this PR has been reported
     * at. Never regresses: a later {@code prerelease} report for the same PR must not overwrite an
     * already-recorded {@code release}. */
    @Column(length = 20)
    public String stage;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    public static ReleasePr findEntry(String project, String repo, int prId, Long ownerUserId) {
        return find("project = ?1 and repo = ?2 and prId = ?3"
                        + String.format(ChangelogVersion.OWNER_VISIBLE + ChangelogVersion.OWNER_FIRST, 4, 4),
                project, repo, prId, ownerUserId).firstResult();
    }
}
