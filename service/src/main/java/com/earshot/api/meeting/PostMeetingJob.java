package com.earshot.api.meeting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;
import com.earshot.api.ml.MlClient;
import com.earshot.api.pipeline.Aligner;
import com.earshot.api.settings.Settings;
import com.earshot.api.speaker.PeopleService;
import com.earshot.api.speaker.SpeakerMatcher;
import com.earshot.api.speaker.Vectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The post-meeting pass (PRD §6.6): each channel transcribed and diarized on its own, the
 * channel split kept, the live transcript replaced. Q&A citations are offsets, so they stay valid.
 */
@Component
public class PostMeetingJob implements JobWorker.Handler {

	private static final Logger log = LoggerFactory.getLogger(PostMeetingJob.class);
	// A caught-up voice this similar to one already in the meeting is that person (filled meeting 20: 0.86–0.97 same, < 0.45 new).
	private static final double SAME_VOICE = 0.5;
	private final JdbcClient db;
	private final MlClient ml;
	private final Settings settings;
	private final SpeakerMatcher matcher;
	private final PeopleService people;
	private final MarkdownExporter exporter;
	private final JsonMapper json;
	private final LiveTranscript transcript;
	private final com.earshot.api.settings.Vocabulary vocabulary;
	private final Path work;

	public PostMeetingJob(JdbcClient db, MlClient ml, Settings settings, SpeakerMatcher matcher, PeopleService people,
			MarkdownExporter exporter, JsonMapper json, LiveTranscript transcript, com.earshot.api.settings.Vocabulary vocabulary,
			EarshotProperties props) {
		this.vocabulary = vocabulary;
		this.db = db;
		this.ml = ml;
		this.settings = settings;
		this.matcher = matcher;
		this.people = people;
		this.exporter = exporter;
		this.json = json;
		this.transcript = transcript;
		this.work = props.data().resolve("audio/work");
	}

	@Override
	public String type() {
		return "post_meeting";
	}

	@Override
	public void run(Jobs.Job job, JobWorker worker) throws Exception {
		long meetingId = job.meetingId(), convId = job.conversationId(), recId = job.recordingId();
		var m = db.sql("""
				SELECT m.audio_mode, COALESCE(c.capture_files, m.capture_files)::text, m.title, c.part
				FROM meeting m JOIN conversation c ON c.id = :c WHERE m.id = :id""").param("id", meetingId).param("c", convId)
				.query((rs, i) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4) }).single();
		String mode = m[0];
		JsonNode files = json.readTree(m[1]);
		String language = db.sql("SELECT language FROM recording WHERE id = :id").param("id", recId).query(String.class).single();
		LocalDate day = db.sql("SELECT local_date FROM conversation WHERE id = :id").param("id", convId).query(LocalDate.class).single();
		Files.createDirectories(work);
		String stage = db.sql("SELECT stage FROM conversation WHERE id = :id").param("id", convId).query(String.class).single();

		if ("live".equals(stage) && !"post_meeting_full".equals(job.type())) {
			finishFromLive(job, worker, meetingId, convId, recId, mode, files, language, "1".equals(m[3]) ? m[2] : m[2] + " (continued)", day);
			return;
		}
		if ("live".equals(stage)) {
			jobs(job, "transcribe");
			var channelWork = new LinkedHashMap<String, String>();
			var regionsOf = new HashMap<String, JsonNode>();
			double duration = 0;
			for (var e : files.properties()) {
				String ch = e.getKey();
				String dst = work.resolve("meeting-" + meetingId + "-" + ch + ".flac").toString();
				JsonNode prep = ml.prepare(e.getValue().asString(), dst);
				channelWork.put(ch, dst);
				regionsOf.put(ch, prep.get("regions"));
				duration = Math.max(duration, prep.get("duration_s").asDouble());
			}
			String mixed = mix(meetingId, new ArrayList<>(channelWork.values()));
			db.sql("UPDATE recording SET work_path = :w, duration_s = :d WHERE id = :id").param("w", mixed).param("d", duration).param("id", recId).update();
			// Idempotent on retry: drop anything a failed earlier attempt inserted.
			db.sql("DELETE FROM segment WHERE conversation_id = :c AND NOT live").param("c", convId).update();
			db.sql("DELETE FROM speaker_cluster WHERE conversation_id = :c").param("c", convId).update();
			Map<String, Object> filters = Map.of("no_speech_prob", settings.num("no_speech_prob"), "avg_logprob", settings.num("avg_logprob"),
					"compression_ratio", settings.num("compression_ratio"));
			for (var e : channelWork.entrySet()) {
				worker.checkpoint();
				processChannel(convId, e.getKey(), e.getValue(), regionsOf.get(e.getKey()), duration, language, filters,
						vocabulary.forMeeting(meetingId));
			}
			matcher.match(convId);
			if ("mic_system".equals(mode) && !matcher.hasMeVoiceprint()) {
				// Without a voiceprint the mic channel is the user (PRD §6.6).
				long me = people.meId();
				db.sql("UPDATE speaker_cluster SET person_id = :p WHERE conversation_id = :c AND channel = 'mic'").param("p", me).param("c", convId).update();
			}
			db.sql("DELETE FROM segment WHERE conversation_id = :c AND live").param("c", convId).update();
			db.sql("UPDATE conversation SET end_offset_s = :d, stage = 'transcribed' WHERE id = :id").param("d", duration).param("id", convId).update();
			db.sql("DELETE FROM segment_vec WHERE segment_id NOT IN (SELECT id FROM segment)").update();
			stage = "transcribed";
		}
		if ("transcribed".equals(stage)) {
			worker.checkpoint();
			// No summary here: it is made only when the user clicks Summarize (owner rule, 2026-10-24).
			if (m[2] != null && !"Meeting".equals(m[2])) {
				// The user's own title wins over the generated one.
				db.sql("UPDATE conversation SET title = :t WHERE id = :id").param("t", m[2]).param("id", convId).update();
			}
			db.sql("UPDATE conversation SET stage = 'enriched' WHERE id = :id").param("id", convId).update();
			stage = "enriched";
		}
		if ("enriched".equals(stage)) {
			db.sql("UPDATE conversation SET stage = 'exported', status = 'done' WHERE id = :id").param("id", convId).update();
		}
		// Recorder conversations that overlap this meeting are now duplicates of it (A-13).
		db.sql("""
				UPDATE conversation c SET duplicate_of_meeting_id = :m FROM conversation p, recording r
				WHERE p.id = :part AND r.id = c.recording_id AND r.source_type = 'recorder' AND c.duplicate_of_meeting_id IS NULL
				  AND LEAST(p.started_at + make_interval(secs => p.end_offset_s), c.started_at + make_interval(secs => c.end_offset_s - c.start_offset_s))
				      - GREATEST(p.started_at, c.started_at)
				      >= 0.5 * LEAST(make_interval(secs => p.end_offset_s), make_interval(secs => c.end_offset_s - c.start_offset_s))""")
				.param("m", meetingId).param("part", convId).update();
		db.sql("UPDATE meeting SET status = 'done' WHERE id = :id").param("id", meetingId).update();
		db.sql("UPDATE recording SET status = 'done' WHERE id = :id").param("id", recId).update();
		exporter.exportDay(day);
		transcript.writeNow(meetingId);
		log.info("event=post_meeting_done meeting={}", meetingId);
	}

	/**
	 * The default after Stop (owner decision, 2026-10-19: no slow re-run): the live transcript is
	 * final, a summary the user asked for becomes the meeting's summary, and only playback audio, the last
	 * index entries and the export are made. Takes seconds.
	 */
	private void finishFromLive(Jobs.Job job, JobWorker worker, long meetingId, long convId, long recId, String mode, JsonNode files,
			String language, String title, LocalDate day) throws Exception {
		catchUp(job, worker, meetingId, convId, mode, files, language);
		jobs(job, "finish");
		var inputs = new ArrayList<String>();
		files.properties().forEach(e -> {
			if (Files.exists(Path.of(e.getValue().asString()))) {
				inputs.add(e.getValue().asString());
			}
		});
		double duration = db.sql("SELECT COALESCE(duration_s, 0) FROM recording WHERE id = :id").param("id", recId).query(Double.class).single();
		if (!inputs.isEmpty()) {
			db.sql("UPDATE recording SET work_path = :w WHERE id = :id").param("w", mix(meetingId, inputs)).param("id", recId).update();
		}
		db.sql("UPDATE segment SET live = false WHERE conversation_id = :c").param("c", convId).update();
		matcher.match(convId);
		db.sql("UPDATE conversation SET end_offset_s = GREATEST(end_offset_s, :d) WHERE id = :id").param("d", duration).param("id", convId).update();
		if ("mic_system".equals(mode) && !matcher.hasMeVoiceprint()) {
			long me = people.meId();
			db.sql("UPDATE speaker_cluster SET person_id = :p WHERE conversation_id = :c AND channel = 'mic' AND person_id IS NULL")
					.param("p", me).param("c", convId).update();
		}
		String rolling = db.sql("SELECT rolling_summary::text FROM meeting WHERE id = :id").param("id", meetingId).query(String.class)
				.optional().orElse(null);
		if (rolling != null) {
			JsonNode r = json.readTree(rolling);
			var bullets = new ArrayList<String>();
			r.path("summary").forEach(b -> bullets.add(b.asString()));
			var topics = new ArrayList<String>();
			r.path("topics").forEach(t -> topics.add(t.asString()));
			// The user's title wins; an untitled meeting is named after its first topic.
			boolean continued = title != null && title.endsWith(" (continued)");
			String base = continued ? title.substring(0, title.length() - " (continued)".length()) : title;
			String named = base != null && !"Meeting".equals(base) ? base : topics.isEmpty() ? "Meeting" : topics.getFirst();
			String useTitle = continued ? named + " (continued)" : named;
			db.sql("UPDATE conversation SET title = :t, summary = CAST(:s AS jsonb), topics = CAST(:tp AS jsonb) WHERE id = :id")
					.param("t", useTitle).param("s", json.writeValueAsString(java.util.Map.of("bullets", bullets)))
					.param("tp", json.writeValueAsString(topics)).param("id", convId).update();
			// An untitled meeting takes the generated title, so lists show something recognisable.
			db.sql("UPDATE meeting SET title = :t WHERE id = :id AND title = 'Meeting'").param("t", named).param("id", meetingId).update();
			db.sql("DELETE FROM action_item WHERE conversation_id = :c").param("c", convId).update();
			for (JsonNode a : r.path("action_items")) {
				String text = a.path("text").asString("");
				if (!text.isBlank()) {
					String owner = a.path("owner").isString() ? a.path("owner").asString() + ": " : "";
					db.sql("INSERT INTO action_item (conversation_id, text) VALUES (:c, :t)").param("c", convId).param("t", owner + text).update();
				}
			}
		}
		db.sql("UPDATE conversation SET stage = 'exported', status = 'done' WHERE id = :id").param("id", convId).update();
		try {
			// Lines moved by hand during the meeting: voices from the final lines, now that the audio is joined.
			people.refreshMovedVoices(convId);
		} catch (Exception e) {
			log.warn("event=voice_refresh_failed conversation={} reason={}", convId, e.getMessage());
		}
		db.sql("UPDATE meeting SET status = 'done' WHERE id = :id").param("id", meetingId).update();
		db.sql("UPDATE recording SET status = 'done' WHERE id = :id").param("id", recId).update();
		exporter.exportDay(day);
		transcript.writeNow(meetingId);
		log.info("event=meeting_finished meeting={} from=live", meetingId);
	}

	/**
	 * Stop never loses text (BUGS.md B-2; owner decision D-7, 2026-10-24). When live transcription
	 * was behind at Stop, the ML process drops its queue, and the audio after each channel's last
	 * line is transcribed here from the saved file. Lines go to the voices already heard in the
	 * meeting (by voice similarity), or to new speakers. Safe to repeat: it starts after the last
	 * line on disk, so a paused and resumed job continues where it stopped.
	 */
	private void catchUp(Jobs.Job job, JobWorker worker, long meetingId, long convId, String mode, JsonNode files, String language) {
		for (var e : files.properties()) {
			String ch = e.getKey();
			String src = e.getValue().asString();
			if (!Files.exists(Path.of(src))) {
				continue;
			}
			double last = db.sql("SELECT COALESCE(max(end_s), 0) FROM segment WHERE conversation_id = :c AND channel = :ch").param("c", convId)
					.param("ch", ch).query(Double.class).single();
			worker.checkpoint();
			String dst = work.resolve("meeting-" + meetingId + "-catchup-" + ch + ".flac").toString();
			try {
				JsonNode prep = ml.prepare(src, dst);
				double duration = prep.get("duration_s").asDouble();
				var regions = new ArrayList<double[]>();
				prep.get("regions").forEach(r -> {
					double s = r.get(0).asDouble(), en = r.get(1).asDouble();
					if (en > last + 0.3) {
						regions.add(new double[] { Math.max(s, last), en });
					}
				});
				if (regions.isEmpty()) {
					continue;
				}
				double from = regions.getFirst()[0];
				jobs(job, "catching up: %s, last %d min".formatted(ch, Math.max(1, Math.round((duration - from) / 60))));
				log.info("event=catch_up_started meeting={} channel={} from_s={} to_s={}", meetingId, ch, Math.round(from), Math.round(duration));
				Map<String, Object> filters = Map.of("no_speech_prob", settings.num("no_speech_prob"), "avg_logprob", settings.num("avg_logprob"),
						"compression_ratio", settings.num("compression_ratio"));
				JsonNode t = ml.transcribe(dst, from, duration, regions, language, filters, vocabulary.forMeeting(meetingId));
				// A line already heard live is not added twice (a guard: live times now match the file, BUGS.md B-9).
				var recent = new java.util.HashSet<String>();
				db.sql("SELECT text FROM segment WHERE conversation_id = :c AND channel = :ch AND end_s >= :from")
						.param("c", convId).param("ch", ch).param("from", last - 120).query(String.class).list().forEach(x -> recent.add(normal(x)));
				var segs = new ArrayList<JsonNode>();
				t.get("segments").forEach(s -> {
					if (s.get("start_s").asDouble() >= last - 0.2 && !recent.contains(normal(s.get("text").asString()))) {
						segs.add(s);
					}
				});
				if (segs.isEmpty()) {
					continue;
				}
				var speakerOf = speakersFor(convId, ch, mode, dst, from, duration, regions, segs);
				for (int i = 0; i < segs.size(); i++) {
					JsonNode s = segs.get(i);
					db.sql("""
							INSERT INTO segment (conversation_id, speaker_cluster_id, channel, live, start_s, end_s, text, words_json, confidence)
							VALUES (:c, :sc, :ch, false, :s, :e, :t, CAST(:w AS jsonb), :p)""").param("c", convId).param("sc", speakerOf.get(i))
							.param("ch", ch).param("s", s.get("start_s").asDouble()).param("e", s.get("end_s").asDouble())
							.param("t", s.get("text").asString()).param("w", s.get("words").toString())
							.param("p", Math.exp(s.path("avg_logprob").asDouble())).update();
				}
				log.info("event=caught_up meeting={} channel={} lines={} from_s={}", meetingId, ch, segs.size(), Math.round(from));
			} finally {
				try {
					Files.deleteIfExists(Path.of(dst));
				} catch (java.io.IOException ignored) {
					// a leftover working copy is cleaned with the rest of audio/work
				}
			}
		}
	}

	/** Lower case letters and digits only, for comparing two transcriptions of the same words. */
	static String normal(String text) {
		return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}

	/** A speaker for each caught-up line: the user's mic on a call, else the closest known voice or a new one. */
	private List<Long> speakersFor(long convId, String ch, String mode, String path, double from, double to, List<double[]> regions,
			List<JsonNode> segs) {
		var out = new ArrayList<Long>();
		if ("mic".equals(ch) && "mic_system".equals(mode)) {
			Long me = db.sql("SELECT id FROM speaker_cluster WHERE conversation_id = :c AND channel = 'mic' ORDER BY speech_s DESC LIMIT 1")
					.param("c", convId).query(Long.class).optional().orElse(null);
			segs.forEach(s -> out.add(me));
			return out;
		}
		var known = db.sql("SELECT id, embedding::text FROM speaker_cluster WHERE conversation_id = :c AND channel = :ch AND embedding IS NOT NULL")
				.param("c", convId).param("ch", ch).query((rs, i) -> Map.entry(rs.getLong(1), Vectors.parse(rs.getString(2)))).list();
		JsonNode d = ml.diarize(path, from, to, regions, settings.num("diarization_threshold"));
		var clusterOf = new HashMap<String, Long>();
		for (JsonNode cl : d.get("clusters")) {
			float[] e = Vectors.parse(cl.get("embedding").toString());
			Long best = null;
			double bestScore = 0;
			for (var k : known) {
				double score = Vectors.cosine(e, k.getValue());
				if (score > bestScore) {
					best = k.getKey();
					bestScore = score;
				}
			}
			if (best == null || bestScore < SAME_VOICE) {
				best = db.sql("""
						INSERT INTO speaker_cluster (conversation_id, label, channel, embedding, speech_s)
						VALUES (:c, :l, :ch, CAST(:e AS vector), :s) RETURNING id""").param("c", convId).param("l", "catchup:" + ch + ":" + cl.get("label").asString())
						.param("ch", ch).param("e", cl.get("embedding").toString()).param("s", cl.get("speech_s").asDouble()).query(Long.class).single();
			}
			clusterOf.put(cl.get("label").asString(), best);
		}
		for (JsonNode s : segs) {
			double a = s.get("start_s").asDouble(), b = s.get("end_s").asDouble();
			var overlap = new HashMap<String, Double>();
			d.get("turns").forEach(tn -> {
				double o = Math.min(b, tn.get("end").asDouble()) - Math.max(a, tn.get("start").asDouble());
				if (o > 0) {
					overlap.merge(tn.get("speaker").asString(), o, Double::sum);
				}
			});
			out.add(overlap.entrySet().stream().max(Map.Entry.comparingByValue()).map(en -> clusterOf.get(en.getKey())).orElse(null));
		}
		return out;
	}

	private void jobs(Jobs.Job job, String stage) {
		db.sql("UPDATE job SET stage = :s, updated_at = now() WHERE id = :id").param("s", stage).param("id", job.id()).update();
	}

	private void processChannel(long convId, String channel, String path, JsonNode regions, double duration, String language,
			Map<String, Object> filters, List<String> terms) {
		var regionList = new ArrayList<double[]>();
		regions.forEach(r -> regionList.add(new double[] { r.get(0).asDouble(), r.get(1).asDouble() }));
		if (regionList.isEmpty()) {
			return;
		}
		JsonNode d = ml.diarize(path, 0, duration, regionList, settings.num("diarization_threshold"));
		Map<String, Long> clusterOf = new HashMap<>();
		for (JsonNode cl : d.get("clusters")) {
			String label = channel + ":" + cl.get("label").asString();
			long id = db.sql("""
					INSERT INTO speaker_cluster (conversation_id, label, channel, embedding, speech_s)
					VALUES (:c, :l, :ch, CAST(:e AS vector), :s) RETURNING id""").param("c", convId).param("l", label).param("ch", channel)
					.param("e", cl.get("embedding").toString()).param("s", cl.get("speech_s").asDouble()).query(Long.class).single();
			clusterOf.put(cl.get("label").asString(), id);
		}
		var turns = new ArrayList<Aligner.Turn>();
		d.get("turns").forEach(t -> turns.add(new Aligner.Turn(t.get("start").asDouble(), t.get("end").asDouble(), t.get("speaker").asString())));
		JsonNode t = ml.transcribe(path, 0, duration, regionList, language, filters, terms);
		var asr = new ArrayList<List<Aligner.Word>>();
		for (JsonNode s : t.get("segments")) {
			var words = new ArrayList<Aligner.Word>();
			s.get("words").forEach(w -> words.add(new Aligner.Word(w.get("w").asString(), w.get("s").asDouble(), w.get("e").asDouble(), w.path("p").asDouble())));
			if (!words.isEmpty()) {
				asr.add(words);
			}
		}
		for (var seg : Aligner.align(asr, turns, 1.5)) {
			db.sql("""
					INSERT INTO segment (conversation_id, speaker_cluster_id, channel, start_s, end_s, text, words_json, confidence)
					VALUES (:c, :sc, :ch, :s, :e, :t, CAST(:w AS jsonb), :p)""").param("c", convId).param("sc", clusterOf.get(seg.speaker()))
					.param("ch", channel).param("s", seg.start()).param("e", seg.end()).param("t", seg.text())
					.param("w", json.writeValueAsString(seg.words())).param("p", seg.confidence()).update();
		}
		log.info("event=meeting_channel_done conversation={} channel={} clusters={}", convId, channel, clusterOf.size());
	}

	private String mix(long meetingId, List<String> inputs) throws Exception {
		String out = work.resolve("meeting-" + meetingId + "-" + System.nanoTime() + ".flac").toString();
		if (inputs.size() == 1) {
			Files.copy(Path.of(inputs.getFirst()), Path.of(out), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return out;
		}
		var cmd = new ArrayList<>(List.of("ffmpeg", "-loglevel", "error", "-y"));
		inputs.forEach(i -> {
			cmd.add("-i");
			cmd.add(i);
		});
		cmd.addAll(List.of("-filter_complex", "amix=inputs=" + inputs.size() + ":normalize=0", "-ac", "1", "-ar", "16000", out));
		var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String err = new String(p.getInputStream().readAllBytes());
		if (p.waitFor() != 0) {
			throw new IllegalStateException("ffmpeg mix failed: " + err);
		}
		return out;
	}
}
