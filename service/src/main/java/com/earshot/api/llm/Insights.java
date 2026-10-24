package com.earshot.api.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.settings.Settings;

import tools.jackson.databind.json.JsonMapper;

/**
 * The optional AI layer (owner decision, 2026-10-24). Earshot's job is transcription and
 * diarization; summaries, Q&A and semantic search are extras, each off unless the user picks a
 * provider for it: a local server, OpenAI or Anthropic. Each runs only when the user clicks its
 * button, never on a timer or after a job (owner rule, 2026-10-24): a local call costs GPU, a cloud
 * call costs money. During a live meeting a click is still honoured (owner, 2026-10-24: "the user
 * knows why"; a 32 GB Mac has room): a local model then shares the GPU with Whisper, which the UI
 * warns about and the log records.
 */
@Service
public class Insights {

	private static final Logger log = LoggerFactory.getLogger(Insights.class);

	public enum Feature {
		LIVE_SUMMARY("live_summary", "Summary during a meeting"), MEETING_SUMMARY("meeting_summary", "Summary after a meeting"), ASK("ask", "Ask"),
		SEMANTIC_SEARCH("semantic_search", "Semantic search");

		final String key;
		final String label;

		Feature(String key, String label) {
			this.key = key;
			this.label = label;
		}

		public String settingKey() {
			return "insights_" + key;
		}

		public String label() {
			return label;
		}
	}

	public enum Provider {
		OFF, LOCAL, OPENAI, ANTHROPIC;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public boolean cloud() {
			return this == OPENAI || this == ANTHROPIC;
		}
	}

	/** The feature is switched off in Settings. */
	public static class Off extends RuntimeException {
		public Off(Feature f) {
			super(f.label() + " is off. Turn it on in Settings → Insights.");
		}
	}

	private final Settings settings;
	private final LlmClient local;
	private final LlmClient openai;
	private final AnthropicClient anthropic;
	private final boolean openaiKey;
	private final boolean anthropicKey;
	private volatile BooleanSupplier liveMeeting = () -> false;

	public Insights(Settings settings, JsonMapper json, EarshotProperties props, Environment env) {
		this.settings = settings;
		var timeout = Duration.ofSeconds(props.llm().timeoutSeconds());
		this.local = new LlmClient(json, "local", props.llm().baseUrl(), null, timeout);
		// Keys come from the environment or .env only (owner decision D-3): never the database or the UI.
		String ok = env.getProperty("OPENAI_API_KEY");
		String ak = env.getProperty("ANTHROPIC_API_KEY");
		this.openaiKey = ok != null && !ok.isBlank();
		this.anthropicKey = ak != null && !ak.isBlank();
		this.openai = new LlmClient(json, "openai", env.getProperty("OPENAI_BASE_URL", "https://api.openai.com/v1"), ok, timeout);
		this.anthropic = new AnthropicClient(json, env.getProperty("ANTHROPIC_BASE_URL", "https://api.anthropic.com"), ak, timeout);
	}

	public void setLiveMeeting(BooleanSupplier live) {
		this.liveMeeting = live;
	}

	public Provider provider(Feature f) {
		String v = settings.str(f.settingKey());
		try {
			return v == null ? Provider.OFF : Provider.valueOf(v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return Provider.OFF;
		}
	}

	public boolean enabled(Feature f) {
		return provider(f) != Provider.OFF;
	}

	/** The client for a feature the user just clicked, or {@link Off}. */
	public ChatClient client(Feature f) {
		Provider p = provider(f);
		if (p == Provider.OFF) {
			throw new Off(f);
		}
		if (p == Provider.LOCAL && liveMeeting.getAsBoolean()) {
			log.warn("event=local_model_during_live feature={} reason=user_click", f.key);
		}
		return clientFor(p);
	}

	private ChatClient clientFor(Provider p) {
		return switch (p) {
			case LOCAL -> local;
			case OPENAI -> openai;
			case ANTHROPIC -> anthropic;
			case OFF -> throw new IllegalStateException("off");
		};
	}

	/** Chat model for a feature, or the embedding model for semantic search. */
	public String model(Feature f) {
		Provider p = provider(f);
		return f == Feature.SEMANTIC_SEARCH ? settings.str("insights_embedding_" + p.id()) : settings.str("insights_model_" + p.id());
	}

	/** Rejects choices that can't work: Anthropic for embeddings. */
	public static void validate(String key, Object value) {
		for (Feature f : Feature.values()) {
			if (!f.settingKey().equals(key)) {
				continue;
			}
			Provider p;
			try {
				p = Provider.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(f.label() + ": choose off, local, openai or anthropic.");
			}
			if (f == Feature.SEMANTIC_SEARCH && p == Provider.ANTHROPIC) {
				throw new IllegalArgumentException("Anthropic has no embeddings API: choose local or openai for semantic search.");
			}
		}
	}

	/** Per feature: provider and whether it sends transcript text off the Mac; per provider: readiness. */
	public Map<String, Object> status() {
		var features = new LinkedHashMap<String, Object>();
		var used = new ArrayList<Provider>();
		var cloud = new ArrayList<String>();
		for (Feature f : Feature.values()) {
			Provider p = provider(f);
			features.put(f.key, p.id());
			if (p != Provider.OFF && !used.contains(p)) {
				used.add(p);
			}
			if (p.cloud()) {
				cloud.add(f.label() + " → " + p.id());
			}
		}
		var providers = new LinkedHashMap<String, Object>();
		providers.put("local", Map.of("used", used.contains(Provider.LOCAL), "up", used.contains(Provider.LOCAL) && local.available()));
		providers.put("openai", Map.of("used", used.contains(Provider.OPENAI), "keySet", openaiKey));
		providers.put("anthropic", Map.of("used", used.contains(Provider.ANTHROPIC), "keySet", anthropicKey));
		var out = new LinkedHashMap<String, Object>();
		out.put("features", features);
		out.put("providers", providers);
		out.put("sendsTextOff", cloud);
		out.put("queue", used.stream().mapToInt(p -> clientFor(p).queueDepth()).sum());
		return out;
	}

	/** Logged once at start, so the log says which features send text where. */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	public void logChoices() {
		var parts = new ArrayList<String>();
		for (Feature f : Feature.values()) {
			parts.add(f.key + "=" + provider(f).id());
		}
		log.info("event=insights {} openai_key={} anthropic_key={}", String.join(" ", parts), openaiKey, anthropicKey);
	}

	public List<Feature> features() {
		return List.of(Feature.values());
	}
}
