ALTER TABLE generated_changelog
    ADD COLUMN edited_text TEXT,
    ADD COLUMN edited_by VARCHAR(255),
    ADD COLUMN edited_at TIMESTAMPTZ;
