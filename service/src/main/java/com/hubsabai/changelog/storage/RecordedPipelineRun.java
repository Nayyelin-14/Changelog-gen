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
    name = "recorded_pipeline_run",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_recorded_pipeline_run",
        columnNames = {"provider", "project", "repo", "build_id"}
    )
)
public class RecordedPipelineRun extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String provider;

    public String project;
    public String repo;

    @Column(name = "build_id")
    public Long buildId;

    @Column(length = 100)
    public String version;

    @Column(length = 20)
    public String stage;

    public String branch;

    @Column(name = "run_metadata", columnDefinition = "text")
    public String runMetadata;

    @Column(name = "change_items", columnDefinition = "text")
    public String changeItems;

    @Column(name = "raw_changelog", columnDefinition = "text")
    public String rawChangelog;

    /** AI-generated draft text saved from the dashboard before any version is chosen — the
     * version-free save path (see {@code generate-commit} with no version). {@code version} on
     * this row is then filled in by the human in the push modal when they commit to a release. */
    @Column(name = "ai_draft_audience", length = 50)
    public String aiDraftAudience;

    @Column(name = "ai_draft_text", columnDefinition = "text")
    public String aiDraftText;

    @Column(name = "ai_draft_model")
    public String aiDraftModel;

    @Column(name = "ai_draft_tokens")
    public Long aiDraftTokens;

    @Column(name = "ai_draft_duration_ms")
    public Long aiDraftDurationMs;

    @Column(name = "ai_draft_at")
    public OffsetDateTime aiDraftAt;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    public static RecordedPipelineRun findByBuildId(String provider, String project, String repo, Long buildId) {
        return find("provider = ?1 and project = ?2 and repo = ?3 and buildId = ?4",
                provider, project, repo, buildId).firstResult();
    }

    public static RecordedPipelineRun findByVersion(String provider, String project, String repo, String version) {
        return find("provider = ?1 and project = ?2 and repo = ?3 and version = ?4 order by createdAt desc",
                provider, project, repo, version).firstResult();
    }

    /** A short identity label for this run, for list views: the triggering commit's first line
     * (e.g. "Merge pull request #31 ...") when available, else the pipeline/workflow name, else a
     * plain "Run #<buildId>" fallback. Parsed from {@link #runMetadata}'s {@code run} node. */
    public String displayTitle() {
        if (runMetadata == null || runMetadata.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(runMetadata);
            com.fasterxml.jackson.databind.JsonNode run = node.path("run");
            if (run.isObject()) {
                String commitMessage = run.path("providerCommitMessage").asText(null);
                if (commitMessage != null && !commitMessage.isBlank()) {
                    String firstLine = commitMessage.split("\n")[0].trim();
                    if (!firstLine.isBlank()) {
                        return firstLine;
                    }
                }
                String pipelineName = run.path("pipelineName").asText(null);
                if (pipelineName != null && !pipelineName.isBlank()) {
                    String runNumber = run.path("runNumber").asText(null);
                    return runNumber != null && !runNumber.isBlank()
                            ? pipelineName + " #" + runNumber
                            : pipelineName;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}