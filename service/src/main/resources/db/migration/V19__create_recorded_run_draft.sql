CREATE TABLE recorded_run_draft (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT       NOT NULL REFERENCES recorded_pipeline_run(id) ON DELETE CASCADE,
    audience    VARCHAR(50)  NOT NULL,
    draft_text  TEXT,
    draft_source VARCHAR(40),
    draft_model VARCHAR(255),
    draft_tokens BIGINT,
    draft_duration_ms BIGINT,
    draft_edited_by VARCHAR(100),
    draft_at    TIMESTAMPTZ,
    draft_history TEXT,
    CONSTRAINT uq_recorded_run_draft UNIQUE (run_id, audience)
);

CREATE INDEX idx_recorded_run_draft_run_id ON recorded_run_draft(run_id);
