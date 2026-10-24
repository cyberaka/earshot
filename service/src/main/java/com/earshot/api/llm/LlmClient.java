package com.earshot.api.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI-compatible chat and embeddings: a local server (LM Studio / Ollama) or OpenAI itself,
 * which differ only in base URL and key. At most one chat request is in flight per provider.
 * Which features use it, if any, is up to {@link Insights} (owner decision, 2026-10-24).
 */
public class LlmClient implements ChatClient {

	private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
	private final JsonMapper json;
	private final String name;
	private final String baseUrl;
	private final String apiKey;
	private final Duration timeout;
	private final Semaphore chatSlot = new Semaphore(1, true);

	public record Message(String role, String content) {
		public static Message system(String c) {
			return new Message("system", c);
		}

		public static Message user(String c) {
			return new Message("user", c);
		}
	}

	public static class LlmUnavailable extends RuntimeException {
		public LlmUnavailable(String msg, Throwable cause) {
			super(msg, cause);
		}
	}

	public LlmClient(JsonMapper json, String name, String baseUrl, String apiKey, Duration timeout) {
		this.json = json;
		this.name = name;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
		this.timeout = timeout;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public int queueDepth() {
		return chatSlot.getQueueLength() + (chatSlot.availablePermits() == 0 ? 1 : 0);
	}

	@Override
	public boolean available() {
		try {
			var res = http.send(authorized(HttpRequest.newBuilder(URI.create(baseUrl + "/models"))).timeout(Duration.ofSeconds(3)).build(),
					HttpResponse.BodyHandlers.discarding());
			return res.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	private HttpRequest.Builder authorized(HttpRequest.Builder b) {
		return apiKey == null ? b : b.header("Authorization", "Bearer " + apiKey);
	}

	private HttpRequest request(String path, Object body) {
		return authorized(HttpRequest.newBuilder(URI.create(baseUrl + path))).timeout(timeout).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
	}

	@Override
	public String chat(String model, List<Message> messages, boolean jsonMode) {
		var body = new java.util.HashMap<String, Object>(Map.of("model", model, "messages", messages, "temperature", 0.2));
		if (jsonMode) {
			body.put("response_format", Map.of("type", "json_object"));
		}
		acquire();
		long t = System.nanoTime();
		try {
			var res = http.send(request("/chat/completions", body), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200 && jsonMode) {
				// Some servers reject response_format; the prompt asks for JSON anyway.
				body.remove("response_format");
				res = http.send(request("/chat/completions", body), HttpResponse.BodyHandlers.ofString());
			}
			if (res.statusCode() != 200) {
				throw new LlmUnavailable(name + " returned " + res.statusCode() + ": " + res.body(), null);
			}
			String content = json.readTree(res.body()).at("/choices/0/message/content").asString();
			log.info("event=llm_chat provider={} model={} took_ms={}", name, model, (System.nanoTime() - t) / 1_000_000);
			return content;
		} catch (IOException e) {
			throw new LlmUnavailable(name + " unreachable", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		} finally {
			chatSlot.release();
		}
	}

	/** Streams tokens to {@code onToken}; returns the whole text. */
	@Override
	public String chatStream(String model, List<Message> messages, Consumer<String> onToken) {
		var body = Map.of("model", model, "messages", messages, "temperature", 0.2, "stream", true);
		acquire();
		var out = new StringBuilder();
		try {
			var res = http.send(request("/chat/completions", body), HttpResponse.BodyHandlers.ofLines());
			if (res.statusCode() != 200) {
				throw new LlmUnavailable(name + " returned " + res.statusCode(), null);
			}
			res.body().forEach(line -> {
				if (!line.startsWith("data: ") || line.equals("data: [DONE]")) {
					return;
				}
				JsonNode delta = json.readTree(line.substring(6)).at("/choices/0/delta/content");
				if (!delta.isMissingNode() && !delta.isNull()) {
					String tok = delta.asString();
					out.append(tok);
					onToken.accept(tok);
				}
			});
			return out.toString();
		} catch (IOException e) {
			throw new LlmUnavailable(name + " unreachable", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		} finally {
			chatSlot.release();
		}
	}

	@Override
	public float[][] embed(String model, List<String> texts) {
		try {
			var res = http.send(request("/embeddings", Map.of("model", model, "input", texts)), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				throw new LlmUnavailable("embeddings returned " + res.statusCode() + ": " + res.body(), null);
			}
			JsonNode data = json.readTree(res.body()).get("data");
			float[][] out = new float[data.size()][];
			for (JsonNode d : data) {
				JsonNode e = d.get("embedding");
				float[] v = new float[e.size()];
				for (int i = 0; i < v.length; i++) {
					v[i] = (float) e.get(i).asDouble();
				}
				out[d.get("index").asInt()] = v;
			}
			return out;
		} catch (IOException e) {
			throw new LlmUnavailable(name + " embeddings unreachable", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		}
	}

	private void acquire() {
		try {
			chatSlot.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		}
	}

	/** Pulls the first JSON object out of a reply that may wrap it in prose or code fences. */
	@Override
	public JsonNode parseJson(String reply) {
		int a = reply.indexOf('{');
		int b = reply.lastIndexOf('}');
		if (a < 0 || b <= a) {
			throw new IllegalStateException("no JSON in LLM reply: " + reply);
		}
		return json.readTree(reply.substring(a, b + 1));
	}
}
