package com.earshot.api.recording;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/** Recording start time (PRD §5.4): filename first, then modified time minus duration. */
public final class StartTimes {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private StartTimes() {
	}

	public record Derived(Instant startedAt, String source) {
	}

	/** {@code pattern} must capture the date (yyyyMMdd) and the time (HHmmss) as groups 1 and 2. */
	public static Optional<Instant> fromFilename(String name, String pattern, ZoneId zone) {
		var m = Pattern.compile(pattern).matcher(name);
		if (!m.find() || m.groupCount() < 2) {
			return Optional.empty();
		}
		try {
			return Optional.of(LocalDateTime.parse(m.group(1) + m.group(2), STAMP).atZone(zone).toInstant());
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	/**
	 * Before the duration is known a modified-time start is provisional; the pipeline subtracts
	 * the duration once ffprobe has run (most recorders write mtime when a file closes).
	 */
	public static Derived derive(String name, String pattern, ZoneId zone, Instant modifiedAt) {
		return fromFilename(name, pattern, zone).map(t -> new Derived(t, "filename"))
				.orElseGet(() -> modifiedAt != null ? new Derived(modifiedAt, "mtime") : new Derived(Instant.now(), "import_time"));
	}
}
