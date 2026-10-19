-- The user's own notes on a conversation or meeting (owner request, 2026-10-19).
-- A pinned note stores a recording offset, not a segment id: the post-meeting pass replaces
-- segments, and an offset survives that (as Q&A citations do, PRD §9.2).
CREATE TABLE note (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    anchor_s        DOUBLE PRECISION,
    text            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED
);
CREATE INDEX note_conversation ON note (conversation_id, created_at);
CREATE INDEX note_tsv ON note USING gin (tsv);
CREATE INDEX note_trgm ON note USING gin (text gin_trgm_ops);
