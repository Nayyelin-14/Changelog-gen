CREATE TABLE generated_changelog (
    id BIGSERIAL PRIMARY KEY,
    project VARCHAR(255) NOT NULL,
    repo VARCHAR(255) NOT NULL,
    version VARCHAR(100) NOT NULL,
    audience VARCHAR(50) NOT NULL,
    model_id VARCHAR(255),
    generated_text TEXT NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_generated_changelog UNIQUE (project, repo, version, audience)
);

CREATE INDEX idx_generated_changelog_lookup ON generated_changelog (project, repo, version, audience);
