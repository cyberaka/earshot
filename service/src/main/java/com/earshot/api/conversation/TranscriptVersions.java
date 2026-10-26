package com.earshot.api.conversation;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.llm.CloudTranscriber;
import com.earshot.api.speaker.SpeakerNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A whole conversation transcribed by OpenAI, kept as a version beside Earshot's (owner request,
 * 2026-10-26): "the control has to be in the hand of the user". The user compares the two side by
 * side and picks which one the conversation uses; switching back restores Earshot's lines exactly,
 * edits and speakers included, and no version is ever deleted.
 *
 * The lines in use are always the conversation's segments, so the page, search, the day's transcript
 * file and the live feed need no change. The version not in use waits in transcript_version.
 */
@Service
public class TranscriptVersions {

	private static final Logger log = LoggerFactory.getLogger(TranscriptVersions.class);
	// The diarizing model takes about 23 minutes of audio in one call; a longer conversation goes in pieces.
	static final double ONE_CALL_MAX_S = 23 * 60;
	static final double PIECE_S = 20 * 60;
	// A piece ends at the longest pause within two minutes of its 20-minute mark.
	static final double PAUSE_SEARCH_S = 120;
	private static final int PARALLEL_PIECES = 3;
	// OpenAI's speakers become clusters with this label prefix while their version is in use.
	public static final String LABEL_PREFIX = "openai:";

	private final JdbcClient db;
	private final JsonMapper json;
	private final CloudTranscriber cloud;
	private final MarkdownExporter exporter;
	private final SpeakerNames names;
	private final TransactionTemplate tx;

	public TranscriptVersions(JdbcClient db, JsonMapper json, CloudTranscriber cloud, MarkdownExporter exporter, SpeakerNames names,
			PlatformTransactionManager transactions) {
		this.db = db;
		this.json = json;
		this.cloud = cloud;
		this.exporter = exporter;
		this.names = names;
		this.tx = new TransactionTemplate(transactions);
	}

	record Conv(long id, double start, double end, LocalDate date, Long versionId, String work) {
	}

	private Conv conv(long id) {
		return db.sql("""
				SELECT c.id, c.start_offset_s, c.end_offset_s, c.local_date, c.transcript_version_id, r.work_path
				FROM conversation c JOIN recording r ON r.id = c.recording_id WHERE c.id = :c""").param("c", id)
				.query((rs, i) -> new Conv(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getObject(4, LocalDate.class), (Long) rs.getObject(5),
						rs.getString(6)))
				.optional().orElseThrow(() -> new IllegalArgumentException("No such conversation."));
	}

	private void notBusy(long conversationId) {
		boolean busy = db.sql("""
				SELECT c.stage IN ('live', 'retranscribing')
				       OR EXISTS (SELECT 1 FROM job j WHERE j.conversation_id = c.id AND j.status IN ('queued', 'running'))
				FROM conversation c WHERE c.id = :c""").param("c", conversationId).query(Boolean.class).single();
		if (busy) {
			throw new IllegalStateException("This conversation is still being transcribed: try again when it is done.");
		}
	}

	public record Plan(double audioS, int pieces, double usd, boolean available, String model) {
	}

	public record Line(double startS, double endS, String speaker, String text) {
	}

	public record Version(Long id, String engine, String model, String status, String error, boolean inUse, Double audioS, int pieces, Double usd,
			Double tookS, OffsetDateTime createdAt, JsonNode usage, List<Line> lines) {
	}

	/** {@code startS}: where the conversation starts in the recording, for elapsed times. */
	public record Overview(Long inUse, double startS, Plan plan, List<Version> versions) {
	}

	/** What "Transcribe with OpenAI" would send: the conversation's whole audio, and the price before the click. */
	public Plan plan(long conversationId) {
		var c = conv(conversationId);
		double audio = c.end() - c.start();
		return new Plan(audio, pieces(c).size(), audio / 60 * CloudTranscriber.USD_PER_MINUTE, cloud.available(), cloud.wholeModel());
	}

	private List<double[]> pieces(Conv c) {
		var lines = db.sql("SELECT start_s, end_s FROM segment WHERE conversation_id = :c AND NOT live ORDER BY start_s").param("c", c.id())
				.query((rs, i) -> new double[] { rs.getDouble(1), rs.getDouble(2) }).list();
		return pieces(c.start(), c.end(), lines);
	}

	/** Where a long conversation is cut: at the longest pause between lines near each 20-minute mark. */
	static List<double[]> pieces(double start, double end, List<double[]> lines) {
		var out = new ArrayList<double[]>();
		double from = start;
		while (end - from > ONE_CALL_MAX_S) {
			double target = from + PIECE_S, cut = target, best = 0, last = from;
			for (double[] l : lines) {
				double gap = l[0] - last, mid = (last + l[0]) / 2;
				if (gap > best && Math.abs(mid - target) <= PAUSE_SEARCH_S && mid > from) {
					best = gap;
					cut = mid;
				}
				last = Math.max(last, l[1]);
			}
			out.add(new double[] { from, cut });
			from = cut;
		}
		out.add(new double[] { from, end });
		return out;
	}

	/** The user clicked "Transcribe with OpenAI": the call runs in the background; the page shows its progress. */
	public long transcribeWithOpenai(long conversationId) {
		if (!cloud.available()) {
			throw new IllegalStateException("Set OPENAI_API_KEY in .env and restart Earshot to use OpenAI.");
		}
		var c = conv(conversationId);
		notBusy(conversationId);
		if (c.work() == null || !java.nio.file.Files.exists(java.nio.file.Path.of(c.work()))) {
			throw new IllegalStateException("The audio for this conversation is no longer on disk.");
		}
		boolean running = db.sql("SELECT count(*) FROM transcript_version WHERE conversation_id = :c AND status = 'running'").param("c", c.id())
				.query(Long.class).single() > 0;
		if (running) {
			throw new IllegalStateException("OpenAI is already transcribing this conversation.");
		}
		var pieces = pieces(c);
		double audio = c.end() - c.start();
		long id = db.sql("""
				INSERT INTO transcript_version (conversation_id, engine, model, status, audio_s, pieces, usd)
				VALUES (:c, 'openai', :m, 'running', :a, :p, :u) RETURNING id""").param("c", c.id()).param("m", cloud.wholeModel()).param("a", audio)
				.param("p", pieces.size()).param("u", audio / 60 * CloudTranscriber.USD_PER_MINUTE).query(Long.class).single();
		log.info("event=openai_transcript_started conversation={} version={} audio_s={} pieces={}", c.id(), id, Math.round(audio), pieces.size());
		Thread.ofVirtual().name("openai-transcript-" + id).start(() -> call(id, c, pieces));
		return id;
	}

	private void call(long id, Conv c, List<double[]> pieces) {
		long t0 = System.nanoTime();
		try {
			var raw = new String[pieces.size()];
			var gate = new java.util.concurrent.Semaphore(PARALLEL_PIECES);
			var errors = new java.util.concurrent.ConcurrentLinkedQueue<Exception>();
			try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
				for (int i = 0; i < pieces.size(); i++) {
					int k = i;
					exec.submit(() -> {
						gate.acquireUninterruptibly();
						try {
							raw[k] = cloud.transcribeWhole(mp3(c.work(), pieces.get(k)[0], pieces.get(k)[1]));
						} catch (Exception e) {
							errors.add(e);
						} finally {
							gate.release();
						}
					});
				}
			}
			if (!errors.isEmpty()) {
				throw errors.peek();
			}
			var stored = json.createArrayNode();
			for (String r : raw) {
				stored.add(r);
			}
			var parsed = parse(json, raw, pieces);
			db.sql("""
					UPDATE transcript_version SET status = 'done', error = NULL, lines = CAST(:l AS jsonb), raw = CAST(:r AS jsonb),
					       usage = CAST(:u AS jsonb), took_s = :t, updated_at = now() WHERE id = :id""").param("l", parsed.lines().toString())
					.param("r", stored.toString()).param("u", parsed.usage().toString()).param("t", (System.nanoTime() - t0) / 1e9).param("id", id)
					.update();
			log.info("event=openai_transcript_done conversation={} version={} lines={} took_s={}", c.id(), id, parsed.lines().size(),
					(System.nanoTime() - t0) / 1_000_000_000);
		} catch (Exception e) {
			log.warn("event=openai_transcript_failed conversation={} version={} reason={}", c.id(), id, e.toString());
			db.sql("UPDATE transcript_version SET status = 'failed', error = :e, took_s = :t, updated_at = now() WHERE id = :id")
					.param("e", e.getMessage() == null ? e.toString() : e.getMessage()).param("t", (System.nanoTime() - t0) / 1e9).param("id", id).update();
		}
	}

	record Parsed(ArrayNode lines, ObjectNode usage) {
	}

	/**
	 * OpenAI's replies as lines on the recording's clock, its text as it came back. A long conversation's
	 * pieces were heard separately, so their speakers are kept apart ("2:A" is piece 2's A).
	 */
	static Parsed parse(JsonMapper json, String[] raw, List<double[]> pieces) {
		var lines = json.createArrayNode();
		var usage = json.createObjectNode();
		for (int k = 0; k < raw.length; k++) {
			JsonNode r = json.readTree(raw[k]);
			double offset = pieces.get(k)[0];
			String piece = raw.length > 1 ? (k + 1) + ":" : "";
			for (JsonNode s : r.path("segments")) {
				String text = s.path("text").asString("").strip();
				if (text.isEmpty()) {
					continue;
				}
				lines.addObject().put("s", s.path("start").asDouble() + offset).put("e", s.path("end").asDouble() + offset)
						.put("t", text).put("spk", LABEL_PREFIX + piece + s.path("speaker").asString("?"));
			}
			JsonNode u = r.path("usage");
			add(usage, "audio_tokens", u.path("input_token_details").path("audio_tokens"));
			add(usage, "text_tokens", u.path("input_token_details").path("text_tokens"));
			add(usage, "output_tokens", u.path("output_tokens"));
		}
		return new Parsed(lines, usage);
	}

	private static void add(ObjectNode usage, String key, JsonNode n) {
		if (n.isNumber()) {
			usage.put(key, usage.path(key).asLong(0) + n.asLong());
		}
	}

	/** Both transcripts of the conversation, for the side-by-side page: Earshot's first, then OpenAI's, oldest first. */
	public Overview overview(long conversationId) {
		var c = conv(conversationId);
		var speakers = names.forConversation(conversationId);
		Map<Long, String> people = new HashMap<>();
		db.sql("SELECT id, CASE WHEN is_me THEN 'Me' ELSE name END FROM person").query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list()
				.forEach(e -> people.put(e.getKey(), e.getValue()));
		var inUse = inUseLines(conversationId, speakers);
		var rows = db.sql("""
				SELECT id, engine, model, status, error, lines::text, speakers::text, audio_s, pieces, usd, took_s, created_at, usage::text
				FROM transcript_version WHERE conversation_id = :c ORDER BY engine <> 'earshot', created_at, id""").param("c", conversationId).query()
				.listOfRows();
		var out = new ArrayList<Version>();
		boolean earshotRow = false;
		for (var r : rows) {
			long id = ((Number) r.get("id")).longValue();
			String engine = (String) r.get("engine");
			boolean earshot = "earshot".equals(engine);
			earshotRow |= earshot;
			boolean used = earshot ? c.versionId() == null : Objects.equals(c.versionId(), id);
			List<Line> lines = used ? inUse : stored(json.readTree((String) r.get("lines")), json.readTree((String) r.get("speakers")), speakers, people);
			out.add(new Version(earshot ? null : id, engine, earshot ? "Earshot" : (String) r.get("model"), (String) r.get("status"),
					(String) r.get("error"), used, num(r.get("audio_s")), ((Number) r.get("pieces")).intValue(), num(r.get("usd")), num(r.get("took_s")),
					toOffset(r.get("created_at")), r.get("usage") == null ? null : json.readTree((String) r.get("usage")),
					lines));
		}
		if (!earshotRow) {
			out.addFirst(new Version(null, "earshot", "Earshot", "done", null, true, c.end() - c.start(), 1, null, null, null, null, inUse));
		}
		return new Overview(c.versionId(), c.start(), plan(conversationId), out);
	}

	private static Double num(Object o) {
		return o == null ? null : ((Number) o).doubleValue();
	}

	private static OffsetDateTime toOffset(Object o) {
		if (o instanceof OffsetDateTime t) {
			return t;
		}
		if (o instanceof java.sql.Timestamp t) {
			return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
		}
		return null;
	}

	private List<Line> inUseLines(long conversationId, Map<Long, SpeakerNames.Speaker> speakers) {
		return db.sql("SELECT start_s, end_s, speaker_cluster_id, text FROM segment WHERE conversation_id = :c AND NOT live ORDER BY start_s, id")
				.param("c", conversationId).query((rs, i) -> {
					var s = speakers.get((Long) rs.getObject(3));
					return new Line(rs.getDouble(1), rs.getDouble(2), s == null ? "Unknown" : s.name(), rs.getString(4));
				}).list();
	}

	/** A version's stored lines with today's names: Earshot's by cluster, OpenAI's by the names given while it was in use. */
	private static List<Line> stored(JsonNode lines, JsonNode given, Map<Long, SpeakerNames.Speaker> speakers, Map<Long, String> people) {
		var out = new ArrayList<Line>();
		for (JsonNode l : lines) {
			String name;
			if (l.has("cluster")) {
				var s = l.path("cluster").isNumber() ? speakers.get(l.path("cluster").asLong()) : null;
				name = s == null ? "Unknown" : s.name();
			} else {
				String spk = l.path("spk").asString(null);
				JsonNode person = spk == null ? null : given.path(spk).path("person");
				name = person != null && person.isNumber() && people.containsKey(person.asLong()) ? people.get(person.asLong())
						: spk == null ? "Unknown" : SpeakerNames.openaiName(spk);
			}
			out.add(new Line(l.path("s").asDouble(), l.path("e").asDouble(), name, l.path("t").asString("")));
		}
		return out;
	}

	/** The user picked which transcript the conversation uses: {@code versionId} null is Earshot's. */
	public void use(long conversationId, Long versionId) {
		notBusy(conversationId);
		if (versionId != null) {
			var v = db.sql("SELECT conversation_id, engine, status FROM transcript_version WHERE id = :v").param("v", versionId)
					.query((rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3) }).optional()
					.orElseThrow(() -> new IllegalArgumentException("No such transcript."));
			if ((long) v[0] != conversationId || !"openai".equals(v[1]) || !"done".equals(v[2])) {
				throw new IllegalArgumentException("That transcript can't be used for this conversation.");
			}
		}
		switchTo(conversationId, versionId);
	}

	/**
	 * Earshot is about to write this conversation's lines again (a re-run): if another version is in
	 * use, it is put away with its edits and Earshot's lines come back first, so the re-run replaces
	 * only Earshot's.
	 */
	public void beforeEarshotRewrites(long conversationId) {
		if (conv(conversationId).versionId() != null) {
			switchTo(conversationId, null);
		}
	}

	private void switchTo(long conversationId, Long versionId) {
		var c = conv(conversationId);
		if (Objects.equals(c.versionId(), versionId)) {
			return;
		}
		tx.executeWithoutResult(st -> {
			if (c.versionId() == null) {
				saveEarshot(conversationId);
			} else {
				saveVersion(conversationId, c.versionId());
			}
			db.sql("DELETE FROM segment WHERE conversation_id = :c AND NOT live").param("c", conversationId).update();
			if (versionId == null) {
				restoreEarshot(conversationId);
			} else {
				restoreVersion(conversationId, versionId);
			}
			db.sql("UPDATE conversation SET transcript_version_id = :v WHERE id = :c").param("v", versionId).param("c", conversationId).update();
		});
		exporter.exportDay(c.date());
		log.info("event=transcript_switched conversation={} from={} to={}", conversationId, c.versionId() == null ? "earshot" : c.versionId(),
				versionId == null ? "earshot" : versionId);
	}

	/** Earshot's lines exactly as they are (ids, speakers, edits), and the speakers they may refer to. */
	private void saveEarshot(long c) {
		db.sql("""
				INSERT INTO transcript_version (conversation_id, engine, model, lines, speakers, audio_s)
				SELECT :c, 'earshot', 'earshot',
				       COALESCE((SELECT jsonb_agg(jsonb_build_object('id', s.id, 's', s.start_s, 'e', s.end_s, 't', s.text, 'cluster', s.speaker_cluster_id,
				                   'ch', s.channel, 'w', s.words_json, 'p', s.confidence, 'm', s.speaker_manual, 'o', s.original_text, 'by', s.edited_by)
				                 ORDER BY s.start_s, s.id) FROM segment s WHERE s.conversation_id = :c AND NOT s.live), '[]'::jsonb),
				       jsonb_build_object('clusters', COALESCE((SELECT jsonb_agg(sc.id) FROM speaker_cluster sc WHERE sc.conversation_id = :c), '[]'::jsonb)),
				       (SELECT end_offset_s - start_offset_s FROM conversation WHERE id = :c)
				ON CONFLICT (conversation_id) WHERE engine = 'earshot'
				DO UPDATE SET lines = EXCLUDED.lines, speakers = EXCLUDED.speakers, audio_s = EXCLUDED.audio_s, updated_at = now()""").param("c", c)
				.update();
	}

	private void restoreEarshot(long c) {
		db.sql("""
				INSERT INTO segment (id, conversation_id, speaker_cluster_id, channel, start_s, end_s, text, words_json, confidence, speaker_manual,
				                     original_text, edited_by)
				SELECT (l->>'id')::bigint, :c, sc.id, COALESCE(l->>'ch', 'mixed'), (l->>'s')::float8, (l->>'e')::float8, l->>'t',
				       NULLIF(l->'w', 'null'::jsonb), (l->>'p')::float8, COALESCE((l->>'m')::boolean, false), l->>'o', l->>'by'
				FROM transcript_version v CROSS JOIN jsonb_array_elements(v.lines) l
				LEFT JOIN speaker_cluster sc ON sc.id = (l->>'cluster')::bigint AND sc.conversation_id = :c
				WHERE v.conversation_id = :c AND v.engine = 'earshot'""").param("c", c).update();
	}

	/** The version in use goes back to its row with the user's edits and the names given to its speakers; its speakers go. */
	private void saveVersion(long c, long versionId) {
		db.sql("""
				UPDATE transcript_version SET updated_at = now(),
				       lines = COALESCE((SELECT jsonb_agg(jsonb_build_object('s', s.start_s, 'e', s.end_s, 't', s.text, 'spk', sc.label, 'ch', s.channel,
				                   'w', s.words_json, 'p', s.confidence, 'm', s.speaker_manual, 'o', s.original_text, 'by', s.edited_by)
				                 ORDER BY s.start_s, s.id)
				                 FROM segment s LEFT JOIN speaker_cluster sc ON sc.id = s.speaker_cluster_id
				                 WHERE s.conversation_id = :c AND NOT s.live), '[]'::jsonb),
				       speakers = COALESCE((SELECT jsonb_object_agg(sc.label, jsonb_build_object('person', sc.person_id, 'manual', sc.manual,
				                   'remember', sc.remember))
				                 FROM speaker_cluster sc WHERE sc.conversation_id = :c
				                   AND sc.id IN (SELECT speaker_cluster_id FROM segment WHERE conversation_id = :c)), '{}'::jsonb)
				WHERE id = :v""").param("c", c).param("v", versionId).update();
		db.sql("""
				DELETE FROM speaker_cluster sc WHERE sc.conversation_id = :c
				  AND sc.id NOT IN (SELECT x::bigint FROM transcript_version v CROSS JOIN jsonb_array_elements_text(v.speakers->'clusters') x
				                    WHERE v.conversation_id = :c AND v.engine = 'earshot')
				  AND EXISTS (SELECT 1 FROM transcript_version v WHERE v.conversation_id = :c AND v.engine = 'earshot')""").param("c", c).update();
	}

	/** The chosen version's lines become the conversation's, each of its speakers a speaker of the conversation. */
	private void restoreVersion(long c, long versionId) {
		db.sql("""
				INSERT INTO speaker_cluster (conversation_id, label, channel, speech_s, person_id, manual, remember)
				SELECT :c, x.spk, 'mixed', x.speech, (SELECT p.id FROM person p WHERE p.id = (v.speakers->x.spk->>'person')::bigint),
				       COALESCE((v.speakers->x.spk->>'manual')::boolean, false), COALESCE((v.speakers->x.spk->>'remember')::boolean, false)
				FROM transcript_version v
				CROSS JOIN LATERAL (SELECT l->>'spk' AS spk, sum((l->>'e')::float8 - (l->>'s')::float8) AS speech
				                    FROM jsonb_array_elements(v.lines) l WHERE l->>'spk' IS NOT NULL GROUP BY 1) x
				WHERE v.id = :v""").param("c", c).param("v", versionId).update();
		db.sql("""
				WITH earshot AS (SELECT x::bigint AS id FROM transcript_version v CROSS JOIN jsonb_array_elements_text(v.speakers->'clusters') x
				                 WHERE v.conversation_id = :c AND v.engine = 'earshot')
				INSERT INTO segment (conversation_id, speaker_cluster_id, channel, start_s, end_s, text, words_json, confidence, speaker_manual,
				                     original_text, edited_by)
				SELECT :c, sc.id, COALESCE(l->>'ch', 'mixed'), (l->>'s')::float8, (l->>'e')::float8, l->>'t', NULLIF(l->'w', 'null'::jsonb),
				       (l->>'p')::float8, COALESCE((l->>'m')::boolean, false), l->>'o', l->>'by'
				FROM transcript_version v CROSS JOIN jsonb_array_elements(v.lines) l
				LEFT JOIN speaker_cluster sc ON sc.conversation_id = :c AND sc.label = l->>'spk' AND sc.id NOT IN (SELECT id FROM earshot)
				WHERE v.id = :v AND btrim(COALESCE(l->>'t', '')) <> ''""").param("c", c).param("v", versionId).update();
	}

	/** A call cut short by a restart never finishes: say so instead of showing it as running. */
	@EventListener(ApplicationReadyEvent.class)
	public void markInterrupted() {
		int n = db.sql("""
				UPDATE transcript_version SET status = 'failed', error = 'Earshot restarted before OpenAI answered: try again.', updated_at = now()
				WHERE status = 'running'""").update();
		if (n > 0) {
			log.info("event=openai_transcripts_interrupted count={}", n);
		}
	}

	/** The span as 16 kHz mono MP3: 14 minutes are about 7 MB, well under OpenAI's 25 MB upload limit. */
	private static byte[] mp3(String work, double start, double end) throws IOException, InterruptedException {
		var p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-ss", String.valueOf(start), "-to", String.valueOf(end), "-i", work,
				"-ac", "1", "-ar", "16000", "-b:a", "64k", "-f", "mp3", "-").start();
		byte[] out = p.getInputStream().readAllBytes();
		if (p.waitFor() != 0 || out.length == 0) {
			throw new IOException("ffmpeg could not cut the conversation's audio.");
		}
		return out;
	}
}
