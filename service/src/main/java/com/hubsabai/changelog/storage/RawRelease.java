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

/** The raw commit/PR facts a pipeline reports for one release, before any AI text exists for it —
 * ingested via {@code POST /api/pipeline/generate}, which no longer calls AI itself. This is what
 * the dashboard's Developer tab shows first (as a plain view), and what a human later generates
 * or edits from, same as any other audience. */
@Entity
@Table(
    name = "raw_release",
    uniqueConstraints = @UniqueConstraint(name = "uq_raw_release", columnNames = {"project", "repo", "version"})
)
public class RawRelease extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String project;
    public String repo;
    public String branch;

    @Column(length = 100)
    public String version;

    /** {@code prerelease} or {@code release} — which stage of the pipeline reported this. */
    @Column(length = 20)
    public String stage;

    /** The raw {@code ChangeItem} list (commits/PRs/work items) serialized as JSON, exactly as
     * received — plain text column, not jsonb: nothing here ever queries into the JSON itself, so
     * there's no need for Postgres-side JSON typing or its Hibernate mapping overhead. */
    @Column(columnDefinition = "text")
    public String items;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    public static RawRelease findEntry(String project, String repo, String version) {
        return find("project = ?1 and repo = ?2 and version = ?3", project, repo, version).firstResult();
    }
}
