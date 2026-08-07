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
    uniqueConstraints = @UniqueConstraint(name = "uq_changelog_version", columnNames = {"project", "repo", "version"})
)
public class ChangelogVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String project;
    public String repo;

    @Column(length = 100)
    public String version;

    @Column(name = "build_id")
    public Integer buildId;

    @Column(name = "build_number", length = 100)
    public String buildNumber;

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
        return find("project = ?1 and repo = ?2 and version = ?3", project, repo, version).firstResult();
    }

    public static List<ChangelogVersion> listByRepo(String project, String repo) {
        return find("project = ?1 and repo = ?2 order by createdAt desc", project, repo).list();
    }

    public static long countByRepo(String project, String repo) {
        return count("project = ?1 and repo = ?2", project, repo);
    }
}
