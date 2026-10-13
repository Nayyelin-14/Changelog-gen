-- Row ownership. Every stored changelog/release/run row gains `owner_user_id`:
-- the internal, immutable app user id that created it. `owner_user_id IS NULL`
-- means the row belongs to the service/CI, not to any one person.
--
-- `owner_user_id` is deliberately NOT the GitHub login (GitHub usernames are mutable and
-- can be recycled by a different person) and NOT the GitHub numeric id (an external
-- identifier this app does not control). It is `github_user.id`, allocated here.
--
-- Backfill is conditional on the database being single-tenant at migration time
-- (exactly one github_user): in that case every pre-existing row was created by that
-- one person, so it is theirs. With zero or several users nothing is guessed —
-- legacy rows stay service-owned (NULL) rather than being handed to the wrong person.
--
-- NULLs are distinct under UNIQUE in both Postgres and H2, so a service row and a
-- per-user row for the same (provider, project, repo, version, ...) coexist.

ALTER TABLE changelog_version        ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
ALTER TABLE changelog_revision       ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
ALTER TABLE generated_changelog      ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
ALTER TABLE raw_release              ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
ALTER TABLE release_pr               ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
ALTER TABLE recorded_pipeline_run    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;
ALTER TABLE recorded_run_draft       ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

-- Single-tenant backfill (no-op unless exactly one github_user row exists).
DO $$
DECLARE
    tenant_count BIGINT;
    tenant_id    BIGINT;
BEGIN
    SELECT COUNT(*) INTO tenant_count FROM github_user;
    IF tenant_count <> 1 THEN
        RETURN;
    END IF;
    SELECT id INTO tenant_id FROM github_user LIMIT 1;

    UPDATE changelog_version     SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
    UPDATE changelog_revision    SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
    UPDATE generated_changelog   SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
    UPDATE raw_release           SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
    UPDATE release_pr            SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
    UPDATE recorded_pipeline_run SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
    UPDATE recorded_run_draft    SET owner_user_id = tenant_id WHERE owner_user_id IS NULL;
END $$;

-- Ownership is now part of the business key: two users may each hold their own row for the
-- same repository/version/audience. Recreate each constraint with owner_user_id included.
-- `changelog_revision` and `recorded_run_draft` hang off a parent that already carries the
-- owner, so their unique keys stay as they are.

ALTER TABLE changelog_version DROP CONSTRAINT IF EXISTS uq_changelog_version;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'changelog_version'::regclass AND conname = 'uq_changelog_version') THEN
        ALTER TABLE changelog_version ADD CONSTRAINT uq_changelog_version
            UNIQUE (owner_user_id, provider, project, repo, version, build_id);
    END IF;
END $$;

ALTER TABLE generated_changelog DROP CONSTRAINT IF EXISTS uq_generated_changelog;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'generated_changelog'::regclass AND conname = 'uq_generated_changelog') THEN
        ALTER TABLE generated_changelog ADD CONSTRAINT uq_generated_changelog
            UNIQUE (owner_user_id, provider, project, repo, version, audience);
    END IF;
END $$;

ALTER TABLE raw_release DROP CONSTRAINT IF EXISTS uq_raw_release;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'raw_release'::regclass AND conname = 'uq_raw_release') THEN
        ALTER TABLE raw_release ADD CONSTRAINT uq_raw_release
            UNIQUE (owner_user_id, project, repo, version);
    END IF;
END $$;

ALTER TABLE release_pr DROP CONSTRAINT IF EXISTS uq_release_pr;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'release_pr'::regclass AND conname = 'uq_release_pr') THEN
        ALTER TABLE release_pr ADD CONSTRAINT uq_release_pr
            UNIQUE (owner_user_id, project, repo, pr_id);
    END IF;
END $$;

ALTER TABLE recorded_pipeline_run DROP CONSTRAINT IF EXISTS uq_recorded_pipeline_run;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'recorded_pipeline_run'::regclass AND conname = 'uq_recorded_pipeline_run') THEN
        ALTER TABLE recorded_pipeline_run ADD CONSTRAINT uq_recorded_pipeline_run
            UNIQUE (owner_user_id, provider, project, repo, build_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_changelog_version_owner
    ON changelog_version (owner_user_id, provider, project, repo);
CREATE INDEX IF NOT EXISTS idx_generated_changelog_owner
    ON generated_changelog (owner_user_id, provider, project, repo, audience);
CREATE INDEX IF NOT EXISTS idx_raw_release_owner
    ON raw_release (owner_user_id, project, repo);
CREATE INDEX IF NOT EXISTS idx_release_pr_owner
    ON release_pr (owner_user_id, project, repo);
CREATE INDEX IF NOT EXISTS idx_recorded_pipeline_run_owner
    ON recorded_pipeline_run (owner_user_id, provider, project, repo);
CREATE INDEX IF NOT EXISTS idx_changelog_revision_owner
    ON changelog_revision (owner_user_id, version_id);
CREATE INDEX IF NOT EXISTS idx_recorded_run_draft_owner
    ON recorded_run_draft (owner_user_id, run_id);
