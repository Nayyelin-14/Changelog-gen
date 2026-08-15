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
    uniqueConstraints = @UniqueConstraint(name = "uq_changelog_version", columnNames = {"provider", "project", "repo", "version", "build_id"})
)
public class ChangelogVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

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

    public static ChangelogVersion findEntry(String project, String repo, String version) {
        return findEntry("azure", project, repo, version);
    }

    public static ChangelogVersion findEntry(String provider, String project, String repo, String version) {
        return find("provider = ?1 and project = ?2 and repo = ?3 and version = ?4", provider, project, repo, version).firstResult();
    }

    /** Finds a record by project/repo and non-null version — used by push/conflict detection. */
    public static ChangelogVersion findEntryNotNullVersion(String project, String repo, String version) {
        if (version == null || version.isBlank()) return null;
        return findEntry(project, repo, version);
    }

    public static List<ChangelogVersion> listByRepo(String project, String repo) {
        return listByRepo("azure", project, repo);
    }

    public static List<ChangelogVersion> listByRepo(String provider, String project, String repo) {
        return find("provider = ?1 and project = ?2 and repo = ?3 order by createdAt desc", provider, project, repo).list();
    }

    public static long countByRepo(String project, String repo) {
        return countByRepo("azure", project, repo);
    }

    public static long countByRepo(String provider, String project, String repo) {
        return count("provider = ?1 and project = ?2 and repo = ?3", provider, project, repo);
    }
}
