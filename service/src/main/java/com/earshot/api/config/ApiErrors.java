package com.earshot.api.config;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.earshot.api.llm.Insights;
import com.earshot.api.llm.LlmClient;

/** Errors the UI can show as they are: bad input, a conflicting state, an AI provider being unavailable. */
@RestControllerAdvice
public class ApiErrors {

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", String.valueOf(e.getMessage())));
	}

	@ExceptionHandler(LlmClient.LlmUnavailable.class)
	public ResponseEntity<Map<String, String>> llmOffline(LlmClient.LlmUnavailable e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "The AI provider is unavailable: " + e.getMessage()));
	}

	/** An optional AI feature that is off, or local while a meeting is live: say which and why. */
	@ExceptionHandler({ Insights.Off.class, Insights.HeldForLive.class })
	public ResponseEntity<Map<String, String>> insightsUnavailable(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", String.valueOf(e.getMessage())));
	}
}
