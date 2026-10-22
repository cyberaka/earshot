-- Reassigning single lines (owner request, 2026-10-20): two similar voices (siblings on one phone)
-- end up in one cluster, so a line can be moved to another person on its own. A moved line is pinned:
-- live regrouping leaves it alone. A cluster whose voice the user asked to remember keeps that wish
-- even before its audio is on disk (live chunks are written every 30 s); the voiceprint follows then.
ALTER TABLE segment ADD COLUMN speaker_manual BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE speaker_cluster ADD COLUMN remember BOOLEAN NOT NULL DEFAULT false;
UPDATE speaker_cluster sc SET remember = true WHERE EXISTS (SELECT 1 FROM voiceprint v WHERE v.cluster_id = sc.id);
