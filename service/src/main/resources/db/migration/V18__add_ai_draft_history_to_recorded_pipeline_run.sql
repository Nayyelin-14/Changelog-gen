ALTER TABLE recorded_pipeline_run
    ADD COLUMN ai_draft_source VARCHAR(40),
    ADD COLUMN ai_draft_edited_by VARCHAR(100),
    ADD COLUMN ai_draft_history TEXT;