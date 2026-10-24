package com.earshot.api.health;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.settings.Settings;

/**
 * Daily backup and retention (PRD §9.4, §7). Runs an hour after start and then every hour,
 * but only does work once per 24 h, because a laptop is often asleep at night.
 */
@Component
public class Housekeeping {

	private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);
	private final JdbcClient db;
	private final Settings settings;
	private final Path backups;

	private final String pgDump;
	private final String dbUrl;
	private final String dbUser;
	private final String dbPassword;

	public Housekeeping(JdbcClient db, Settings settings, EarshotProperties props, @Value("${earshot.pg-dump}") String pgDump,
			@Value("${spring.datasource.url}") String dbUrl, @Value("${spring.datasource.username}") String dbUser,
			@Value("${spring.datasource.password}") String dbPassword) {
		this.db = db;
		this.settings = settings;
		this.backups = props.data().resolve("backups");
		this.pgDump = pgDump;
		this.dbUrl = dbUrl;
		this.dbUser = dbUser;
		this.dbPassword = dbPassword;
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
	public void run() {
		backup();
		retention();
	}

	void backup() {
		try {
			Files.createDirectories(backups);
			Path today = backups.resolve("earshot-" + LocalDate.now() + ".sql.gz");
			try (Stream<Path> files = Files.list(backups)) {
				var recent = files.filter(p -> p.getFileName().toString().endsWith(".sql.gz")).anyMatch(p -> {
					try {
						return Files.getLastModifiedTime(p).toInstant().isAfter(Instant.now().minus(24, ChronoUnit.HOURS));
					} catch (IOException e) {
						return false;
					}
				});
				if (recent) {
					return;
				}
			}
			// jdbc:postgresql://host:port/db -> pg_dump -h host -p port db (native Postgres, decision D-5).
			var uri = java.net.URI.create(dbUrl.substring("jdbc:".length()));
			var pb = new ProcessBuilder(pgDump, "-h", uri.getHost(), "-p", String.valueOf(uri.getPort()), "-U", dbUser, uri.getPath().substring(1));
			pb.environment().put("PGPASSWORD", dbPassword);
			Path errors = backups.resolve("backup-errors.txt");
			pb.redirectError(errors.toFile());
			Process p = pb.start();
			try (var in = p.getInputStream(); var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(today))) {
				in.transferTo(out);
			}
			if (p.waitFor() != 0) {
				log.error("event=backup_failed output={}", Files.readString(errors).strip());
				Files.deleteIfExists(today);
				return;
			}
			Files.deleteIfExists(errors);
			int keep = (int) settings.num("backup_retention_days");
			try (Stream<Path> files = Files.list(backups)) {
				for (Path old : files.filter(f -> f.getFileName().toString().endsWith(".sql.gz")).sorted().toList().reversed().stream().skip(keep).toList()) {
					Files.deleteIfExists(old);
				}
			}
			log.info("event=backup_done file={}", today.getFileName());
		} catch (Exception e) {
			log.error("event=backup_failed reason={}", e.toString());
		}
	}

	void retention() {
		int rawDays = (int) settings.num("raw_audio_retention_days");
		if (rawDays >= 0) {
			// The working copy is the playback source, so raw audio is redundant once processed.
			var old = db.sql("""
					SELECT id, path FROM recording WHERE source_type = 'recorder' AND path IS NOT NULL AND work_path IS NOT NULL
					  AND status = 'segmented' AND created_at < now() - make_interval(days => :d)
					  AND NOT EXISTS (SELECT 1 FROM conversation c WHERE c.recording_id = recording.id AND c.status <> 'done')""")
					.param("d", rawDays).query((rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }).list();
			for (Object[] r : old) {
				try {
					Files.deleteIfExists(Path.of((String) r[1]));
					db.sql("UPDATE recording SET path = NULL WHERE id = :id").param("id", r[0]).update();
				} catch (IOException e) {
					log.warn("event=raw_delete_failed recording={}", r[0]);
				}
			}
			if (!old.isEmpty()) {
				log.info("event=raw_audio_deleted count={}", old.size());
			}
		}
		int unknownDays = (int) settings.num("unknown_voiceprint_retention_days");
		int n = db.sql("""
				UPDATE speaker_cluster SET embedding = NULL WHERE person_id IS NULL AND embedding IS NOT NULL
				  AND created_at < now() - make_interval(days => :d)""").param("d", unknownDays).update();
		if (n > 0) {
			log.info("event=unknown_voiceprints_expired count={}", n);
		}
	}
}
