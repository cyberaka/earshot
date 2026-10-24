package com.earshot.api.settings;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	private final Settings settings;

	public SettingsController(Settings settings) {
		this.settings = settings;
	}

	@GetMapping
	public Map<String, Object> get() {
		return settings.all();
	}

	@PutMapping
	public Map<String, Object> put(@RequestBody Map<String, Object> changes) {
		changes.forEach(com.earshot.api.llm.Insights::validate);
		changes.forEach(settings::put);
		return settings.all();
	}
}
