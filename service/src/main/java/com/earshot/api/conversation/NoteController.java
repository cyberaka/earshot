package com.earshot.api.conversation;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.earshot.api.export.MarkdownExporter;

/** The user's notes on a conversation; each change re-renders that day's markdown. */
@RestController
@RequestMapping("/api")
public class NoteController {

	private static final int MAX_CHARS = 10_000;
	private final JdbcClient db;
	private final MarkdownExporter exporter;

	public NoteController(JdbcClient db, MarkdownExporter exporter) {
		this.db = db;
		this.exporter = exporter;
	}

	public record NoteBody(String text, Double anchorS) {
	}

	private static String clean(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("A note needs some text.");
		}
		if (text.length() > MAX_CHARS) {
			throw new IllegalArgumentException("A note can be at most " + MAX_CHARS + " characters.");
		}
		return text.strip();
	}

	@PostMapping("/conversations/{id}/notes")
	public Map<String, Object> add(@PathVariable long id, @RequestBody NoteBody req) {
		long noteId = db.sql("INSERT INTO note (conversation_id, anchor_s, text) VALUES (:c, :a, :t) RETURNING id")
				.param("c", id).param("a", req.anchorS()).param("t", clean(req.text())).query(Long.class).single();
		reexport("SELECT local_date FROM conversation WHERE id = :id", id);
		return Map.of("id", noteId);
	}

	@PatchMapping("/notes/{id}")
	public Map<String, Object> edit(@PathVariable long id, @RequestBody NoteBody req) {
		int n = db.sql("UPDATE note SET text = :t, updated_at = now() WHERE id = :id").param("t", clean(req.text())).param("id", id).update();
		if (n == 0) {
			throw new IllegalArgumentException("No such note.");
		}
		reexport("SELECT c.local_date FROM note n JOIN conversation c ON c.id = n.conversation_id WHERE n.id = :id", id);
		return Map.of("ok", true);
	}

	@DeleteMapping("/notes/{id}")
	public Map<String, Object> delete(@PathVariable long id) {
		LocalDate day = db.sql("SELECT c.local_date FROM note n JOIN conversation c ON c.id = n.conversation_id WHERE n.id = :id")
				.param("id", id).query(LocalDate.class).optional().orElse(null);
		db.sql("DELETE FROM note WHERE id = :id").param("id", id).update();
		if (day != null) {
			exporter.exportDay(day);
		}
		return Map.of("ok", true);
	}

	private void reexport(String sql, long id) {
		db.sql(sql).param("id", id).query(LocalDate.class).optional().ifPresent(exporter::exportDay);
	}
}
