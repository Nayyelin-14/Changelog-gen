-- Add pipeline_run_number column to separate pipeline execution identifier from release version.
-- pipeline_run_number stores values like '9' from CI (e.g., Azure DevOps build number, GitHub run number).
-- version remains the canonical semantic release version (e.g., '1.4.30').
-- build_number remains the CI provider's build number (not repurposed).

ALTER TABLE changelog_version ADD COLUMN IF NOT EXISTS pipeline_run_number VARCHAR(100);

-- Index for lookups by pipeline run number
CREATE INDEX IF NOT EXISTS idx_changelog_version_pipeline_run_number
    ON changelog_version (project, repo, pipeline_run_number);
