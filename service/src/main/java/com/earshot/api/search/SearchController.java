package com.earshot.api.search;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

	private final SearchService search;

	public SearchController(SearchService search) {
		this.search = search;
	}

	@GetMapping("/api/search")
	public List<SearchService.Hit> search(@RequestParam String q,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "20") int limit) {
		return search.search(q, new SearchService.Scope(from, to, null), Math.min(limit, 100));
	}
}
