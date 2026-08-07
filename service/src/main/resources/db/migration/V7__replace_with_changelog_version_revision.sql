-- Create the new version + revision tables.
-- Each (project, repo, version) has one row in changelog_version carrying pipeline metadata
-- and raw items; every text modification (pipeline ingest, AI gen, manual edit, restore)
-- gets its own row in changelog_revision so nothing is ever overwritten.
--
-- The old generated_changelog and raw_release tables are kept alive so V8 can backfill their
-- data, then dropped at the end of V8.

CREATE TABLE changelog_version (
    id BIGSERIAL PRIMARY KEY,
    project VARCHAR(255) NOT NULL,
    repo VARCHAR(255) NOT NULL,
    version VARCHAR(100) NOT NULL,
    build_id INTEGER,
    build_number VARCHAR(100),
    stage VARCHAR(20),
    raw_items TEXT,
    branch VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    pushed_at TIMESTAMP WITH TIME ZONE,
    pushed_commit_url TEXT,
    UNIQUE (project, repo, version)
);

CREATE TABLE changelog_revision (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES changelog_version(id) ON DELETE CASCADE,
    audience VARCHAR(50) NOT NULL,
    sequence INTEGER NOT NULL,
    text TEXT NOT NULL,
    source VARCHAR(10) NOT NULL,
    model VARCHAR(255),
    tokens INTEGER,
    duration_ms INTEGER,
    edited_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (version_id, audience, sequence)
);

-- Hibernate-style sequences for the new tables
CREATE SEQUENCE changelog_version_SEQ START 1 INCREMENT 50;
CREATE SEQUENCE changelog_revision_SEQ START 1 INCREMENT 50;

-- Index for listing versions by project+repo
CREATE INDEX idx_changelog_version_lookup ON changelog_version (project, repo, created_at DESC);

-- Index for fetching revisions for a version+audience
CREATE INDEX idx_changelog_revision_lookup ON changelog_revision (version_id, audience, sequence);
