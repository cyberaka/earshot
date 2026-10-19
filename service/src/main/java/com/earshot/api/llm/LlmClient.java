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
import org.springframework.stereotype.Component;

import com.earshot.api.config.EarshotProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI-compatible chat and embeddings (LM Studio / Ollama). At most one chat request is in
 * flight, so a long answer never stacks GPU work on top of live transcription (PRD §8).
 */
@Component
public class LlmClient {

	private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
	private final JsonMapper json;
	private final String baseUrl;
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

	public LlmClient(JsonMapper json, EarshotProperties props) {
		this.json = json;
		this.baseUrl = props.llm().baseUrl();
		this.timeout = Duration.ofSeconds(props.llm().timeoutSeconds());
	}

	public int queueDepth() {
		return chatSlot.getQueueLength() + (chatSlot.availablePermits() == 0 ? 1 : 0);
	}

	public boolean available() {
		try {
			var res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/models")).timeout(Duration.ofSeconds(3)).build(),
					HttpResponse.BodyHandlers.discarding());
			return res.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	private HttpRequest request(String path, Object body) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
	}

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
				throw new LlmUnavailable("LLM returned " + res.statusCode() + ": " + res.body(), null);
			}
			String content = json.readTree(res.body()).at("/choices/0/message/content").asString();
			log.info("event=llm_chat model={} took_ms={}", model, (System.nanoTime() - t) / 1_000_000);
			return content;
		} catch (IOException e) {
			throw new LlmUnavailable("LLM offline", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		} finally {
			chatSlot.release();
		}
	}

	/** Streams tokens to {@code onToken}; returns the whole text. */
	public String chatStream(String model, List<Message> messages, Consumer<String> onToken) {
		var body = Map.of("model", model, "messages", messages, "temperature", 0.2, "stream", true);
		acquire();
		var out = new StringBuilder();
		try {
			var res = http.send(request("/chat/completions", body), HttpResponse.BodyHandlers.ofLines());
			if (res.statusCode() != 200) {
				throw new LlmUnavailable("LLM returned " + res.statusCode(), null);
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
			throw new LlmUnavailable("LLM offline", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailable("interrupted", e);
		} finally {
			chatSlot.release();
		}
	}

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
			throw new LlmUnavailable("embeddings offline", e);
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
	public JsonNode parseJson(String reply) {
		int a = reply.indexOf('{');
		int b = reply.lastIndexOf('}');
		if (a < 0 || b <= a) {
			throw new IllegalStateException("no JSON in LLM reply: " + reply);
		}
		return json.readTree(reply.substring(a, b + 1));
	}
}
