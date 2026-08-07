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
    uniqueConstraints = @UniqueConstraint(name = "uq_release_pr", columnNames = {"project", "repo", "pr_id"})
)
public class ReleasePr extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

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

    public static ReleasePr findEntry(String project, String repo, int prId) {
        return find("project = ?1 and repo = ?2 and prId = ?3", project, repo, prId).firstResult();
    }
}
