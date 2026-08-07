ALTER TABLE generated_changelog
    ADD COLUMN pushed_text TEXT,
    ADD COLUMN pushed_at TIMESTAMPTZ,
    ADD COLUMN pushed_pull_request_url TEXT;
