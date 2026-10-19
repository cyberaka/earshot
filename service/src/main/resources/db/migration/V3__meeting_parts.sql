-- Resumable meetings (owner request, 2026-10-19): a meeting can be stopped and resumed any time later.
-- Each part is its own recording and conversation (so it shows on the day it happened); the meeting
-- page joins the parts. A speaker heard again in a later part points at their first cluster, so one
-- name covers every part.
ALTER TABLE conversation ADD COLUMN part INTEGER NOT NULL DEFAULT 1;
ALTER TABLE conversation ADD COLUMN capture_files JSONB;
ALTER TABLE speaker_cluster ADD COLUMN origin_cluster_id BIGINT REFERENCES speaker_cluster(id) ON DELETE SET NULL;
CREATE INDEX speaker_cluster_origin ON speaker_cluster (origin_cluster_id);
UPDATE conversation c SET capture_files = m.capture_files FROM meeting m WHERE c.meeting_id = m.id;
