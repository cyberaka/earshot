package com.earshot.api.ml;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.earshot.api.config.EarshotProperties;

import tools.jackson.databind.JsonNode;

/** The Python ML and capture processes (PRD §10.3). */
@Component
public class MlClient {

	private final RestClient ml;
	private final RestClient capture;
	private final EarshotProperties.Ml props;

	public MlClient(EarshotProperties props) {
		this.props = props.ml();
		var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
		var factory = new JdkClientHttpRequestFactory(http);
		// HTTP/1.1: uvicorn rejects the JDK client's h2c upgrade and the body is lost.
		// A two-hour conversation takes minutes to diarize and transcribe.
		factory.setReadTimeout(Duration.ofMinutes(60));
		this.ml = RestClient.builder().baseUrl(this.props.baseUrl()).requestFactory(factory)
				.defaultHeader("X-Earshot-Secret", this.props.secret()).build();
		this.capture = RestClient.builder().baseUrl(this.props.captureUrl()).requestFactory(factory)
				.defaultHeader("X-Earshot-Secret", this.props.secret()).build();
	}

	public String secret() {
		return props.secret();
	}

	public String liveEventsUrl(String sessionId) {
		return props.baseUrl().replaceFirst("^http", "ws") + "/ml/live/" + sessionId + "/events?secret=" + props.secret();
	}

	private JsonNode post(RestClient client, String path, Object body) {
		return client.post().uri(path).body(body).retrieve().body(JsonNode.class);
	}

	public JsonNode prepare(String src, String dst) {
		return post(ml, "/ml/prepare", Map.of("src", src, "dst", dst));
	}

	public JsonNode diarize(String path, double start, double end, Object regions, double threshold) {
		return post(ml, "/ml/diarize", Map.of("path", path, "start_s", start, "end_s", end, "regions", regions, "threshold", threshold));
	}

	public JsonNode transcribe(String path, double start, double end, Object regions, String language, Map<String, Object> filters) {
		return transcribe(path, start, end, regions, language, filters, List.of());
	}

	/** {@code vocabulary}: names and terms for Whisper's prompt (R-3). */
	public JsonNode transcribe(String path, double start, double end, Object regions, String language, Map<String, Object> filters,
			List<String> vocabulary) {
		var body = new java.util.HashMap<String, Object>(filters);
		body.putAll(Map.of("path", path, "start_s", start, "end_s", end, "regions", regions, "language", language, "vocabulary", vocabulary));
		return post(ml, "/ml/transcribe", body);
	}

	public void liveVocabulary(String sessionId, List<String> vocabulary) {
		post(ml, "/ml/live/" + sessionId + "/vocabulary", Map.of("vocabulary", vocabulary));
	}

	public float[] embed(String path, List<double[]> ranges) {
		JsonNode n = post(ml, "/ml/embed", Map.of("path", path, "ranges", ranges));
		float[] v = new float[n.get("embedding").size()];
		for (int i = 0; i < v.length; i++) {
			v[i] = (float) n.get("embedding").get(i).asDouble();
		}
		return v;
	}

	public void liveLanguage(String sessionId, String language) {
		post(ml, "/ml/live/" + sessionId + "/language", Map.of("language", language));
	}

	public void liveStart(String sessionId, String language, double maxSegmentS, Map<String, ?> seeds, List<String> vocabulary) {
		post(ml, "/ml/live/" + sessionId + "/start", Map.of("language", language, "max_segment_s", maxSegmentS, "seeds", seeds,
				"vocabulary", vocabulary));
	}

	public void liveStop(String sessionId) {
		post(ml, "/ml/live/" + sessionId + "/stop", Map.of());
	}

	public JsonNode health() {
		return ml.get().uri("/ml/health").retrieve().body(JsonNode.class);
	}

	public void heartbeat() {
		post(ml, "/ml/heartbeat", Map.of());
		post(capture, "/capture/heartbeat", Map.of());
	}

	public JsonNode captureHealth() {
		return capture.get().uri("/capture/health").retrieve().body(JsonNode.class);
	}

	public JsonNode devices() {
		return capture.get().uri("/capture/devices").retrieve().body(JsonNode.class);
	}

	/** Capture refuses a bad device or file with a 400 whose detail is meant for the user. */
	public void captureStart(Map<String, Object> body) {
		try {
			post(capture, "/capture/start", body);
		} catch (org.springframework.web.client.HttpClientErrorException e) {
			String detail = e.getResponseBodyAsString();
			try {
				detail = tools.jackson.databind.json.JsonMapper.builder().build().readTree(detail).path("detail").asString(detail);
			} catch (RuntimeException ignored) {
				// not JSON: use the body as is
			}
			throw new IllegalArgumentException(detail, e);
		}
	}

	public JsonNode captureStatus(String meetingId) {
		return capture.get().uri("/capture/{id}/status", meetingId).retrieve().body(JsonNode.class);
	}

	/** Moves a live recording to another input device; a device that can't be opened is a 400 with the reason. */
	public String captureSwitch(String meetingId, Map<String, Object> body) {
		try {
			return post(capture, "/capture/" + meetingId + "/device", body).path("device").asString();
		} catch (org.springframework.web.client.HttpClientErrorException e) {
			String detail = e.getResponseBodyAsString();
			try {
				detail = tools.jackson.databind.json.JsonMapper.builder().build().readTree(detail).path("detail").asString(detail);
			} catch (RuntimeException ignored) {
				// not JSON: use the body as is
			}
			throw new IllegalArgumentException(detail, e);
		}
	}

	public JsonNode captureStop(String meetingId) {
		return post(capture, "/capture/" + meetingId + "/stop", Map.of());
	}
}
