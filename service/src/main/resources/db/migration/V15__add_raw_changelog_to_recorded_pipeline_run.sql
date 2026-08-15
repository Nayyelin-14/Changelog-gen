-- Raw changelog snapshot for GitHub workflow runs.
-- Captured at intake (eager: workflow POSTs to /api/github/pipeline/generate)
-- or lazily on first dashboard open. Version-free on purpose — the raw capture
-- is keyed only by (provider, project, repo, build_id) via the unique constraint.
-- AI/editorial output stays in the version-keyed tables (raw_release / generated_changelog).

ALTER TABLE recorded_pipeline_run ADD COLUMN IF NOT EXISTS raw_changelog TEXT;
