-- Screenshots attached to a meeting at a moment of the transcript (owner request R-1, 2026-10-24),
-- and per-meeting vocabulary for Whisper (R-3).
CREATE TABLE attachment (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,              -- the file, under the part's recording folder
    original_name   TEXT,
    content_type    TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL,
    caption         TEXT,
    taken_at        TIMESTAMPTZ,                -- when the screenshot was taken, if the file says so
    attached_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    anchor_s        DOUBLE PRECISION NOT NULL,  -- where it sits, as an offset in the recording
    placed_by       TEXT NOT NULL CHECK (placed_by IN ('taken', 'attached', 'end'))
);
CREATE INDEX attachment_conversation ON attachment (conversation_id, anchor_s);

ALTER TABLE meeting ADD COLUMN vocabulary TEXT;
