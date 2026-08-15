-- Adds a `provider` dimension to the changelog storage tables so a GitHub repo
-- (keyed by owner+repo) can never collide with an Azure DevOps project+repo that
-- happens to share the same strings. Existing rows default to 'azure'.
-- `changelog_revision` needs no provider column: it hangs off `changelog_version`,
-- which carries the provider.
--
-- The tight unique constraints were declared inline in V1/V7 (unnamed, so Postgres
-- auto-named them), so there is no deterministic `uq_changelog_version`/... name to
-- `DROP INDEX`. Recreate each as a drop-by-constraint + named-idempotent-add pair:
-- drop the old unnamed unique constraint if present, then add a NEW named UNIQUE
-- constraint on (provider, ...) with IF NOT EXISTS semantics so re-running on a DB
-- that never had the old one still works.

-- generated_changelog
ALTER TABLE generated_changelog ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'azure';

ALTER TABLE generated_changelog DROP CONSTRAINT IF EXISTS uq_generated_changelog;
ALTER TABLE generated_changelog DROP CONSTRAINT IF EXISTS generated_changelog_project_repo_version_audience_key;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'generated_changelog'::regclass AND conname = 'uq_generated_changelog') THEN
        ALTER TABLE generated_changelog ADD CONSTRAINT uq_generated_changelog
            UNIQUE (provider, project, repo, version, audience);
    END IF;
END $$;

-- changelog_version
ALTER TABLE changelog_version ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'azure';

ALTER TABLE changelog_version DROP CONSTRAINT IF EXISTS uq_changelog_version;
ALTER TABLE changelog_version DROP CONSTRAINT IF EXISTS changelog_version_project_repo_version_key;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'changelog_version'::regclass AND conname = 'uq_changelog_version') THEN
        ALTER TABLE changelog_version ADD CONSTRAINT uq_changelog_version
            UNIQUE (provider, project, repo, version);
    END IF;
END $$;