package com.earshot.api.conversation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.export.MarkdownExporter;

/**
 * Screenshots attached to a meeting (owner request R-1, 2026-10-24): the slide the user captured
 * sits in the transcript at the moment it was taken. A screenshot is often pasted a minute or two
 * after it was taken, so the time it was taken wins when known (macOS puts it in the file name;
 * a dropped file also carries its modified time) and falls inside the meeting. Otherwise it goes
 * where the meeting is now (live) or at the end (attached afterwards).
 */
@Service
public class Attachments {

	private static final Logger log = LoggerFactory.getLogger(Attachments.class);
	static final long MAX_BYTES = 25L * 1024 * 1024;
	private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
	// "Screenshot 2026-10-24 at 11.18.52", "Screen Shot 2026-10-24 at 11.18.52 AM", "CleanShot 2026-10-24 at 11.18.52@2x".
	private static final Pattern TAKEN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}) at (\\d{1,2})\\.(\\d{2})\\.(\\d{2})(?:\\s*([AP]M))?",
			Pattern.CASE_INSENSITIVE);
	// A dropped file's modified time this close to now says nothing (a paste is "modified" now).
	private static final Duration FRESH = Duration.ofSeconds(5);
	private final JdbcClient db;
	private final MarkdownExporter exporter;

	public Attachments(JdbcClient db, MarkdownExporter exporter) {
		this.db = db;
		this.exporter = exporter;
	}

	record Part(long conversationId, Instant start, double durationS, Path folder, LocalDate day, ZoneId zone) {
		Instant end() {
			return start.plusMillis((long) (durationS * 1000));
		}
	}

	public record Added(long id, long conversationId, String placedBy) {
	}

	/** Image type from the first bytes, not the name: only real images are kept. */
	static String imageType(byte[] head) {
		if (head.length >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
			return "image/png";
		}
		if (head.length >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) {
			return "image/jpeg";
		}
		if (head.length >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
			return "image/gif";
		}
		if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'E'
				&& head[10] == 'B' && head[11] == 'P') {
			return "image/webp";
		}
		return null;
	}

	/** When a screenshot was taken, from a macOS / CleanShot file name, in the meeting's zone. */
	static Instant takenFromName(String name, ZoneId zone) {
		if (name == null) {
			return null;
		}
		var m = TAKEN.matcher(name);
		if (!m.find()) {
			return null;
		}
		int h = Integer.parseInt(m.group(2));
		if (m.group(5) != null) {
			boolean pm = m.group(5).equalsIgnoreCase("PM");
			h = h % 12 + (pm ? 12 : 0);
		}
		var t = LocalDateTime.of(LocalDate.parse(m.group(1)), java.time.LocalTime.of(h, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
		return t.atZone(zone).toInstant();
	}

	private List<Part> parts(long meetingId) {
		return db.sql("""
				SELECT c.id, c.started_at, c.end_offset_s - c.start_offset_s, r.path, c.local_date, r.time_zone, c.status
				FROM conversation c JOIN recording r ON r.id = c.recording_id WHERE c.meeting_id = :m ORDER BY c.part""")
				.param("m", meetingId).query((rs, i) -> {
					Instant start = rs.getTimestamp(2).toInstant();
					double dur = rs.getDouble(3);
					if ("live".equals(rs.getString(7))) {
						dur = Math.max(dur, Duration.between(start, Instant.now()).toMillis() / 1000.0);
					}
					String tz = rs.getString(6);
					return new Part(rs.getLong(1), start, dur, Path.of(rs.getString(4)), rs.getObject(5, LocalDate.class),
							tz == null ? ZoneId.systemDefault() : ZoneId.of(tz));
				}).list();
	}

	private static Part containing(List<Part> parts, Instant t) {
		for (Part p : parts) {
			// A little slack at the end: a slide captured as the user pressed Stop still belongs.
			if (!t.isBefore(p.start()) && !t.isAfter(p.end().plusSeconds(60))) {
				return p;
			}
		}
		return null;
	}

	public Added add(long meetingId, String originalName, long size, InputStream in, Long lastModifiedMs, String caption) throws IOException {
		if (size > MAX_BYTES) {
			throw new IllegalArgumentException("A screenshot can be at most 25 MB.");
		}
		byte[] bytes = in.readAllBytes();
		String type = imageType(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 16)));
		if (type == null) {
			throw new IllegalArgumentException("Only images can be attached (PNG, JPEG, GIF or WebP).");
		}
		List<Part> parts = parts(meetingId);
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("No such meeting.");
		}
		Instant now = Instant.now();
		ZoneId zone = parts.getLast().zone();
		Instant taken = takenFromName(originalName, zone);
		if (taken == null && lastModifiedMs != null) {
			Instant modified = Instant.ofEpochMilli(lastModifiedMs);
			if (Duration.between(modified, now).abs().compareTo(FRESH) > 0) {
				taken = modified;
			}
		}
		String placedBy;
		Part part;
		Instant at;
		if (taken != null && (part = containing(parts, taken)) != null) {
			placedBy = "taken";
			at = taken;
		} else if ((part = containing(parts, now)) != null) {
			placedBy = "attached";
			at = now;
		} else {
			part = parts.getLast();
			placedBy = "end";
			at = part.end();
		}
		double anchor = Math.max(0, Math.min(part.durationS(), Duration.between(part.start(), at).toNanos() / 1e9));
		String base = originalName == null || originalName.isBlank() ? "screenshot" : originalName.replaceAll("\\.[A-Za-z0-9]+$", "");
		base = base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
		if (base.length() > 60) {
			base = base.substring(0, 60);
		}
		String ext = switch (type) {
			case "image/png" -> ".png";
			case "image/jpeg" -> ".jpg";
			case "image/gif" -> ".gif";
			default -> ".webp";
		};
		Path dir = part.folder().resolve("attachments");
		Files.createDirectories(dir);
		String stem = at.atZone(zone).format(HHMMSS) + "-" + (base.isEmpty() ? "screenshot" : base);
		Path file = dir.resolve(stem + ext);
		for (int n = 2; Files.exists(file); n++) {
			file = dir.resolve(stem + "-" + n + ext);
		}
		Files.write(file, bytes);
		long id = db.sql("""
				INSERT INTO attachment (conversation_id, path, original_name, content_type, size_bytes, caption, taken_at, anchor_s, placed_by)
				VALUES (:c, :p, :n, :t, :s, :cap, :tk, :a, :pb) RETURNING id""").param("c", part.conversationId()).param("p", file.toString())
				.param("n", originalName).param("t", type).param("s", (long) bytes.length).param("cap", clean(caption))
				.param("tk", taken == null ? null : java.sql.Timestamp.from(taken)).param("a", anchor).param("pb", placedBy)
				.query(Long.class).single();
		exporter.exportDay(part.day());
		log.info("event=attachment_added meeting={} conversation={} id={} placed_by={} anchor_s={}", meetingId, part.conversationId(), id,
				placedBy, Math.round(anchor));
		return new Added(id, part.conversationId(), placedBy);
	}

	private static String clean(String caption) {
		if (caption == null || caption.isBlank()) {
			return null;
		}
		String c = caption.strip().replaceAll("\\s+", " ");
		return c.length() > 300 ? c.substring(0, 300) : c;
	}

	public void caption(long id, String caption) {
		if (db.sql("UPDATE attachment SET caption = :c WHERE id = :id").param("c", clean(caption)).param("id", id).update() == 0) {
			throw new IllegalArgumentException("No such attachment.");
		}
		day(id).ifPresent(exporter::exportDay);
	}

	public void delete(long id) throws IOException {
		var day = day(id);
		var path = db.sql("SELECT path FROM attachment WHERE id = :id").param("id", id).query(String.class).optional();
		db.sql("DELETE FROM attachment WHERE id = :id").param("id", id).update();
		if (path.isPresent()) {
			Files.deleteIfExists(Path.of(path.get()));
		}
		day.ifPresent(exporter::exportDay);
	}

	private java.util.Optional<LocalDate> day(long id) {
		return db.sql("SELECT c.local_date FROM attachment a JOIN conversation c ON c.id = a.conversation_id WHERE a.id = :id").param("id", id)
				.query(LocalDate.class).optional();
	}

	public record File(Path path, String contentType) {
	}

	public File file(long id) {
		return db.sql("SELECT path, content_type FROM attachment WHERE id = :id").param("id", id)
				.query((rs, i) -> new File(Path.of(rs.getString(1)), rs.getString(2))).optional()
				.orElseThrow(() -> new IllegalArgumentException("No such attachment."));
	}
}
