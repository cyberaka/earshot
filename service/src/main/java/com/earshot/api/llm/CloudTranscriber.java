package com.earshot.api.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * One clicked line sent to OpenAI's transcription API (owner request R-4, 2026-10-25): a second
 * opinion on a badly heard line. Only that line's audio leaves the Mac, and only on the user's click;
 * Anthropic's API takes no audio. The key comes from the environment only (D-3).
 */
@Component
public class CloudTranscriber {

	private static final Logger log = LoggerFactory.getLogger(CloudTranscriber.class);
	// List price of gpt-4o-transcribe when this was written (USD per minute of audio): shown before the click, approximate.
	public static final double USD_PER_MINUTE = 0.006;
	// The same romanized example the speech engine uses for English + Hindi (ml/earshot_ml/asr.py HINGLISH_PROMPT).
	static final String HINGLISH_PROMPT = "Haan bhai, theek hai. Mujhe lagta hai ki yeh kaam aaj karna hai, data ka issue dekh lo.";

	private final JsonMapper json;
	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public CloudTranscriber(JsonMapper json, Environment env) {
		this.json = json;
		this.baseUrl = env.getProperty("OPENAI_BASE_URL", "https://api.openai.com/v1");
		String key = env.getProperty("OPENAI_API_KEY");
		this.apiKey = key == null || key.isBlank() ? null : key;
		this.model = env.getProperty("OPENAI_TRANSCRIBE_MODEL", "gpt-4o-transcribe");
	}

	public boolean available() {
		return apiKey != null;
	}

	public String model() {
		return model;
	}

	/** {@code wav}: the line's audio. {@code language}: Earshot's setting (en, hi, en+hi, auto). */
	public String transcribe(byte[] wav, String language, String terms) throws IOException, InterruptedException {
		if (apiKey == null) {
			throw new IllegalStateException("Set OPENAI_API_KEY in .env and restart Earshot to use OpenAI for this.");
		}
		String boundary = "earshot-" + UUID.randomUUID();
		var body = new ByteArrayOutputStream();
		field(body, boundary, "model", model);
		field(body, boundary, "response_format", "json");
		if ("en".equals(language) || "hi".equals(language)) {
			field(body, boundary, "language", language);
		}
		// English + Hindi is written as Hinglish in Latin letters, as Earshot writes it (owner, 2026-10-25).
		String prompt = String.join(" ", "en+hi".equals(language) ? HINGLISH_PROMPT : "", terms == null ? "" : terms).strip();
		if (!prompt.isEmpty()) {
			field(body, boundary, "prompt", prompt);
		}
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"line.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
				.getBytes(StandardCharsets.UTF_8));
		body.write(wav);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		var req = HttpRequest.newBuilder(URI.create(baseUrl + "/audio/transcriptions")).timeout(Duration.ofSeconds(120))
				.header("Authorization", "Bearer " + apiKey).header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
		var res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			log.warn("event=cloud_transcribe_failed status={} model={}", res.statusCode(), model);
			throw new IllegalStateException("OpenAI could not transcribe this line (HTTP " + res.statusCode() + ").");
		}
		String text = json.readTree(res.body()).path("text").asString("").strip();
		if (echoes(text, prompt)) {
			// On unclear audio the model can return its prompt ("Haan bhai, theek hai…", 23 Oct day): nothing was heard.
			log.info("event=cloud_transcribe_echo model={}", model);
			return "";
		}
		log.info("event=cloud_transcribed model={} bytes={}", model, wav.length);
		return text;
	}

	/** A reply made only of the prompt's words (two or more) is the prompt coming back, not speech. */
	static boolean echoes(String text, String prompt) {
		if (prompt == null || prompt.isBlank() || text == null) {
			return false;
		}
		var vocab = java.util.Set.of(words(prompt));
		var said = words(text);
		return said.length >= 2 && java.util.Arrays.stream(said).allMatch(vocab::contains);
	}

	private static String[] words(String s) {
		return java.util.Arrays.stream(s.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}']+")).filter(w -> !w.isEmpty()).distinct()
				.toArray(String[]::new);
	}

	private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
		out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
				.getBytes(StandardCharsets.UTF_8));
	}
}
