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

@Entity
@Table(
    name = "generated_changelog",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_generated_changelog",
        columnNames = {"provider", "project", "repo", "version", "audience"})
)
public class GeneratedChangelog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The source provider this changelog belongs to — {@code "azure"} (Azure DevOps) or
     * {@code "github"}. Keeps a GitHub owner+repo row from colliding with an Azure DevOps
     * project+repo that shares the same strings. */
    @Column(length = 20)
    public String provider = "azure";

    public String project;
    public String repo;

    @Column(length = 100)
    public String version;

    @Column(length = 50)
    public String audience;

    /** What's shown right now — either the latest AI generation or the latest human edit,
     * whichever happened most recently ({@link #currentSource} says which). */
    @Column(name = "current_text", columnDefinition = "text")
    public String currentText;

    @Column(name = "current_source", length = 10)
    public String currentSource;

    @Column(name = "current_model_id")
    public String currentModelId;

    @Column(name = "current_input_hash", length = 64)
    public String currentInputHash;

    @Column(name = "current_edited_by")
    public String currentEditedBy;

    @Column(name = "current_at")
    public OffsetDateTime currentAt;

    /** Whatever was in {@code current_*} immediately before the last write — one rolling level
     * of history, shifted forward on every edit or regeneration. Null until there's been a
     * second write. */
    @Column(name = "previous_text", columnDefinition = "text")
    public String previousText;

    @Column(name = "previous_source", length = 10)
    public String previousSource;

    @Column(name = "previous_model_id")
    public String previousModelId;

    @Column(name = "previous_input_hash", length = 64)
    public String previousInputHash;

    @Column(name = "previous_edited_by")
    public String previousEditedBy;

    @Column(name = "previous_at")
    public OffsetDateTime previousAt;

    /** The text as of the last successful push to the repo. Null until the first push. */
    @Column(name = "pushed_text", columnDefinition = "text")
    public String pushedText;

    @Column(name = "pushed_at")
    public OffsetDateTime pushedAt;

    @Column(name = "pushed_pull_request_url", columnDefinition = "text")
    public String pushedPullRequestUrl;

    public static GeneratedChangelog findEntry(String project, String repo, String version, String audience) {
        return findEntry("azure", project, repo, version, audience);
    }

    public static GeneratedChangelog findEntry(String provider, String project, String repo, String version, String audience) {
        return find("provider = ?1 and project = ?2 and repo = ?3 and version = ?4 and audience = ?5",
                provider, project, repo, version, audience)
            .firstResult();
    }

    /** All entries for one project/repo/audience, in one query — for batch reads like /history,
     * which would otherwise run one query per version. */
    public static java.util.List<GeneratedChangelog> findAllForAudience(String project, String repo, String audience) {
        return findAllForAudience("azure", project, repo, audience);
    }

    public static java.util.List<GeneratedChangelog> findAllForAudience(String provider, String project, String repo, String audience) {
        return find("provider = ?1 and project = ?2 and repo = ?3 and audience = ?4", provider, project, repo, audience).list();
    }

    /**
     * Names of repos in {@code project} that {@code /history} would serve entirely from the DB
     * cache (see the {@code hasImportCache} check in {@code AzureDevOpsResource#history}) without
     * needing to hit Azure DevOps at all. One query for the whole project rather than one per
     * repo — callers that fan a per-repo live check out to a plain (non-Quarkus-managed) executor
     * must look this up beforehand on the request thread, since Panache's entity manager isn't
     * available on threads outside Quarkus's request-scoped context.
     */
    public static java.util.Set<String> reposWithImportCache(String project) {
        return reposWithImportCache("azure", project);
    }

    public static java.util.Set<String> reposWithImportCache(String provider, String project) {
        java.util.List<GeneratedChangelog> rows = find(
                "provider = ?1 and project = ?2 and audience = 'developer' and currentSource = 'import'", provider, project).list();
        java.util.Set<String> repos = new java.util.HashSet<>();
        for (GeneratedChangelog row : rows) {
            repos.add(row.repo);
        }
        return repos;
    }
}
