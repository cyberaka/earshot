package com.earshot.api.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The persisted job table (PRD §10.1): no Redis, survives restarts. */
@Repository
public class Jobs {

	public record Job(long id, String type, Long recordingId, Long conversationId, Long meetingId, int attempts) {
	}

	private final JdbcClient db;

	public Jobs(JdbcClient db) {
		this.db = db;
	}

	public long enqueue(String type, Long recordingId, Long conversationId, Long meetingId) {
		return db.sql("""
				INSERT INTO job (type, recording_id, conversation_id, meeting_id) VALUES (:t, :r, :c, :m) RETURNING id""")
				.param("t", type).param("r", recordingId).param("c", conversationId).param("m", meetingId).query(Long.class).single();
	}

	/** Claims the oldest queued job. Jobs left 'running' by a crash are re-queued at startup. */
	public Optional<Job> claim() {
		return db.sql("""
				UPDATE job SET status = 'running', attempts = attempts + 1, updated_at = now()
				WHERE id = (SELECT id FROM job WHERE status = 'queued' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
				RETURNING id, type, recording_id, conversation_id, meeting_id, attempts""")
				.query((rs, i) -> new Job(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4),
						(Long) rs.getObject(5), rs.getInt(6)))
				.optional();
	}

	/** While a meeting is live, only work the user clicked for during it. */
	public Optional<Job> claimClickedDuringLive() {
		return db.sql("""
				UPDATE job SET status = 'running', attempts = attempts + 1, updated_at = now()
				WHERE id = (SELECT id FROM job WHERE status = 'queued' AND type LIKE '%\\_now' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
				RETURNING id, type, recording_id, conversation_id, meeting_id, attempts""")
				.query((rs, i) -> new Job(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4),
						(Long) rs.getObject(5), rs.getInt(6)))
				.optional();
	}

	public void finish(long id) {
		db.sql("UPDATE job SET status = 'done', error = NULL, updated_at = now() WHERE id = :id").param("id", id).update();
	}

	public void stage(long id, String stage) {
		db.sql("UPDATE job SET stage = :s, updated_at = now() WHERE id = :id").param("s", stage).param("id", id).update();
	}

	/** Back in the queue (paused for a meeting, or the LLM was offline); the stage checkpoint stays. */
	public void requeue(long id, String reason) {
		db.sql("UPDATE job SET status = 'queued', error = :e, updated_at = now() WHERE id = :id").param("e", reason).param("id", id).update();
	}

	public void fail(long id, String error) {
		db.sql("UPDATE job SET status = 'failed', error = :e, updated_at = now() WHERE id = :id").param("e", error).param("id", id).update();
	}

	public int recoverInterrupted() {
		return db.sql("UPDATE job SET status = 'queued', updated_at = now() WHERE status = 'running'").update();
	}

	public List<Map<String, Object>> recent(int limit) {
		return db.sql("""
				SELECT j.id, j.type, j.status, j.stage, j.attempts, j.error, j.updated_at, j.recording_id, j.conversation_id,
				       j.meeting_id, COALESCE(r.original_name, c.file_name) AS original_name, c.title, c.local_date::text AS local_date, c.started_at AS conversation_started_at, c.stage AS conversation_stage,
				       c.is_ambient, c.time_zone, COALESCE(r.source_type, c.source_type) AS source_type, c.end_offset_s - c.start_offset_s AS duration_s,
				       (SELECT count(*) FROM segment s WHERE s.conversation_id = c.id AND NOT s.live) AS lines,
				       (SELECT count(DISTINCT s.speaker_cluster_id) FROM segment s WHERE s.conversation_id = c.id AND NOT s.live) AS speakers,
				       (SELECT string_agg(x.name, ', ' ORDER BY x.is_me DESC, x.first_s) FROM (SELECT p.name, p.is_me, min(s.start_s) AS first_s
				            FROM segment s JOIN speaker_cluster sc ON sc.id = s.speaker_cluster_id JOIN person p ON p.id = sc.person_id
				            WHERE s.conversation_id = c.id AND NOT s.live GROUP BY p.id, p.name, p.is_me) x) AS people,
				       (SELECT count(DISTINCT s.speaker_cluster_id) FROM segment s JOIN speaker_cluster sc ON sc.id = s.speaker_cluster_id
				            WHERE s.conversation_id = c.id AND NOT s.live AND sc.person_id IS NULL) AS unnamed
				FROM job j
				LEFT JOIN recording r ON r.id = j.recording_id
				LEFT JOIN (SELECT c.*, r2.time_zone, r2.source_type, r2.original_name AS file_name FROM conversation c JOIN recording r2 ON r2.id = c.recording_id) c ON c.id = j.conversation_id
				ORDER BY j.id DESC LIMIT :n""").param("n", limit).query().listOfRows();
	}

	public boolean pending() {
		return db.sql("SELECT count(*) FROM job WHERE status IN ('queued', 'running')").query(Long.class).single() > 0;
	}
}
