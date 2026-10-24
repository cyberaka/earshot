package com.earshot.api.search;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

	private final SearchService search;
	private final Indexer indexer;

	public SearchController(SearchService search, Indexer indexer) {
		this.search = search;
		this.indexer = indexer;
	}

	@GetMapping("/api/search")
	public Map<String, Object> search(@RequestParam String q,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "false") boolean meaning) {
		var scope = new SearchService.Scope(from, to, null);
		return Map.of("transcript", search.search(q, scope, Math.min(limit, 100), meaning), "notes", search.searchNotes(q, scope, 20));
	}

	/** How many lines a click on "Prepare search by meaning" would embed. */
	@GetMapping("/api/search/index")
	public Map<String, Object> indexStatus() {
		return indexer.status();
	}

	/** Embeds every line not yet embedded: only on the user's click (owner rule, 2026-10-24). */
	@PostMapping("/api/search/index")
	public Map<String, Object> index() {
		return Map.of("embedded", indexer.indexAll());
	}
}
