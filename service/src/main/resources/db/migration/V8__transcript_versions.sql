-- Another engine's transcript of a whole conversation, kept beside Earshot's (owner request, 2026-10-26):
-- the user compares them side by side and picks which one the conversation uses; neither is deleted.
-- The lines in use are always the conversation's segments. A version not in use keeps its lines here:
-- OpenAI's as they came back, and Earshot's own while another version is in use.
CREATE TABLE transcript_version (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    engine           TEXT NOT NULL,                     -- 'earshot' or 'openai'
    model            TEXT,
    status           TEXT NOT NULL DEFAULT 'done',      -- 'running', 'done' or 'failed'
    error            TEXT,
    lines            JSONB NOT NULL DEFAULT '[]',
    speakers         JSONB NOT NULL DEFAULT '{}',       -- names given to its speakers while it was in use
    raw              JSONB,                             -- the provider's replies exactly as returned: an array of strings, one per piece
    usage            JSONB,
    audio_s          DOUBLE PRECISION,
    pieces           INTEGER NOT NULL DEFAULT 1,
    usd              DOUBLE PRECISION,
    took_s           DOUBLE PRECISION,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX transcript_version_conversation ON transcript_version (conversation_id);
CREATE UNIQUE INDEX transcript_version_earshot ON transcript_version (conversation_id) WHERE engine = 'earshot';

-- NULL: Earshot's own transcript is in use.
ALTER TABLE conversation ADD COLUMN transcript_version_id BIGINT REFERENCES transcript_version(id) ON DELETE SET NULL;
