package com.earshot.api.meeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.conversation.ConversationService;

/**
 * The meeting transcript as a plain file, kept current while the meeting runs, for an outside
 * agent to read (owner decision, 2026-10-24: Earshot transcribes; what happens with the transcript
 * is the user's choice). {@code data/live/meeting-<id>.md} per meeting, and {@code current.md}
 * for the meeting being recorded. Each write replaces the whole file atomically, so renamed
 * speakers and moved lines are right everywhere and a reader never sees half a file.
 */
@Service
public class LiveTranscript {

	private static final Logger log = LoggerFactory.getLogger(LiveTranscript.class);
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
	// Lines arrive a few per second at most; one write per burst keeps the file within ~1 s.
	private static final long DEBOUNCE_MS = 500;
	private final JdbcClient db;
	private final ConversationService conversations;
	private final Path dir;
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("live-transcript").factory());
	private final Set<Long> pending = ConcurrentHashMap.newKeySet();
	private final Map<Long, Double> lag = new ConcurrentHashMap<>();
	private volatile Long current;

	public LiveTranscript(JdbcClient db, ConversationService conversations, EarshotProperties props) {
		this.db = db;
		this.conversations = conversations;
		this.dir = props.data().resolve("live");
	}

	public Path dir() {
		return dir;
	}

	/** The meeting now being recorded, or null after Stop; current.md follows it. */
	public void setCurrent(Long meetingId) {
		this.current = meetingId;
		if (meetingId != null) {
			changed(meetingId);
		}
	}

	public void lag(long meetingId, double lagS) {
		lag.put(meetingId, lagS);
	}

	/** Something in the meeting's transcript changed: rewrite its file shortly. */
	public void changed(long meetingId) {
		if (pending.add(meetingId)) {
			exec.schedule(() -> {
				pending.remove(meetingId);
				write(meetingId);
			}, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
		}
	}

	/** Names or lines changed on a day: rewrite the files of its meetings that have one. */
	public void dayChanged(java.time.LocalDate day) {
		db.sql("SELECT DISTINCT meeting_id FROM conversation WHERE local_date = :d AND meeting_id IS NOT NULL").param("d", day)
				.query(Long.class).list().stream().filter(m -> Files.exists(dir.resolve("meeting-" + m + ".md"))).forEach(this::changed);
	}

	/** The meeting was deleted: its transcript file goes too (and current.md, if it showed it). */
	public void forget(long meetingId) {
		try {
			Files.deleteIfExists(dir.resolve("meeting-" + meetingId + ".md"));
			Long cur = current;
			if ((cur == null || cur != meetingId) && wasCurrent(meetingId)) {
				Files.deleteIfExists(dir.resolve("current.md"));
			}
		} catch (IOException e) {
			log.warn("event=live_transcript_forget_failed meeting={} reason={}", meetingId, e.toString());
		}
	}

	/** Writes now (after Stop and after the catch-up pass). */
	public void writeNow(long meetingId) {
		write(meetingId);
	}

	private void write(long meetingId) {
		try {
			String text = render(meetingId);
			Files.createDirectories(dir);
			replace(dir.resolve("meeting-" + meetingId + ".md"), text);
			Long cur = current;
			if (cur != null && cur == meetingId) {
				replace(dir.resolve("current.md"), text);
			} else if (cur == null && Files.exists(dir.resolve("current.md")) && wasCurrent(meetingId)) {
				// Stopped: current.md keeps the last meeting, now marked as no longer recording.
				replace(dir.resolve("current.md"), text);
			}
		} catch (Exception e) {
			log.warn("event=live_transcript_failed meeting={} reason={}", meetingId, e.toString());
		}
	}

	private boolean wasCurrent(long meetingId) {
		try {
			String head = Files.readString(dir.resolve("current.md"), StandardCharsets.UTF_8);
			return head.contains("\nMeeting " + meetingId + " ·");
		} catch (IOException e) {
			return false;
		}
	}

	private static void replace(Path path, String text) throws IOException {
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	String render(long meetingId) {
		var m = db.sql("SELECT title, status, started_at FROM meeting WHERE id = :id").param("id", meetingId)
				.query((rs, i) -> new Object[] { rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant() }).single();
		List<Long> parts = db.sql("SELECT id FROM conversation WHERE meeting_id = :m ORDER BY part").param("m", meetingId).query(Long.class).list();
		Long cur = current;
		boolean recording = cur != null && cur == meetingId;
		String state = recording ? "RECORDING" : "processing".equals(m[1]) ? "STOPPED, finishing" : "STOPPED";
		var sb = new StringBuilder();
		sb.append("# ").append(m[0] == null ? "Meeting " + meetingId : m[0]).append("\n\n");
		sb.append("Meeting ").append(meetingId).append(" · ").append(state).append(" · started ").append(m[2]).append(" · written ")
				.append(LocalTime.now().format(HMS)).append('\n');
		if (recording) {
			double l = lag.getOrDefault(meetingId, 0.0);
			sb.append(l < 5 ? "Transcript is current (within a few seconds of the room).\n"
					: "Transcript is about %d s behind the room.\n".formatted(Math.round(l)));
		}
		int n = 0;
		for (long conv : parts) {
			var d = conversations.detail(conv);
			n++;
			sb.append("\n## Part ").append(n).append(" (").append(d.date()).append(' ').append(d.startClock()).append(")\n\n");
			for (var s : d.segments()) {
				sb.append('[').append(s.clock()).append("] ").append(s.speaker()).append(": ").append(s.text()).append('\n');
			}
			for (var note : conversations.notes(conv)) {
				sb.append("[note").append(note.clock() == null ? "" : " " + note.clock()).append("] ").append(note.text().replace('\n', ' ')).append('\n');
			}
		}
		return sb.toString();
	}
}
