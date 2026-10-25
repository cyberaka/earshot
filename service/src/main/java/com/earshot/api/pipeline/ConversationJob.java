package com.earshot.api.pipeline;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;
import com.earshot.api.ml.MlClient;
import com.earshot.api.settings.Settings;
import com.earshot.api.speaker.SpeakerMatcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One conversation through diarize → identify → ASR + align → enrich → index → export, with a
 * checkpoint after each stage (A-2). Diarization runs before ASR so ambient audio is never
 * transcribed (PRD §5.2 steps 6–12).
 */
@Component
public class ConversationJob implements JobWorker.Handler {

	private static final Logger log = LoggerFactory.getLogger(ConversationJob.class);
	static final List<String> STAGES = List.of("segmented", "diarized", "identified", "transcribed", "enriched", "indexed", "exported");
	private final JdbcClient db;
	private final MlClient ml;
	private final Settings settings;
	private final com.earshot.api.settings.Vocabulary vocabulary;
	private final SpeakerMatcher matcher;
	private final MarkdownExporter exporter;
	private final Jobs jobs;
	private final JsonMapper json;

	public ConversationJob(JdbcClient db, MlClient ml, Settings settings, SpeakerMatcher matcher,
			MarkdownExporter exporter, Jobs jobs, JsonMapper json, com.earshot.api.settings.Vocabulary vocabulary) {
		this.vocabulary = vocabulary;
		this.db = db;
		this.ml = ml;
		this.settings = settings;
		this.matcher = matcher;
		this.exporter = exporter;
		this.jobs = jobs;
		this.json = json;
	}

	@Override
	public String type() {
		return "conversation";
	}

	record Conv(long id, String workPath, double start, double end, String regions, String stage, boolean ambient, String language,
			LocalDate day) {
	}

	@Override
	public void run(Jobs.Job job, JobWorker worker) {
		long id = job.conversationId();
		db.sql("UPDATE conversation SET status = 'running' WHERE id = :id").param("id", id).update();
		while (true) {
			Conv c = load(id);
			int next = STAGES.indexOf(c.stage()) + 1;
			if (next >= STAGES.size()) {
				break;
			}
			worker.checkpoint();
			String stage = STAGES.get(next);
			jobs.stage(job.id(), stage);
			switch (stage) {
				case "diarized" -> diarize(c);
				case "identified" -> {
					var r = matcher.match(id);
					db.sql("UPDATE conversation SET is_ambient = :a WHERE id = :id").param("a", r.ambient()).param("id", id).update();
					if (r.ambient()) {
						advance(id, "identified");
						db.sql("UPDATE conversation SET status = 'done' WHERE id = :id").param("id", id).update();
						exporter.exportDay(c.day());
						return;
					}
				}
				case "transcribed" -> transcribe(c);
				// Summaries and semantic vectors are made only when the user clicks (owner rule, 2026-10-24).
				case "enriched", "indexed" -> {
				}
				case "exported" -> exporter.exportDay(c.day());
				default -> throw new IllegalStateException(stage);
			}
			advance(id, stage);
		}
		db.sql("UPDATE conversation SET status = 'done' WHERE id = :id").param("id", id).update();
	}

	private Conv load(long id) {
		return db.sql("""
				SELECT c.id, r.work_path, c.start_offset_s, c.end_offset_s, r.speech_regions::text, c.stage, c.is_ambient, r.language,
				       c.local_date
				FROM conversation c JOIN recording r ON r.id = c.recording_id WHERE c.id = :id""").param("id", id)
				.query((rs, i) -> new Conv(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5),
						rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getObject(9, LocalDate.class)))
				.single();
	}

	private void advance(long id, String stage) {
		db.sql("UPDATE conversation SET stage = :s WHERE id = :id").param("s", stage).param("id", id).update();
	}

	private List<double[]> regionsIn(Conv c) {
		var out = new ArrayList<double[]>();
		if (c.regions() == null) {
			return out;
		}
		json.readTree(c.regions()).forEach(r -> {
			double s = r.get(0).asDouble(), e = r.get(1).asDouble();
			if (e > c.start() && s < c.end()) {
				out.add(new double[] { Math.max(s, c.start()), Math.min(e, c.end()) });
			}
		});
		return out;
	}

	private void diarize(Conv c) {
		JsonNode res = ml.diarize(c.workPath(), c.start(), c.end(), regionsIn(c), settings.num("diarization_threshold"));
		db.sql("DELETE FROM speaker_cluster WHERE conversation_id = :c").param("c", c.id()).update();
		for (JsonNode cl : res.get("clusters")) {
			db.sql("""
					INSERT INTO speaker_cluster (conversation_id, label, embedding, speech_s) VALUES (:c, :l, CAST(:e AS vector), :s)""")
					.param("c", c.id()).param("l", cl.get("label").asString()).param("e", cl.get("embedding").toString())
					.param("s", cl.get("speech_s").asDouble()).update();
		}
		db.sql("UPDATE conversation SET turns_json = CAST(:t AS jsonb) WHERE id = :id").param("t", res.get("turns").toString())
				.param("id", c.id()).update();
		log.info("event=diarized conversation={} backend={} clusters={}", c.id(), res.path("backend").asString(), res.get("clusters").size());
	}

	private void transcribe(Conv c) {
		Map<String, Object> filters = Map.of("no_speech_prob", settings.num("no_speech_prob"), "avg_logprob", settings.num("avg_logprob"),
				"compression_ratio", settings.num("compression_ratio"));
		JsonNode res = ml.transcribe(c.workPath(), c.start(), c.end(), regionsIn(c), c.language(), filters, vocabulary.global());
		var turns = new ArrayList<Aligner.Turn>();
		String turnsJson = db.sql("SELECT turns_json::text FROM conversation WHERE id = :id").param("id", c.id()).query(String.class).single();
		if (turnsJson != null) {
			json.readTree(turnsJson).forEach(t -> turns.add(new Aligner.Turn(t.get("start").asDouble(), t.get("end").asDouble(),
					t.get("speaker").asString())));
		}
		var asr = new ArrayList<List<Aligner.Word>>();
		for (JsonNode s : res.get("segments")) {
			var words = new ArrayList<Aligner.Word>();
			s.get("words").forEach(w -> words.add(new Aligner.Word(w.get("w").asString(), w.get("s").asDouble(), w.get("e").asDouble(),
					w.path("p").asDouble())));
			if (!words.isEmpty()) {
				asr.add(words);
			}
		}
		Map<String, Long> clusterOf = new HashMap<>();
		db.sql("SELECT label, id FROM speaker_cluster WHERE conversation_id = :c").param("c", c.id())
				.query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list().forEach(e -> clusterOf.put(e.getKey(), e.getValue()));
		db.sql("DELETE FROM segment WHERE conversation_id = :c").param("c", c.id()).update();
		for (var seg : Aligner.align(asr, turns, 1.5)) {
			db.sql("""
					INSERT INTO segment (conversation_id, speaker_cluster_id, channel, start_s, end_s, text, words_json, confidence)
					VALUES (:c, :sc, 'mixed', :s, :e, :t, CAST(:w AS jsonb), :p)""").param("c", c.id()).param("sc", clusterOf.get(seg.speaker()))
					.param("s", seg.start()).param("e", seg.end()).param("t", vocabulary.correct(seg.text())).param("w", json.writeValueAsString(seg.words()))
					.param("p", seg.confidence()).update();
		}
		log.info("event=transcribed conversation={} asr_segments={} dropped={}", c.id(), asr.size(), res.path("dropped").asInt());
	}
}
