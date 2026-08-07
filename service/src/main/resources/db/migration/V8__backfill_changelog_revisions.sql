-- Backfill changelog_version and changelog_revision from existing generated_changelog data.
-- The V7 migration created the new tables but didn't move data from the old tables,
-- leaving changelog_version and changelog_revision empty for all existing entries.
-- If generated_changelog was already dropped (by a previous V7 version), skip the backfill.

DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'generated_changelog') THEN

        -- 1. Create changelog_version rows for every (project, repo, version) that exists in
        --    generated_changelog but doesn't have a row yet.
        INSERT INTO changelog_version (project, repo, version, created_at)
        SELECT DISTINCT gc.project, gc.repo, gc.version,
            COALESCE(gc.current_at, gc.previous_at, NOW())
        FROM generated_changelog gc
        WHERE gc.version IS NOT NULL AND gc.version != ''
          AND NOT EXISTS (
            SELECT 1 FROM changelog_version cv
            WHERE cv.project = gc.project AND cv.repo = gc.repo AND cv.version = gc.version
          );

        -- 2. Insert a revision for each generated_changelog row's current_text.
        INSERT INTO changelog_revision (version_id, audience, sequence, text, source, model, edited_by, created_at)
        SELECT cv.id, gc.audience,
            CASE WHEN gc.previous_text IS NOT NULL AND gc.previous_text != '' THEN 1 ELSE 0 END,
            gc.current_text, gc.current_source, gc.current_model_id,
            gc.current_edited_by, gc.current_at
        FROM generated_changelog gc
        JOIN changelog_version cv ON cv.project = gc.project AND cv.repo = gc.repo AND cv.version = gc.version
        WHERE gc.current_text IS NOT NULL AND gc.current_text != ''
          AND NOT EXISTS (
            SELECT 1 FROM changelog_revision cr
            WHERE cr.version_id = cv.id
              AND cr.audience = gc.audience
              AND cr.sequence = CASE WHEN gc.previous_text IS NOT NULL AND gc.previous_text != '' THEN 1 ELSE 0 END
          );

        -- 3. Insert a revision for previous_text (sequence = 0) when it exists.
        INSERT INTO changelog_revision (version_id, audience, sequence, text, source, model, edited_by, created_at)
        SELECT cv.id, gc.audience, 0,
            gc.previous_text, gc.previous_source, gc.previous_model_id,
            gc.previous_edited_by, gc.previous_at
        FROM generated_changelog gc
        JOIN changelog_version cv ON cv.project = gc.project AND cv.repo = gc.repo AND cv.version = gc.version
        WHERE gc.previous_text IS NOT NULL AND gc.previous_text != ''
          AND NOT EXISTS (
            SELECT 1 FROM changelog_revision cr
            WHERE cr.version_id = cv.id
              AND cr.audience = gc.audience
              AND cr.sequence = 0
          );

        -- 4. Old tables are no longer needed — drop them now that their data lives in
        --    changelog_version/changelog_revision.
        DROP TABLE IF EXISTS generated_changelog CASCADE;
        DROP TABLE IF EXISTS raw_release CASCADE;
        DROP SEQUENCE IF EXISTS generated_changelog_SEQ;
        DROP SEQUENCE IF EXISTS raw_release_SEQ;

    END IF;
END $$;