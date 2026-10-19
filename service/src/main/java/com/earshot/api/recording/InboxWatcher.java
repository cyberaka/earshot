package com.earshot.api.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.settings.Settings;

/** Imports files dropped into data/inbox once they stop growing (A-1). */
@Component
public class InboxWatcher {

	private static final Logger log = LoggerFactory.getLogger(InboxWatcher.class);
	private static final long STABLE_MS = 10_000;
	private final ImportService imports;
	private final Settings settings;
	private final Path inbox;
	private final Map<Path, long[]> seen = new HashMap<>(); // path -> {size, since}

	public InboxWatcher(ImportService imports, Settings settings, EarshotProperties props) {
		this.imports = imports;
		this.settings = settings;
		this.inbox = props.data().resolve("inbox");
	}

	@Scheduled(fixedDelay = 5_000)
	public void scan() throws IOException {
		Files.createDirectories(inbox);
		long now = System.currentTimeMillis();
		try (Stream<Path> files = Files.list(inbox)) {
			for (Path p : files.filter(Files::isRegularFile).filter(p -> p.toString().matches("(?i).*\\.(wav|mp3|m4a|flac)$")).toList()) {
				long size = Files.size(p);
				long[] prev = seen.get(p);
				if (prev == null || prev[0] != size) {
					seen.put(p, new long[] { size, now });
				} else if (now - prev[1] >= STABLE_MS) {
					seen.remove(p);
					importOne(p);
				}
			}
		}
	}

	private void importOne(Path p) {
		try (var in = Files.newInputStream(p)) {
			Instant mod = Files.getLastModifiedTime(p).toInstant();
			var result = imports.importFiles(List.of(new ImportService.Incoming(p.getFileName().toString(), in, mod)),
					ZoneId.systemDefault().getId(), 0, settings.str("language"));
			imports.process((Long) result.get("importId"));
			Files.delete(p);
			log.info("event=inbox_imported file={}", p.getFileName());
		} catch (Exception e) {
			log.error("event=inbox_import_failed file={} reason={}", p.getFileName(), e.getMessage());
		}
	}
}
