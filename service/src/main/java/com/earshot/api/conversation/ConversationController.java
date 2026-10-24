package com.earshot.api.conversation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;

@RestController
@RequestMapping("/api")
public class ConversationController {

	private final ConversationService conversations;
	private final Deletions deletions;
	private final JdbcClient db;
	private final MarkdownExporter exporter;
	private final Jobs jobs;
	private final JobWorker worker;
	private final com.earshot.api.pipeline.Enricher enricher;

	public ConversationController(ConversationService conversations, Deletions deletions, JdbcClient db, MarkdownExporter exporter,
			Jobs jobs, JobWorker worker, com.earshot.api.pipeline.Enricher enricher) {
		this.enricher = enricher;
		this.conversations = conversations;
		this.deletions = deletions;
		this.db = db;
		this.exporter = exporter;
		this.jobs = jobs;
		this.worker = worker;
	}

	@GetMapping("/days")
	public List<Map<String, Object>> days() {
		return conversations.days();
	}

	@GetMapping("/days/{date}")
	public Map<String, Object> day(@PathVariable LocalDate date) {
		var d = conversations.day(date);
		return Map.of("date", d.date(), "conversations", d.conversations(), "ambientCount", d.ambientCount(), "meetingCount",
				d.meetingCount(), "exportPath", Files.exists(exporter.path(date)) ? exporter.path(date).toString() : "");
	}

	@GetMapping("/conversations/{id}")
	public ConversationService.Detail conversation(@PathVariable long id) {
		return conversations.detail(id);
	}

	public record Done(boolean done) {
	}

	@PatchMapping("/action-items/{id}")
	public Map<String, Object> actionItem(@PathVariable long id, @RequestBody Done req) {
		LocalDate day = db.sql("""
				UPDATE action_item a SET done = :d FROM conversation c WHERE a.id = :id AND c.id = a.conversation_id RETURNING c.local_date""")
				.param("d", req.done()).param("id", id).query(LocalDate.class).single();
		exporter.exportDay(day);
		return Map.of("ok", true);
	}

	/** What a click on Summarize would send for one conversation (no model call). */
	@GetMapping("/conversations/{id}/summary")
	public Map<String, Object> summaryEstimate(@PathVariable long id) {
		var r = db.sql("SELECT count(*), COALESCE(sum(length(text) + 6), 0) FROM segment WHERE conversation_id = :c").param("c", id)
				.query((rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) }).single();
		return Map.of("lines", r[0], "tokens", Math.round((r[1] + 800) / 4.0));
	}

	/** The user clicked Summarize on a conversation: one model call (owner rule, 2026-10-24). */
	@PostMapping("/conversations/{id}/summary")
	public Map<String, Object> summarize(@PathVariable long id) {
		enricher.summarize(id);
		db.sql("SELECT local_date FROM conversation WHERE id = :id").param("id", id).query(LocalDate.class).optional().ifPresent(exporter::exportDay);
		return Map.of("ok", true);
	}

	/** Transcribes an ambient conversation on demand (A-11). */
	@PostMapping("/conversations/{id}/transcribe")
	public Map<String, Object> transcribe(@PathVariable long id) {
		db.sql("UPDATE conversation SET is_ambient = false, status = 'queued' WHERE id = :id").param("id", id).update();
		jobs.enqueue("conversation", null, id, null);
		worker.wake();
		return Map.of("queued", true);
	}

	@DeleteMapping("/conversations/{id}")
	public Map<String, Object> delete(@PathVariable long id) throws IOException {
		return Map.of("deleted", deletions.conversation(id));
	}

	@DeleteMapping("/days/{date}")
	public Map<String, Object> deleteDay(@PathVariable LocalDate date) throws IOException {
		return Map.of("deleted", deletions.day(date));
	}

	/** A span of a recording's working copy as WAV, for click-to-play (A-5). */
	@GetMapping("/audio/{recordingId}")
	public ResponseEntity<StreamingResponseBody> audio(@PathVariable long recordingId, @RequestParam double start,
			@RequestParam double end) {
		String work = db.sql("SELECT work_path FROM recording WHERE id = :id").param("id", recordingId).query(String.class)
				.optional().orElse(null);
		if (work == null || !Files.exists(Path.of(work))) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "audio not available yet");
		}
		double from = Math.max(0, start);
		double to = Math.min(Math.max(end, from + 0.5), from + 600);
		StreamingResponseBody body = out -> {
			var p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-ss", String.valueOf(from), "-to", String.valueOf(to), "-i", work,
					"-f", "wav", "-").start();
			try (InputStream in = p.getInputStream()) {
				in.transferTo(out);
			}
		};
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(body);
	}
}
