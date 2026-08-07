ALTER TABLE generated_changelog
    ADD COLUMN current_text TEXT,
    ADD COLUMN current_source VARCHAR(10),
    ADD COLUMN current_model_id VARCHAR(255),
    ADD COLUMN current_input_hash VARCHAR(64),
    ADD COLUMN current_edited_by VARCHAR(255),
    ADD COLUMN current_at TIMESTAMPTZ,
    ADD COLUMN previous_text TEXT,
    ADD COLUMN previous_source VARCHAR(10),
    ADD COLUMN previous_model_id VARCHAR(255),
    ADD COLUMN previous_input_hash VARCHAR(64),
    ADD COLUMN previous_edited_by VARCHAR(255),
    ADD COLUMN previous_at TIMESTAMPTZ;

UPDATE generated_changelog SET
    current_text = COALESCE(edited_text, generated_text),
    current_source = CASE WHEN edited_text IS NOT NULL THEN 'edit' ELSE 'ai' END,
    current_model_id = model_id,
    current_input_hash = input_hash,
    current_edited_by = edited_by,
    current_at = COALESCE(edited_at, created_at);

ALTER TABLE generated_changelog
    ALTER COLUMN current_text SET NOT NULL,
    ALTER COLUMN current_source SET NOT NULL,
    ALTER COLUMN current_at SET NOT NULL;

ALTER TABLE generated_changelog
    DROP COLUMN generated_text,
    DROP COLUMN edited_text,
    DROP COLUMN model_id,
    DROP COLUMN input_hash,
    DROP COLUMN created_at,
    DROP COLUMN edited_by,
    DROP COLUMN edited_at;
