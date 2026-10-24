package com.earshot.api.ask;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.earshot.api.llm.Insights;
import com.earshot.api.llm.LlmClient;

/** POST /api/ask streams the answer as server-sent events: token*, citations, done. */
@RestController
public class AskController {

	private static final Logger log = LoggerFactory.getLogger(AskController.class);
	private final AskService asks;
	private final JdbcClient db;

	public AskController(AskService asks, JdbcClient db) {
		this.asks = asks;
		this.db = db;
	}

	@PostMapping(value = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter ask(@RequestBody AskService.Ask req) {
		var emitter = new SseEmitter(300_000L);
		Thread.ofVirtual().start(() -> {
			try {
				asks.ask(req, new AskService.Sink() {
					@Override
					public void token(String t) {
						send(emitter, "token", Map.of("t", t));
					}

					@Override
					public void citations(List<AskService.Citation> c) {
						send(emitter, "citations", c);
					}

					@Override
					public void done(long qaId, String answer) {
						send(emitter, "done", Map.of("qaId", qaId));
					}
				});
				emitter.complete();
			} catch (LlmClient.LlmUnavailable e) {
				send(emitter, "error", Map.of("message", "The AI provider is unavailable: " + e.getMessage()));
				emitter.complete();
			} catch (Insights.Off e) {
				send(emitter, "error", Map.of("message", e.getMessage()));
				emitter.complete();
			} catch (Exception e) {
				log.error("event=ask_failed reason={}", e.toString(), e);
				send(emitter, "error", Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage()));
				emitter.complete();
			}
		});
		return emitter;
	}

	private static void send(SseEmitter emitter, String name, Object data) {
		try {
			emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
		} catch (IOException e) {
			// The browser went away; the answer is still stored.
		}
	}

	@GetMapping("/api/qa")
	public List<Map<String, Object>> history(@RequestParam String scopeType, @RequestParam String scopeRef) {
		return db.sql("""
				SELECT id, question, answer, citations_json::text AS citations, created_at FROM qa
				WHERE scope_type = :t AND scope_ref = :r ORDER BY id""").param("t", scopeType).param("r", scopeRef).query().listOfRows();
	}
}
