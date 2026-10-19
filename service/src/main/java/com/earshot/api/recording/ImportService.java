package com.earshot.api.recording;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.job.Jobs;
import com.earshot.api.settings.Settings;

@Service
public class ImportService {

	private static final Logger log = LoggerFactory.getLogger(ImportService.class);
	private static final long REFUSE_BELOW_BYTES = 5L * 1024 * 1024 * 1024;
	private final JdbcClient db;
	private final Settings settings;
	private final Jobs jobs;
	private final Path data;

	public ImportService(JdbcClient db, Settings settings, Jobs jobs, EarshotProperties props) {
		this.db = db;
		this.settings = settings;
		this.jobs = jobs;
		this.data = props.data();
	}

	public record Incoming(String name, InputStream content, Instant modifiedAt) {
	}

	public record ImportedFile(Long recordingId, String name, String status, Instant startedAt, String startedAtSource) {
	}

	public long freeBytes() {
		try {
			Files.createDirectories(data);
			return Files.getFileStore(data).getUsableSpace();
		} catch (IOException e) {
			return Long.MAX_VALUE;
		}
	}

	@Transactional
	public Map<String, Object> importFiles(List<Incoming> files, String timeZone, int clockOffsetS, String language) throws IOException {
		if (freeBytes() < REFUSE_BELOW_BYTES) {
			throw new IllegalStateException("Less than 5 GB of free disk; import refused.");
		}
		ZoneId zone = ZoneId.of(timeZone);
		long importId = db.sql("INSERT INTO import (time_zone, clock_offset_s) VALUES (:tz, :off) RETURNING id")
				.param("tz", zone.getId()).param("off", clockOffsetS).query(Long.class).single();
		String pattern = settings.str("recorder_filename_pattern");
		Path tmpDir = data.resolve("tmp");
		Files.createDirectories(tmpDir);
		var out = new ArrayList<ImportedFile>();
		for (Incoming f : files) {
			Path tmp = Files.createTempFile(tmpDir, "upload", ".part");
			String sha = copyHashing(f.content(), tmp);
			Long existing = db.sql("SELECT id FROM recording WHERE sha256 = :s").param("s", sha).query(Long.class).optional().orElse(null);
			if (existing != null) {
				Files.deleteIfExists(tmp);
				out.add(new ImportedFile(existing, f.name(), "duplicate", null, null));
				continue;
			}
			var d = StartTimes.derive(f.name(), pattern, zone, f.modifiedAt());
			Instant started = d.startedAt().plusSeconds(clockOffsetS);
			Path dir = data.resolve("audio/raw").resolve(started.atZone(zone).toLocalDate().toString());
			Files.createDirectories(dir);
			Path dest = dir.resolve(sha.substring(0, 8) + "-" + f.name().replaceAll("[^A-Za-z0-9._-]", "_"));
			Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			long id = db.sql("""
					INSERT INTO recording (import_id, source_type, original_name, path, sha256, started_at, time_zone,
					    started_at_source, file_modified_at, clock_offset_s, language)
					VALUES (:imp, 'recorder', :name, :path, :sha, :start, :tz, :src, :mod, :off, :lang) RETURNING id""")
					.param("imp", importId).param("name", f.name()).param("path", dest.toString()).param("sha", sha)
					.param("start", java.sql.Timestamp.from(started)).param("tz", zone.getId()).param("src", d.source())
					.param("mod", f.modifiedAt() == null ? null : java.sql.Timestamp.from(f.modifiedAt()))
					.param("off", clockOffsetS).param("lang", language).query(Long.class).single();
			out.add(new ImportedFile(id, f.name(), "imported", started.truncatedTo(ChronoUnit.SECONDS), d.source()));
			log.info("event=imported recording={} name={} start_source={}", id, f.name(), d.source());
		}
		return Map.of("importId", importId, "files", out);
	}

	/** Changes the time zone or clock offset of an import before it is processed (A-10). */
	@Transactional
	public void adjust(long importId, String timeZone, int clockOffsetS) {
		var old = db.sql("SELECT time_zone, clock_offset_s FROM import WHERE id = :id").param("id", importId)
				.query((rs, i) -> new Object[] { rs.getString(1), rs.getInt(2) }).single();
		ZoneId oldZone = ZoneId.of((String) old[0]);
		ZoneId zone = ZoneId.of(timeZone);
		int deltaOffset = clockOffsetS - (int) old[1];
		db.sql("UPDATE import SET time_zone = :tz, clock_offset_s = :off WHERE id = :id").param("tz", zone.getId())
				.param("off", clockOffsetS).param("id", importId).update();
		var recs = db.sql("SELECT id, started_at, started_at_source FROM recording WHERE import_id = :id AND status = 'imported'")
				.param("id", importId).query((rs, i) -> new Object[] { rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3) }).list();
		for (Object[] r : recs) {
			Instant t = ((Instant) r[1]).plusSeconds(deltaOffset);
			if ("filename".equals(r[2])) {
				// A filename stamp is local wall time: re-read it in the new zone.
				t = t.atZone(oldZone).toLocalDateTime().atZone(zone).toInstant();
			}
			db.sql("UPDATE recording SET started_at = :t, time_zone = :tz, clock_offset_s = :off WHERE id = :id")
					.param("t", java.sql.Timestamp.from(t)).param("tz", zone.getId()).param("off", clockOffsetS).param("id", r[0]).update();
		}
	}

	@Transactional
	public int process(long importId) {
		var ids = db.sql("SELECT id FROM recording WHERE import_id = :id AND status = 'imported'").param("id", importId)
				.query(Long.class).list();
		for (long id : ids) {
			db.sql("UPDATE recording SET status = 'queued' WHERE id = :id").param("id", id).update();
			jobs.enqueue("prepare", id, null, null);
		}
		return ids.size();
	}

	public List<Map<String, Object>> files(long importId) {
		return db.sql("""
				SELECT id, original_name, started_at, started_at_source, duration_s, status, time_zone
				FROM recording WHERE import_id = :id ORDER BY started_at""").param("id", importId).query().listOfRows();
	}

	private static String copyHashing(InputStream in, Path dest) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (var din = new DigestInputStream(in, md)) {
				Files.copy(din, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
