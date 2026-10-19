package com.earshot.api.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Boot config (PRD §9.1). Runtime settings live in the setting table; see {@code Settings}. */
@ConfigurationProperties("earshot")
public record EarshotProperties(String dataDir, List<String> allowedOrigins, Ml ml, Llm llm, Map<String, Object> defaults) {

	public record Ml(String baseUrl, String captureUrl, String secret, boolean manage, String python, String workdir) {
	}

	public record Llm(String baseUrl, int timeoutSeconds) {
	}

	public Path data() {
		return Path.of(dataDir).toAbsolutePath().normalize();
	}
}
