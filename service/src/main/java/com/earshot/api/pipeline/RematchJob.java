package com.earshot.api.pipeline;

import java.time.LocalDate;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;
import com.earshot.api.speaker.SpeakerMatcher;

/**
 * Re-runs speaker matching over past conversations after a voiceprint or threshold change
 * (A-12). Cheap: embeddings are stored. A conversation that stops being ambient is transcribed.
 */
@Component
public class RematchJob implements JobWorker.Handler {

	private static final Logger log = LoggerFactory.getLogger(RematchJob.class);
	private final JdbcClient db;
	private final SpeakerMatcher matcher;
	private final Jobs jobs;
	private final MarkdownExporter exporter;

	public RematchJob(JdbcClient db, SpeakerMatcher matcher, Jobs jobs, MarkdownExporter exporter) {
		this.db = db;
		this.matcher = matcher;
		this.jobs = jobs;
		this.exporter = exporter;
	}

	@Override
	public String type() {
		return "rematch";
	}

	@Override
	public void run(Jobs.Job job, JobWorker worker) {
		var convs = db.sql("""
				SELECT id, is_ambient, stage, local_date, meeting_id FROM conversation
				WHERE stage NOT IN ('segmented', 'diarized') ORDER BY id""")
				.query((rs, i) -> new Object[] { rs.getLong(1), rs.getBoolean(2), rs.getString(3), rs.getObject(4, LocalDate.class),
						rs.getObject(5) })
				.list();
		var days = new HashSet<LocalDate>();
		int revived = 0;
		for (Object[] c : convs) {
			long id = (long) c[0];
			var r = matcher.match(id);
			// A meeting is never ambient: the user chose to record it (PRD §6.6).
			boolean ambient = c[4] == null && r.ambient();
			db.sql("UPDATE conversation SET is_ambient = :a WHERE id = :id").param("a", ambient).param("id", id).update();
			boolean transcribed = !"identified".equals(c[2]);
			if (!ambient && !transcribed) {
				db.sql("UPDATE conversation SET status = 'queued' WHERE id = :id").param("id", id).update();
				jobs.enqueue("conversation", null, id, null);
				revived++;
			}
			days.add((LocalDate) c[3]);
		}
		days.forEach(exporter::exportDay);
		log.info("event=rematched conversations={} revived={}", convs.size(), revived);
	}
}
