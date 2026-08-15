-- A manual dashboard generation is saved version-free, keyed on the pipeline run it came from.
-- `version` is then chosen by a human in the push modal and written into this row at push time.
ALTER TABLE recorded_pipeline_run
    ADD COLUMN ai_draft_audience VARCHAR(50),
    ADD COLUMN ai_draft_text TEXT,
    ADD COLUMN ai_draft_model VARCHAR(255),
    ADD COLUMN ai_draft_tokens BIGINT,
    ADD COLUMN ai_draft_duration_ms BIGINT,
    ADD COLUMN ai_draft_at TIMESTAMPTZ;
