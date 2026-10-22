package com.earshot.api.meeting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;
import com.earshot.api.llm.LlmClient;
import com.earshot.api.llm.LlmClient.Message;
import com.earshot.api.ml.MlClient;
import com.earshot.api.search.Indexer;
import com.earshot.api.settings.Settings;
import com.earshot.api.speaker.SpeakerNames;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One live meeting at a time (PRD §6): capture → ML events → DB + browser, plus rolling context. */
@Service
public class MeetingService {

	private static final Logger log = LoggerFactory.getLogger(MeetingService.class);
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
	private final JdbcClient db;
	private final MlClient ml;
	private final LlmClient llm;
	private final Settings settings;
	private final Indexer indexer;
	private final Jobs jobs;
	private final JobWorker worker;
	private final MeetingSockets sockets;
	private final JsonMapper json;
	private final Path meetingsDir;
	private final ExecutorService liveIndex = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
	private volatile Live live;
	private final com.earshot.api.conversation.ConversationService conversations;
	private final com.earshot.api.speaker.SpeakerMatcher matcher;
	private final SpeakerNames names;

	public MeetingService(JdbcClient db, MlClient ml, LlmClient llm, Settings settings, Indexer indexer, Jobs jobs, JobWorker worker,
			MeetingSockets sockets, JsonMapper json, EarshotProperties props, com.earshot.api.conversation.ConversationService conversations,
			com.earshot.api.speaker.SpeakerMatcher matcher, SpeakerNames names) {
		this.conversations = conversations;
		this.matcher = matcher;
		this.names = names;
		this.db = db;
		this.ml = ml;
		this.llm = llm;
		this.settings = settings;
		this.indexer = indexer;
		this.jobs = jobs;
		this.worker = worker;
		this.sockets = sockets;
		this.json = json;
		this.meetingsDir = props.data().resolve("audio/meetings");
	}

	@PostConstruct
	void init() {
		worker.setLiveMeeting(() -> live != null);
	}

	static final class Live {
		final long meetingId, recordingId, conversationId;
		final String audioMode;
		final Instant startedAt;
		final ZoneId zone = ZoneId.systemDefault();
		final AtomicInteger newSegments = new AtomicInteger();
		final AtomicBoolean summarizing = new AtomicBoolean();
		volatile Instant lastSummary = Instant.now();
		volatile double lagS;
		volatile double rtf;
		volatile String warning;
		volatile String device;
		volatile String language;
		volatile WebSocket ml;
		// Live speaker labels ("mic:L1") -> speaker_cluster ids, and ML segment keys -> segment ids.
		final java.util.concurrent.ConcurrentHashMap<String, Long> clusters = new java.util.concurrent.ConcurrentHashMap<>();
		final java.util.concurrent.ConcurrentHashMap<String, Long> segments = new java.util.concurrent.ConcurrentHashMap<>();

		Live(long meetingId, long recordingId, long conversationId, String audioMode, Instant startedAt) {
			this.meetingId = meetingId;
			this.recordingId = recordingId;
			this.conversationId = conversationId;
			this.audioMode = audioMode;
			this.startedAt = startedAt;
		}
	}

	public record Start(String title, String audioMode, String source, String device, Integer micChannel, Integer systemChannel,
			Map<String, String> files, String preContext, String language, Double speed) {
	}

	private static Map<String, Integer> channels(String mode, Start req) {
		var channels = new LinkedHashMap<String, Integer>();
		if (!"system".equals(mode)) {
			channels.put("mic", req.micChannel() == null ? 0 : req.micChannel());
		}
		if (!"mic".equals(mode)) {
			channels.put("system", req.systemChannel() == null ? ("system".equals(mode) ? 0 : 1) : req.systemChannel());
		}
		return channels;
	}

	/** Switches the live meeting's input (speakers -> headset) without stopping: same part, same timeline. */
	public Map<String, Object> switchDevice(long meetingId, Start req) {
		var l = live;
		if (l == null || l.meetingId != meetingId) {
			throw new IllegalStateException("Meeting " + meetingId + " is not live.");
		}
		var body = new HashMap<String, Object>();
		body.put("device", req.device());
		body.put("channels", channels(l.audioMode, req));
		l.device = ml.captureSwitch("m" + meetingId, body);
		l.warning = null; // the leak check starts over on the new device
		log.info("event=meeting_device_switched meeting={} device={}", meetingId, l.device);
		var st = status(l);
		sockets.broadcast(meetingId, st);
		return st;
	}

	/** A wrong language noticed mid-meeting: the next sentence is transcribed in the new one. */
	public Map<String, Object> switchLanguage(long meetingId, String language) {
		var l = live;
		if (l == null || l.meetingId != meetingId) {
			throw new IllegalStateException("Meeting " + meetingId + " is not live.");
		}
		if (language == null || !language.matches("[a-z]{2}(\\+[a-z]{2})*|auto")) {
			throw new IllegalArgumentException("Unknown language: " + language);
		}
		ml.liveLanguage("m" + meetingId, language);
		l.language = language;
		db.sql("UPDATE recording SET language = :l WHERE id = :id").param("l", language).param("id", l.recordingId).update();
		log.info("event=meeting_language_switched meeting={} language={}", meetingId, language);
		var st = status(l);
		sockets.broadcast(meetingId, st);
		return st;
	}

	public Long activeId() {
		var l = live;
		return l == null ? null : l.meetingId;
	}

	public synchronized long start(Start req) throws Exception {
		if (live != null) {
			throw new IllegalStateException("A meeting is already recording.");
		}
		String mode = req.audioMode() == null ? "mic" : req.audioMode();
		Instant now = Instant.now();
		ZoneId zone = ZoneId.systemDefault();
		String lang = req.language() == null || req.language().isBlank() ? settings.str("language") : req.language();
		long recId = db.sql("""
				INSERT INTO recording (source_type, original_name, started_at, time_zone, started_at_source, language, status)
				VALUES ('meeting', :t, :s, :tz, 'live', :l, 'live') RETURNING id""").param("t", req.title()).param("s", java.sql.Timestamp.from(now))
				.param("tz", zone.getId()).param("l", lang).query(Long.class).single();
		long meetingId = db.sql("""
				INSERT INTO meeting (recording_id, title, pre_context, audio_mode, started_at) VALUES (:r, :t, :p, :m, :s) RETURNING id""")
				.param("r", recId).param("t", req.title() == null || req.title().isBlank() ? "Meeting" : req.title())
				.param("p", req.preContext()).param("m", mode).param("s", java.sql.Timestamp.from(now)).query(Long.class).single();
		long convId = db.sql("""
				INSERT INTO conversation (recording_id, meeting_id, start_offset_s, end_offset_s, started_at, local_date, title, stage, status)
				VALUES (:r, :m, 0, 0, :s, :d, :t, 'live', 'live') RETURNING id""").param("r", recId).param("m", meetingId)
				.param("s", java.sql.Timestamp.from(now)).param("d", now.atZone(zone).toLocalDate())
				.param("t", req.title() == null || req.title().isBlank() ? null : req.title()).query(Long.class).single();
		return begin(meetingId, recId, convId, mode, now, lang, meetingsDir.resolve(String.valueOf(meetingId)), req, Map.of(), true);
	}

	/**
	 * Resumes a stopped meeting as a new part, minutes or days later (owner request, 2026-10-19).
	 * The part is its own recording and conversation, so it shows on the day it happens; speakers
	 * from earlier parts are seeded into live separation so they keep their labels and names.
	 */
	public synchronized long resume(long meetingId, Start req) throws Exception {
		if (live != null) {
			throw new IllegalStateException("A meeting is already recording.");
		}
		var m = db.sql("SELECT title, status FROM meeting WHERE id = :id").param("id", meetingId)
				.query((rs, i) -> new String[] { rs.getString(1), rs.getString(2) }).optional()
				.orElseThrow(() -> new IllegalArgumentException("No such meeting."));
		if (!"done".equals(m[1])) {
			throw new IllegalStateException("This meeting is still finishing; try again in a few seconds.");
		}
		String mode = req.audioMode() == null ? "mic" : req.audioMode();
		Instant now = Instant.now();
		ZoneId zone = ZoneId.systemDefault();
		String lang = req.language() == null || req.language().isBlank() ? settings.str("language") : req.language();
		int part = db.sql("SELECT COALESCE(max(part), 0) + 1 FROM conversation WHERE meeting_id = :m").param("m", meetingId)
				.query(Integer.class).single();
		long recId = db.sql("""
				INSERT INTO recording (source_type, original_name, started_at, time_zone, started_at_source, language, status)
				VALUES ('meeting', :t, :s, :tz, 'live', :l, 'live') RETURNING id""").param("t", m[0]).param("s", java.sql.Timestamp.from(now))
				.param("tz", zone.getId()).param("l", lang).query(Long.class).single();
		long convId = db.sql("""
				INSERT INTO conversation (recording_id, meeting_id, part, start_offset_s, end_offset_s, started_at, local_date, title, stage, status)
				VALUES (:r, :m, :p, 0, 0, :s, :d, :t, 'live', 'live') RETURNING id""").param("r", recId).param("m", meetingId).param("p", part)
				.param("s", java.sql.Timestamp.from(now)).param("d", now.atZone(zone).toLocalDate()).param("t", m[0] + " (continued)")
				.query(Long.class).single();
		db.sql("UPDATE meeting SET status = 'live', audio_mode = :mode, ended_at = NULL WHERE id = :id").param("mode", mode)
				.param("id", meetingId).update();
		// Each speaker once, as their first cluster in this meeting, with their voice so far.
		var seeds = new HashMap<String, java.util.List<Map<String, Object>>>();
		db.sql("""
				SELECT DISTINCT ON (COALESCE(sc.origin_cluster_id, sc.id)) COALESCE(sc.origin_cluster_id, sc.id) AS root, sc.channel,
				       sc.embedding::text AS embedding
				FROM speaker_cluster sc JOIN conversation c ON c.id = sc.conversation_id
				WHERE c.meeting_id = :m AND c.id <> :c AND sc.embedding IS NOT NULL
				ORDER BY COALESCE(sc.origin_cluster_id, sc.id), sc.speech_s DESC""").param("m", meetingId).param("c", convId)
				.query((rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3) }).list()
				.forEach(r -> seeds.computeIfAbsent((String) r[1], k -> new java.util.ArrayList<>())
						.add(Map.of("label", "C" + r[0], "embedding", json.readTree((String) r[2]))));
		try {
			return begin(meetingId, recId, convId, mode, now, lang,
					meetingsDir.resolve(String.valueOf(meetingId)).resolve("part-" + part), req, seeds, false);
		} catch (Exception e) {
			db.sql("UPDATE meeting SET status = 'done', ended_at = now() WHERE id = :id").param("id", meetingId).update();
			throw e;
		}
	}

	private long begin(long meetingId, long recId, long convId, String mode, Instant now, String lang, Path dir, Start req,
			Map<String, ?> seeds, boolean newMeeting) throws Exception {
		Files.createDirectories(dir);
		db.sql("UPDATE recording SET path = :p WHERE id = :id").param("p", dir.toString()).param("id", recId).update();
		var l = new Live(meetingId, recId, convId, mode, now);
		l.language = lang;
		live = l;
		try {
			String session = "m" + meetingId;
			ml.liveStart(session, lang, settings.num("live_max_segment_seconds"), seeds);
			l.ml = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
					.buildAsync(URI.create(ml.liveEventsUrl(session)), new Listener(l)).join();
			var channels = channels(mode, req);
			var body = new HashMap<String, Object>();
			body.put("meeting_id", session);
			body.put("out_dir", dir.toString());
			body.put("source", req.source() == null ? "device" : req.source());
			body.put("device", req.device());
			body.put("channels", channels);
			if (req.files() != null) {
				var files = new HashMap<String, String>();
				req.files().forEach((k, v) -> {
					if (channels.containsKey(k)) {
						files.put(k, v);
					}
				});
				body.put("files", files);
			}
			body.put("speed", req.speed() == null ? 1.0 : req.speed());
			ml.captureStart(body);
		} catch (Exception e) {
			// Nothing was recorded: leave no trace of the meeting (rows, folder, live session).
			live = null;
			try {
				ml.liveStop("m" + meetingId);
			} catch (Exception ignored) {
				// the ML session may never have started
			}
			if (l.ml != null) {
				l.ml.abort();
			}
			// A failed resume removes only the new part; a failed new meeting removes the meeting.
			db.sql("DELETE FROM recording WHERE id = :id").param("id", recId).update();
			deleteDir(dir);
			log.warn("event=meeting_start_failed meeting={} reason={}", meetingId, e.getMessage());
			throw e;
		}
		log.info("event=meeting_started meeting={} mode={} source={} resumed={}", meetingId, mode, req.source(), !newMeeting);
		return meetingId;
	}

	/** Receives the ML process's live events. */
	final class Listener implements WebSocket.Listener {
		private final Live l;
		private final StringBuilder buf = new StringBuilder();

		Listener(Live l) {
			this.l = l;
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				String text = buf.toString();
				buf.setLength(0);
				try {
					onEvent(l, json.readTree(text));
				} catch (Exception e) {
					log.error("event=live_event_failed reason={}", e.toString());
				}
			}
			ws.request(1);
			return null;
		}
	}

	private void onEvent(Live l, JsonNode ev) {
		switch (ev.path("type").asString()) {
			case "segment" -> {
				String channel = ev.get("channel").asString();
				double st = ev.get("start_s").asDouble(), e = ev.get("end_s").asDouble();
				Long clusterId = ev.path("speaker").isString() ? cluster(l, channel, ev.get("speaker").asString()) : null;
				long id = db.sql("""
						INSERT INTO segment (conversation_id, speaker_cluster_id, channel, live, start_s, end_s, text, words_json, confidence)
						VALUES (:c, :sc, :ch, true, :s, :e, :t, CAST(:w AS jsonb), :p) RETURNING id""").param("c", l.conversationId)
						.param("sc", clusterId).param("ch", channel).param("s", st).param("e", e).param("t", ev.get("text").asString())
						.param("w", ev.get("words").toString()).param("p", ev.path("confidence").asDouble()).query(Long.class).single();
				if (ev.path("key").isString()) {
					l.segments.put(ev.get("key").asString(), id);
				}
				db.sql("UPDATE conversation SET end_offset_s = GREATEST(end_offset_s, :e) WHERE id = :id").param("e", e)
						.param("id", l.conversationId).update();
				var sp = clusterId == null ? null : names.forConversation(l.conversationId).get(clusterId);
				String name = sp != null ? sp.name() : SpeakerNames.channelName(channel, l.audioMode);
				String key = sp != null ? com.earshot.api.conversation.ConversationService.key(sp)
						: "Others".equals(name) ? "others" : "Room".equals(name) ? "room" : "me";
				var seg = new HashMap<String, Object>();
				seg.put("id", id);
				seg.put("startS", st);
				seg.put("endS", e);
				seg.put("clock", l.startedAt.plusMillis((long) (st * 1000)).atZone(l.zone).format(HMS));
				seg.put("speaker", name);
				seg.put("key", key);
				seg.put("clusterId", clusterId);
				seg.put("channel", channel);
				seg.put("text", ev.get("text").asString());
				seg.put("live", true);
				sockets.broadcast(l.meetingId, Map.of("type", "segment", "segment", seg));
				l.newSegments.incrementAndGet();
				liveIndex.submit(() -> {
					try {
						indexer.indexConversation(l.conversationId);
					} catch (Exception ex) {
						log.warn("event=live_index_failed reason={}", ex.getMessage());
					}
				});
			}
			case "speakers" -> {
				String channel = ev.get("channel").asString();
				for (JsonNode sp : ev.get("speakers")) {
					long id = cluster(l, channel, sp.get("label").asString());
					db.sql("UPDATE speaker_cluster SET embedding = CAST(:e AS vector), speech_s = :s WHERE id = :id")
							.param("e", sp.get("embedding").toString()).param("s", sp.path("speech_s").asDouble()).param("id", id).update();
					// A voice remembered early in the meeting improves as the person keeps talking.
					db.sql("UPDATE voiceprint v SET embedding = sc.embedding FROM speaker_cluster sc WHERE sc.id = :id AND v.cluster_id = sc.id AND v.person_id = sc.person_id")
							.param("id", id).update();
					matcher.matchLive(id, "mic_system".equals(l.audioMode));
				}
				broadcastSpeakers(l);
			}
			case "relabel" -> {
				String channel = ev.get("channel").asString();
				var changes = new java.util.ArrayList<Map<String, Object>>();
				for (JsonNode c : ev.get("changes")) {
					Long segId = l.segments.get(c.get("key").asString());
					if (segId == null) {
						continue;
					}
					long clusterId = cluster(l, channel, c.get("speaker").asString());
					// A line the user moved by hand stays where they put it.
					if (db.sql("UPDATE segment SET speaker_cluster_id = :c WHERE id = :id AND NOT speaker_manual").param("c", clusterId)
							.param("id", segId).update() == 0) {
						continue;
					}
					changes.add(Map.of("segmentId", segId, "clusterId", clusterId));
				}
				// Labels regrouping emptied are gone, unless the user named them.
				db.sql("""
						DELETE FROM speaker_cluster sc WHERE sc.conversation_id = :c AND NOT sc.manual
						  AND NOT EXISTS (SELECT 1 FROM segment s WHERE s.speaker_cluster_id = sc.id)""").param("c", l.conversationId).update();
				l.clusters.entrySet().removeIf(en -> db.sql("SELECT count(*) FROM speaker_cluster WHERE id = :id").param("id", en.getValue())
						.query(Long.class).single() == 0);
				sockets.broadcast(l.meetingId, Map.of("type", "relabel", "changes", changes));
				broadcastSpeakers(l);
			}
			case "status" -> {
				l.lagS = ev.path("lag_s").asDouble();
				l.rtf = ev.path("asr_rtf").asDouble();
				sockets.broadcast(l.meetingId, status(l));
			}
			case "error" -> sockets.broadcast(l.meetingId, Map.of("type", "warning", "message", "Transcription error: " + ev.path("message").asString()));
			default -> {
			}
		}
	}

	/** The speaker_cluster row for a live label, created on first sight. */
	private long cluster(Live l, String channel, String label) {
		return l.clusters.computeIfAbsent(channel + ":" + label, k -> {
			if (label.startsWith("C")) {
				// A speaker from an earlier part: same person, same name.
				long origin = Long.parseLong(label.substring(1));
				return db.sql("""
						INSERT INTO speaker_cluster (conversation_id, label, channel, origin_cluster_id, person_id, manual)
						SELECT :c, :l, :ch, id, person_id, manual FROM speaker_cluster WHERE id = :o RETURNING id""")
						.param("c", l.conversationId).param("l", "live:" + k).param("ch", channel).param("o", origin).query(Long.class).single();
			}
			return db.sql("INSERT INTO speaker_cluster (conversation_id, label, channel) VALUES (:c, :l, :ch) RETURNING id")
					.param("c", l.conversationId).param("l", "live:" + k).param("ch", channel).query(Long.class).single();
		});
	}

	private void broadcastSpeakers(Live l) {
		var list = conversations.speakers(l.conversationId).stream()
				.filter(sp -> db.sql("SELECT count(*) FROM segment WHERE speaker_cluster_id = :id").param("id", sp.clusterId()).query(Long.class).single() > 0)
				.toList();
		sockets.broadcast(l.meetingId, Map.of("type", "speakers", "speakers", list));
	}

	/** After the user names a live speaker: re-match the others (the same voice may have two labels) and push names. */
	public void onSpeakerChanged(long clusterId) {
		var l = live;
		if (l == null) {
			return;
		}
		boolean ours = db.sql("SELECT count(*) FROM speaker_cluster WHERE id = :id AND conversation_id = :c").param("id", clusterId)
				.param("c", l.conversationId).query(Long.class).single() > 0;
		if (!ours) {
			return;
		}
		l.clusters.values().forEach(id -> matcher.matchLive(id, "mic_system".equals(l.audioMode)));
		broadcastSpeakers(l);
	}

	/** After the user moves one line during a meeting: show it under its new speaker at once. */
	public void onLineChanged(long segmentId, long clusterId) {
		var l = live;
		if (l == null || db.sql("SELECT count(*) FROM segment WHERE id = :id AND conversation_id = :c").param("id", segmentId)
				.param("c", l.conversationId).query(Long.class).single() == 0) {
			return;
		}
		sockets.broadcast(l.meetingId, Map.of("type", "relabel", "changes", List.of(Map.of("segmentId", segmentId, "clusterId", clusterId))));
		broadcastSpeakers(l);
	}

	private Map<String, Object> status(Live l) {
		var m = new HashMap<String, Object>();
		m.put("type", "status");
		m.put("lagS", l.lagS);
		m.put("asrRtf", l.rtf);
		m.put("llmQueue", llm.queueDepth());
		m.put("elapsedS", Duration.between(l.startedAt, Instant.now()).toSeconds());
		m.put("warning", l.warning);
		m.put("device", l.device);
		m.put("language", l.language);
		return m;
	}

	@Scheduled(fixedDelay = 5_000)
	public void tick() {
		var l = live;
		if (l == null) {
			return;
		}
		try {
			var st = ml.captureStatus("m" + l.meetingId);
			var ch = st.path("channels");
			l.device = st.path("device").asString(null);
			// Silent call audio while the user is talking: the call app is probably not routed through BlackHole (PRD §6.2).
			boolean silentSystem = ch.has("system") && ch.has("mic") && ch.path("system").path("silent_s").asDouble() >= 60
					&& ch.path("mic").path("silent_s").asDouble() < 5;
			l.warning = silentSystem ? "No call audio is being captured. Set the call app's speaker to the Multi-Output Device." : null;
			// The call heard by the mic: speakers instead of headphones, so others would be labelled as the user.
			if (st.path("echo").path("leaking").asBoolean(false)) {
				l.warning = "Your mic is picking up the call (speakers?): other people may be labelled as you. Switch to headphones.";
			}
			if (st.path("error").isString()) {
				l.warning = "Capture error: " + st.path("error").asString();
			}
		} catch (Exception e) {
			if (live != l) {
				return; // stopped while this tick ran: the capture process is gone on purpose
			}
			l.warning = "Capture process not responding.";
		}
		if (live != l) {
			return;
		}
		sockets.broadcast(l.meetingId, status(l));
		boolean due = l.newSegments.get() >= settings.num("summary_every_segments")
				|| (l.newSegments.get() > 0 && Duration.between(l.lastSummary, Instant.now()).toSeconds() >= settings.num("summary_interval_seconds"));
		// Summaries wait while ASR is behind: transcription has priority (PRD §8).
		if (due && l.lagS < settings.num("live_lag_skip_summary_seconds") && l.summarizing.compareAndSet(false, true)) {
			Thread.ofVirtual().start(() -> {
				try {
					summarize(l.meetingId, l.conversationId, settings.str("llm_live_model"));
					l.newSegments.set(0);
					l.lastSummary = Instant.now();
				} catch (Exception e) {
					log.warn("event=summary_failed meeting={} reason={}", l.meetingId, e.getMessage());
				} finally {
					l.summarizing.set(false);
				}
			});
		}
	}

	/** Rolling summary, topics, decisions, action items and agenda status (PRD §6.5). */
	public void summarize(long meetingId, long conversationId, String model) {
		var meta = db.sql("SELECT pre_context, rolling_summary::text, started_at, audio_mode FROM meeting WHERE id = :id").param("id", meetingId)
				.query((rs, i) -> new Object[] { rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4) }).single();
		var speakerNames = names.forConversation(conversationId);
		var segs = db.sql("SELECT start_s, end_s, channel, text, speaker_cluster_id FROM segment WHERE conversation_id = :c ORDER BY start_s")
				.param("c", conversationId).query((rs, i) -> new Object[] { rs.getDouble(1), rs.getDouble(2), rs.getString(3), rs.getString(4),
						rs.getObject(5) })
				.list();
		if (segs.isEmpty()) {
			return;
		}
		int from = Math.max(0, segs.size() - 80);
		var lines = new StringBuilder();
		Instant start = (Instant) meta[2];
		for (int i = from; i < segs.size(); i++) {
			Object[] s = segs.get(i);
			lines.append('[').append(i + 1).append("] ").append(start.plusMillis((long) ((double) s[0] * 1000)).atZone(ZoneId.systemDefault()).format(HMS))
					.append(' ').append(s[4] != null && speakerNames.containsKey(((Number) s[4]).longValue())
							? speakerNames.get(((Number) s[4]).longValue()).name()
							: SpeakerNames.channelName((String) s[2], (String) meta[3])).append(": ").append(s[3]).append('\n');
		}
		int part = db.sql("SELECT part FROM conversation WHERE id = :c").param("c", conversationId).query(Integer.class).single();
		// A resumed meeting carries its earlier parts' summary forward.
		String previous = (from > 0 || part > 1) && meta[1] != null ? "Summary of the earlier part:\n" + meta[1] + "\n\n" : "";
		String agenda = meta[0] == null || ((String) meta[0]).isBlank() ? "" : "Agenda / pre-meeting notes:\n" + truncate((String) meta[0], 2000) + "\n\n";
		String prompt = previous + agenda + "Transcript so far:\n" + lines + """

				Return JSON only:
				{"summary": ["3-6 bullets of the meeting so far"], "topics": ["topics covered"],
				 "decisions": [{"text": "...", "refs": [line numbers]}],
				 "action_items": [{"text": "...", "owner": "Me, Others or a name, or null", "refs": [line numbers]}]%s}
				Only include what is in the transcript.""".formatted(agenda.isEmpty() ? ""
						: ",\n \"agenda\": [{\"item\": \"agenda item\", \"state\": \"covered | partly | open\"}]");
		JsonNode out = llm.parseJson(llm.chat(model, List.of(Message.system("You keep running meeting notes as JSON."), Message.user(prompt)), true));
		var result = new LinkedHashMap<String, Object>();
		result.put("summary", texts(out.path("summary")));
		result.put("topics", texts(out.path("topics")));
		result.put("decisions", withRefs(out.path("decisions"), segs, start));
		result.put("action_items", withRefs(out.path("action_items"), segs, start));
		var agendaOut = new ArrayList<Map<String, String>>();
		out.path("agenda").forEach(a -> agendaOut.add(Map.of("item", a.path("item").asString(""), "state", a.path("state").asString("open"))));
		result.put("agenda", agendaOut);
		result.put("updatedAt", Instant.now().toString());
		db.sql("UPDATE meeting SET rolling_summary = CAST(:s AS jsonb), summary_at = now() WHERE id = :id")
				.param("s", json.writeValueAsString(result)).param("id", meetingId).update();
		sockets.broadcast(meetingId, Map.of("type", "summary", "summary", result));
		log.info("event=meeting_summarized meeting={} segments={}", meetingId, segs.size());
	}

	private static String truncate(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n);
	}

	private static List<String> texts(JsonNode arr) {
		var out = new ArrayList<String>();
		arr.forEach(x -> out.add(x.isString() ? x.asString() : x.path("text").asString(x.toString())));
		return out;
	}

	private static List<Map<String, Object>> withRefs(JsonNode arr, List<Object[]> segs, Instant start) {
		var out = new ArrayList<Map<String, Object>>();
		arr.forEach(x -> {
			var cites = new ArrayList<Map<String, Object>>();
			x.path("refs").forEach(r -> {
				int n = r.asInt();
				if (n >= 1 && n <= segs.size()) {
					Object[] s = segs.get(n - 1);
					cites.add(Map.of("startS", s[0], "endS", s[1],
							"clock", start.plusMillis((long) ((double) s[0] * 1000)).atZone(ZoneId.systemDefault()).format(HMS)));
				}
			});
			var m = new LinkedHashMap<String, Object>();
			m.put("text", x.isString() ? x.asString() : x.path("text").asString(""));
			if (x.path("owner").isString()) {
				m.put("owner", x.path("owner").asString());
			}
			m.put("citations", cites);
			out.add(m);
		});
		return out;
	}

	public synchronized Map<String, Object> stop(long meetingId) {
		var l = live;
		if (l == null || l.meetingId != meetingId) {
			throw new IllegalStateException("That meeting is not recording.");
		}
		JsonNode files;
		try {
			files = ml.captureStop("m" + meetingId);
		} finally {
			try {
				ml.liveStop("m" + meetingId);
			} catch (Exception e) {
				log.warn("event=live_stop_failed reason={}", e.getMessage());
			}
			if (l.ml != null) {
				l.ml.sendClose(WebSocket.NORMAL_CLOSURE, "stop");
			}
			live = null;
		}
		double duration = files.path("duration_s").asDouble();
		if (duration < 1 && db.sql("SELECT count(*) FROM conversation WHERE meeting_id = :m").param("m", meetingId).query(Long.class).single() > 1) {
			// An empty resumed part: drop the part, keep the meeting.
			db.sql("DELETE FROM recording WHERE id = :id").param("id", l.recordingId).update();
			db.sql("UPDATE meeting SET status = 'done', ended_at = now() WHERE id = :id").param("id", meetingId).update();
			sockets.broadcast(meetingId, Map.of("type", "stopped"));
			return Map.of("meetingId", meetingId, "durationS", 0);
		}
		if (duration < 1) {
			discard(meetingId, l.recordingId);
			return Map.of("meetingId", meetingId, "durationS", 0, "discarded", true);
		}
		db.sql("UPDATE meeting SET ended_at = now(), status = 'processing', capture_files = CAST(:f AS jsonb) WHERE id = :id")
				.param("f", files.path("files").toString()).param("id", meetingId).update();
		db.sql("UPDATE recording SET duration_s = :d, status = 'captured' WHERE id = :id").param("d", duration).param("id", l.recordingId).update();
		db.sql("""
				UPDATE conversation SET end_offset_s = GREATEST(end_offset_s, :d), status = 'queued', capture_files = CAST(:f AS jsonb)
				WHERE id = :id""").param("d", duration).param("f", files.path("files").toString()).param("id", l.conversationId).update();
		jobs.enqueue("post_meeting", l.recordingId, l.conversationId, meetingId);
		worker.wake();
		sockets.broadcast(meetingId, Map.of("type", "stopped"));
		log.info("event=meeting_stopped meeting={} duration_s={}", meetingId, Math.round(duration));
		return Map.of("meetingId", meetingId, "durationS", duration);
	}

	/** A meeting that captured no audio is removed rather than kept as an empty record. */
	private void discard(long meetingId, long recordingId) {
		db.sql("DELETE FROM recording WHERE id = :id").param("id", recordingId).update();
		deleteDir(meetingsDir.resolve(String.valueOf(meetingId)));
		sockets.broadcast(meetingId, Map.of("type", "discarded"));
		log.info("event=meeting_discarded meeting={} reason=no_audio", meetingId);
	}

	private static void deleteDir(Path dir) {
		try (var walk = Files.walk(dir)) {
			for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(f);
			}
		} catch (java.io.IOException e) {
			// best effort: an empty folder left behind is harmless
		}
	}

	/**
	 * After a service restart, meetings still marked live have no session here. Their capture may
	 * still be running (it outlives a backend crash): stop it and process what was recorded.
	 */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	@org.springframework.core.annotation.Order(100)
	public void recoverOrphans() {
		var orphans = db.sql("""
				SELECT m.id, c.recording_id, c.id FROM meeting m JOIN conversation c ON c.meeting_id = m.id AND c.status = 'live'
				WHERE m.status = 'live'""")
				.query((rs, i) -> new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) }).list();
		for (long[] o : orphans) {
			double duration = 0;
			JsonNode files = null;
			try {
				files = ml.captureStop("m" + o[0]);
				duration = files.path("duration_s").asDouble();
			} catch (Exception e) {
				// capture no longer has it: use whatever chunks reached the disk
				files = chunkFiles(o[0]);
				duration = files == null ? 0 : 1;
			}
			if (duration < 1 || files == null) {
				discard(o[0], o[1]);
				continue;
			}
			db.sql("UPDATE meeting SET ended_at = now(), status = 'processing', capture_files = CAST(:f AS jsonb) WHERE id = :id")
					.param("f", files.path("files").toString()).param("id", o[0]).update();
			db.sql("UPDATE conversation SET status = 'queued', capture_files = CAST(:f AS jsonb) WHERE id = :id")
					.param("f", files.path("files").toString()).param("id", o[2]).update();
			jobs.enqueue("post_meeting", o[1], o[2], o[0]);
			log.info("event=meeting_recovered meeting={}", o[0]);
		}
	}

	/** Joined files are written at Stop; after a crash, join the 30 s chunks here. */
	private JsonNode chunkFiles(long meetingId) {
		Path dir = meetingsDir.resolve(String.valueOf(meetingId));
		var files = new LinkedHashMap<String, String>();
		for (String ch : List.of("mic", "system")) {
			Path chDir = dir.resolve(ch);
			try (var list = Files.list(chDir)) {
				var chunks = list.filter(p -> p.getFileName().toString().endsWith(".flac")).sorted().toList();
				if (chunks.isEmpty()) {
					continue;
				}
				Path listFile = dir.resolve(ch + "-chunks.txt");
				Files.writeString(listFile, String.join("\n", chunks.stream().map(c -> "file '" + c + "'").toList()));
				Path out = dir.resolve(ch + ".flac");
				var p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(), out.toString())
						.redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();
				if (p.waitFor() == 0) {
					files.put(ch, out.toString());
				}
			} catch (Exception e) {
				// no chunks for this channel
			}
		}
		return files.isEmpty() ? null : json.valueToTree(Map.of("files", files));
	}

	public Map<String, Object> view(long meetingId) {
		var row = db.sql("""
				SELECT m.id, m.title, m.audio_mode, m.started_at, m.ended_at, m.status, m.rolling_summary::text AS summary, m.pre_context,
				       c.id AS conversation_id, c.recording_id
				FROM meeting m JOIN conversation c ON c.meeting_id = m.id WHERE m.id = :id ORDER BY c.part DESC LIMIT 1""")
				.param("id", meetingId).query().singleRow();
		var out = new LinkedHashMap<String, Object>(row);
		out.put("summary", row.get("summary") == null ? null : json.readTree((String) row.get("summary")));
		out.put("live", activeId() != null && activeId() == meetingId);
		out.put("parts", db.sql("""
				SELECT c.part, c.id AS conversation_id, c.recording_id, c.started_at, c.local_date::text AS local_date,
				       c.end_offset_s - c.start_offset_s AS duration_s
				FROM conversation c WHERE c.meeting_id = :id ORDER BY c.part""").param("id", meetingId).query().listOfRows());
		var l = live;
		if (l != null && l.meetingId == meetingId) {
			out.put("status_live", status(l));
		}
		return out;
	}
}
