-- Remembered voices (owner request, 2026-10-20): a voiceprint saved when naming a speaker points at
-- that speaker's cluster, so correcting the name removes the wrong voiceprint, and while a meeting is
-- live the voiceprint follows the cluster as more of the person's speech arrives. The voiceprint
-- outlives the cluster (deleting a conversation keeps the person's voice).
ALTER TABLE voiceprint ADD COLUMN cluster_id BIGINT REFERENCES speaker_cluster(id) ON DELETE SET NULL;
CREATE INDEX voiceprint_cluster ON voiceprint (cluster_id);
