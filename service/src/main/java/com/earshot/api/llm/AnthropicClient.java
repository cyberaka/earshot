package com.earshot.api.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.earshot.api.llm.LlmClient.LlmUnavailable;
import com.earshot.api.llm.LlmClient.Message;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Anthropic's Messages API. It has no embeddings, so semantic search can't use it. */
public class AnthropicClient implements ChatClient {

	private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
	private static final String VERSION = "2023-06-01";
	private static final int MAX_TOKENS = 2048;
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
	private final JsonMapper json;
	private final String baseUrl;
	private final String apiKey;
	private final Duration timeout;
	private final Semaphore slot = new Semaphore(1, true);

	public AnthropicClient(JsonMapper json, String baseUrl, String apiKey, Duration timeout) {
		this.json = json;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
		this.timeout = timeout;
	}

	@Override
	public String name() {
		return "anthropic";
	}

	@Override
	public int queueDepth() {
		return slot.getQueueLength() + (slot.availablePermits() == 0 ? 1 : 0);
	}

	@Override
	public boolean available() {
		return apiKey != null;
	}

	/** The system prompt is a top-level field; the other messages alternate user/assistant. */
	private Map<String, Object> body(String model, List<Message> messages, boolean stream) {
		var system = new StringBuilder();
		var turns = new ArrayList<Map<String, String>>();
		for (Message m : messages) {
			if ("system".equals(m.role())) {
				system.append(system.isEmpty() ? "" : "\n\n").append(m.content());
			} else {
				turns.add(Map.of("role", m.role(), "content", m.content()));
			}
		}
		var body = new HashMap<String, Object>(Map.of("model", model, "max_tokens", MAX_TOKENS, "messages", turns, "temperature", 0.2));
		if (!system.isEmpty()) {
			body.put("system", system.toString());
		}
		if (stream) {
			body.put("stream", true);
		}
		return body;
	}

	private HttpRequest request(Map<String, Object> body) {
		if (apiKey == null) {
			throw new LlmUnavailable("ANTHROPIC_API_KEY is not set", null);
		}
		return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages")).timeout(timeout).header("Content-Type", "application/json")
				.header("x-api-key", apiKey).header("anthropic-version", VERSION)
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
	}

	@Override
	public String chat(String model, List<Message> messages, boolean jsonMode) {
		// No JSON mode in the Messages API; the prompts ask for JSON and parseJson finds it.
		acquire();
		long t = System.nanoTime();
		try {
			var res = http.send(request(body(model, messages, false)), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				throw new LlmUnavailable("anthropic returned " + res.statusCode() + ": " + res.body(), null);
			}
			var out = new StringBuilder();
			json.readTree(res.body()).path("content").forEach(c -> {
				if ("text".equals(c.path("type").asString())) {
					out.append(c.path("text").asString());
				}
			});
			log.info("event=llm_chat provider=anthropic model={} took_ms={}", model, (System.nanoTime() - t) / 1_000_000);
			return out.toString();
		} catch (IOException e) {
			throw new LlmUnavailable("anthropic unreachable", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		} finally {
			slot.release();
		}
	}

	@Override
	public String chatStream(String model, List<Message> messages, Consumer<String> onToken) {
		acquire();
		var out = new StringBuilder();
		try {
			var res = http.send(request(body(model, messages, true)), HttpResponse.BodyHandlers.ofLines());
			if (res.statusCode() != 200) {
				throw new LlmUnavailable("anthropic returned " + res.statusCode(), null);
			}
			res.body().forEach(line -> {
				if (!line.startsWith("data: ")) {
					return;
				}
				JsonNode ev = json.readTree(line.substring(6));
				if ("content_block_delta".equals(ev.path("type").asString()) && "text_delta".equals(ev.at("/delta/type").asString())) {
					String tok = ev.at("/delta/text").asString();
					out.append(tok);
					onToken.accept(tok);
				} else if ("error".equals(ev.path("type").asString())) {
					throw new LlmUnavailable("anthropic: " + ev.at("/error/message").asString(), null);
				}
			});
			return out.toString();
		} catch (IOException e) {
			throw new LlmUnavailable("anthropic unreachable", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		} finally {
			slot.release();
		}
	}

	@Override
	public float[][] embed(String model, List<String> texts) {
		throw new UnsupportedOperationException("Anthropic has no embeddings API");
	}

	@Override
	public JsonNode parseJson(String reply) {
		int a = reply.indexOf('{');
		int b = reply.lastIndexOf('}');
		if (a < 0 || b <= a) {
			throw new IllegalStateException("no JSON in reply: " + reply);
		}
		try {
			return json.readTree(reply.substring(a, b + 1));
		} catch (tools.jackson.core.JacksonException e) {
			log.warn("event=llm_bad_json provider={} reply_start={}", name(), reply.substring(0, Math.min(reply.length(), 600)).replace('\n', ' '));
			throw new IllegalStateException("The model's reply was not valid JSON, so nothing was saved. Click again to retry.");
		}
	}

	private void acquire() {
		try {
			slot.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		}
	}
}
