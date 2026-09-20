package com.hubsabai.changelog.storage;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/**
 * Per-audience draft for a pipeline run — each audience (developer, qa, business) has its own
 * independent persisted draft. Saving one audience never overwrites another.
 * Replaces the single-slot ai_draft_* fields on {@link RecordedPipelineRun}.
 */
@Entity
@Table(
    name = "recorded_run_draft",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_recorded_run_draft",
        columnNames = {"run_id", "audience"}
    )
)
public class RecordedRunDraft extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    public RecordedPipelineRun run;

    @Column(length = 50)
    public String audience;

    @Column(name = "draft_text", columnDefinition = "text")
    public String draftText;

    @Column(name = "draft_source", length = 40)
    public String draftSource;

    @Column(name = "draft_model")
    public String draftModel;

    @Column(name = "draft_tokens")
    public Long draftTokens;

    @Column(name = "draft_duration_ms")
    public Long draftDurationMs;

    @Column(name = "draft_edited_by", length = 100)
    public String draftEditedBy;

    @Column(name = "draft_at")
    public OffsetDateTime draftAt;

    @Column(name = "draft_history", columnDefinition = "text")
    public String draftHistory;

    public static RecordedRunDraft findByRunAndAudience(Long runId, String audience) {
        return find("run.id = ?1 and audience = ?2", runId, audience).firstResult();
    }

    public static java.util.List<RecordedRunDraft> findAllByRun(Long runId) {
        return find("run.id = ?1 order by audience asc", runId).list();
    }
}
