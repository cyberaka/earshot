package com.earshot.api.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.export.MarkdownExporter;

/** One-click delete that cascades to audio, vectors and exports (PRD §9.4). */
@Service
public class Deletions {

	private static final Logger log = LoggerFactory.getLogger(Deletions.class);
	private final JdbcClient db;
	private final MarkdownExporter exporter;

	public Deletions(JdbcClient db, MarkdownExporter exporter) {
		this.db = db;
		this.exporter = exporter;
	}

	public int conversation(long id) throws IOException {
		var row = db.sql("SELECT recording_id, local_date FROM conversation WHERE id = :id").param("id", id)
				.query((rs, i) -> new Object[] { rs.getLong(1), rs.getObject(2, LocalDate.class) }).optional().orElse(null);
		if (row == null) {
			return 0;
		}
		db.sql("DELETE FROM conversation WHERE id = :id").param("id", id).update();
		dropRecordingIfEmpty((long) row[0]);
		exporter.exportDay((LocalDate) row[1]);
		return 1;
	}

	public int day(LocalDate day) throws IOException {
		List<Long> recordings = db.sql("SELECT DISTINCT recording_id FROM conversation WHERE local_date = :d").param("d", day)
				.query(Long.class).list();
		int n = db.sql("DELETE FROM conversation WHERE local_date = :d").param("d", day).update();
		for (long r : recordings) {
			dropRecordingIfEmpty(r);
		}
		Files.deleteIfExists(exporter.path(day));
		log.info("event=day_deleted day={} conversations={}", day, n);
		return n;
	}

	private void dropRecordingIfEmpty(long recordingId) throws IOException {
		long left = db.sql("SELECT count(*) FROM conversation WHERE recording_id = :r").param("r", recordingId).query(Long.class).single();
		if (left > 0) {
			return;
		}
		var paths = db.sql("SELECT path, work_path FROM recording WHERE id = :r").param("r", recordingId)
				.query((rs, i) -> new String[] { rs.getString(1), rs.getString(2) }).optional().orElse(null);
		db.sql("DELETE FROM recording WHERE id = :r").param("r", recordingId).update();
		if (paths != null) {
			for (String p : paths) {
				if (p != null) {
					Path path = Path.of(p);
					if (Files.isDirectory(path)) {
						try (var walk = Files.walk(path)) {
							for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
								Files.deleteIfExists(f);
							}
						}
					} else {
						Files.deleteIfExists(path);
					}
				}
			}
		}
	}
}
