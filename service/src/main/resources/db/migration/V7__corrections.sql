-- Corrections by the user (owner request R-4, 2026-10-25).
-- A line the user edited, or replaced with a re-transcription, keeps what Earshot first wrote.
ALTER TABLE segment ADD COLUMN original_text TEXT;
ALTER TABLE segment ADD COLUMN edited_by TEXT;   -- 'user', 'correction', or the model that re-transcribed it ('local:…', 'openai:…')

-- Words and phrases Earshot keeps getting wrong: applied to every new transcript, and fed to Whisper as names and terms.
CREATE TABLE correction (
    id          BIGSERIAL PRIMARY KEY,
    wrong       TEXT NOT NULL,
    fixed       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX correction_wrong ON correction (lower(wrong));
