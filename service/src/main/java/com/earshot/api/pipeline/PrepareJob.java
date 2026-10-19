package com.earshot.api.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;
import com.earshot.api.ml.MlClient;
import com.earshot.api.settings.Settings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ingest → working copy → VAD → conversations (PRD §5.2 steps 1–5). */
@Component
public class PrepareJob implements JobWorker.Handler {

	private static final Logger log = LoggerFactory.getLogger(PrepareJob.class);
	private final JdbcClient db;
	private final MlClient ml;
	private final Settings settings;
	private final Jobs jobs;
	private final JsonMapper json;
	private final Path work;

	public PrepareJob(JdbcClient db, MlClient ml, Settings settings, Jobs jobs, JsonMapper json, EarshotProperties props) {
		this.db = db;
		this.ml = ml;
		this.settings = settings;
		this.jobs = jobs;
		this.json = json;
		this.work = props.data().resolve("audio/work");
	}

	@Override
	public String type() {
		return "prepare";
	}

	@Override
	public void run(Jobs.Job job, JobWorker worker) throws Exception {
		long id = job.recordingId();
		var rec = db.sql("SELECT path, work_path, speech_regions::text, started_at, started_at_source, time_zone FROM recording WHERE id = :id")
				.param("id", id).query((rs, i) -> new Object[] { rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6) })
				.single();
		String workPath = (String) rec[1];
		String regionsJson = (String) rec[2];
		double duration;
		if (workPath == null || regionsJson == null || !Files.exists(Path.of(workPath))) {
			jobs.stage(job.id(), "prepare");
			Files.createDirectories(work);
			workPath = work.resolve(id + ".flac").toString();
			JsonNode res = ml.prepare((String) rec[0], workPath);
			duration = res.get("duration_s").asDouble();
			regionsJson = json.writeValueAsString(res.get("regions"));
			Instant started = (Instant) rec[3];
			if ("mtime".equals(rec[4])) {
				started = started.minusMillis((long) (duration * 1000));
			}
			db.sql("""
					UPDATE recording SET work_path = :w, duration_s = :d, speech_regions = CAST(:r AS jsonb), started_at = :s,
					    status = 'prepared' WHERE id = :id""").param("w", workPath).param("d", duration).param("r", regionsJson)
					.param("s", java.sql.Timestamp.from(started)).param("id", id).update();
		} else {
			duration = db.sql("SELECT duration_s FROM recording WHERE id = :id").param("id", id).query(Double.class).single();
		}
		worker.checkpoint();
		long existing = db.sql("SELECT count(*) FROM conversation WHERE recording_id = :id").param("id", id).query(Long.class).single();
		if (existing > 0) {
			return;
		}
		var regions = new ArrayList<double[]>();
		json.readTree(regionsJson).forEach(r -> regions.add(new double[] { r.get(0).asDouble(), r.get(1).asDouble() }));
		List<Segmenter.Span> spans = Segmenter.split(regions, settings.num("conversation_gap_seconds"),
				settings.num("max_conversation_minutes") * 60, settings.num("min_conversation_seconds"), duration);
		Instant recStart = db.sql("SELECT started_at FROM recording WHERE id = :id").param("id", id).query(java.sql.Timestamp.class)
				.single().toInstant();
		ZoneId zone = ZoneId.of((String) rec[5]);
		for (var s : spans) {
			Instant start = recStart.plusMillis((long) (s.start() * 1000));
			Instant end = recStart.plusMillis((long) (s.end() * 1000));
			long cid = db.sql("""
					INSERT INTO conversation (recording_id, start_offset_s, end_offset_s, started_at, local_date, duplicate_of_meeting_id)
					VALUES (:r, :s, :e, :t, :d, (
					    SELECT m.id FROM meeting m WHERE m.ended_at IS NOT NULL
					      AND LEAST(m.ended_at, CAST(:end AS timestamptz)) - GREATEST(m.started_at, CAST(:t AS timestamptz))
					          >= 0.5 * LEAST(m.ended_at - m.started_at, CAST(:end AS timestamptz) - CAST(:t AS timestamptz))
					    ORDER BY m.id LIMIT 1))
					RETURNING id""").param("r", id).param("s", s.start()).param("e", s.end()).param("t", java.sql.Timestamp.from(start))
					.param("end", java.sql.Timestamp.from(end)).param("d", start.atZone(zone).toLocalDate()).query(Long.class).single();
			jobs.enqueue("conversation", null, cid, null);
		}
		db.sql("UPDATE recording SET status = 'segmented' WHERE id = :id").param("id", id).update();
		log.info("event=segmented recording={} conversations={} duration_s={}", id, spans.size(), Math.round(duration));
	}
}
