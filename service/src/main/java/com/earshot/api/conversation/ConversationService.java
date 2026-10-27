package com.earshot.api.conversation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.speaker.SpeakerMatcher;
import com.earshot.api.speaker.SpeakerNames;
import com.earshot.api.speaker.Vectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read models for the Today view, conversation detail and exports, with names rendered. */
@Service
public class ConversationService {

	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
	private final JdbcClient db;
	private final SpeakerNames names;
	private final SpeakerMatcher matcher;
	private final JsonMapper json;

	public ConversationService(JdbcClient db, SpeakerNames names, SpeakerMatcher matcher, JsonMapper json) {
		this.db = db;
		this.names = names;
		this.matcher = matcher;
		this.json = json;
	}

	public record Tag(String label, String key) {
	}

	public record ConversationRow(long id, long recordingId, Long meetingId, Long duplicateOfMeetingId, String startClock,
			String endClock, double durationS, String title, List<Tag> speakers, boolean ambient, String stage, String status,
			Instant startedAt) {
	}

	public record DayView(LocalDate date, List<ConversationRow> conversations, int ambientCount, int meetingCount, boolean exported) {
	}

	public record SpeakerView(long clusterId, String name, Long personId, boolean isMe, String key, String channel,
			double speechS, String suggestion, Long suggestionPersonId, boolean recognised, Double matchScore,
			List<Candidate> candidates, boolean nearTie) {
	}

	/** One of the closest remembered voices to a speaker, offered when naming them. */
	public record Candidate(long personId, String name, boolean isMe, double score) {
	}

	public record ActionItem(long id, String text, String owner, boolean done) {
	}

	/**
	 * {@code elapsedS}: time into the conversation, or into the whole meeting across all its parts
	 * (owner request: to find where to resume a video that was playing).
	 */
	public record SegmentView(long id, double startS, double endS, String clock, String speaker, String key, Long clusterId,
			String channel, String text, boolean live, double elapsedS, String originalText, String editedBy) {
	}

	/** The user's own note; {@code clock} is set when it is pinned to a moment. */
	public record NoteView(long id, String text, Double anchorS, String clock, Instant createdAt) {
	}

	public record Detail(long id, long recordingId, Long meetingId, String source, LocalDate date, String startClock,
			String endClock, double durationS, String title, List<String> summary, List<String> topics,
			List<ActionItem> actionItems, List<SpeakerView> speakers, List<SegmentView> segments, List<NoteView> notes,
			List<AttachmentView> attachments, boolean ambient, String stage, String status, String audioMode, boolean hasAudio) {
	}

	/** A screenshot at a moment of the transcript (R-1). {@code path} is the file on disk, for exports. */
	public record AttachmentView(long id, double anchorS, String clock, double elapsedS, String caption, String url, String path,
			String originalName, String placedBy, Instant takenAt) {
	}

	public List<NoteView> notes(long conversationId) {
		Conv c = db.sql(CONV_SQL + " WHERE c.id = :id").param("id", conversationId).query((rs, i) -> conv(rs)).single();
		return notes(c);
	}

	private List<NoteView> notes(Conv c) {
		return db.sql("SELECT id, text, anchor_s, created_at FROM note WHERE conversation_id = :c ORDER BY COALESCE(anchor_s, 1e12), created_at")
				.param("c", c.id()).query((rs, i) -> {
					Double a = (Double) rs.getObject(3);
					return new NoteView(rs.getLong(1), rs.getString(2), a, a == null ? null : clock(c, a, HMS), rs.getTimestamp(4).toInstant());
				}).list();
	}

	record Conv(long id, long recordingId, Long meetingId, Long dupOf, double startOff, double endOff, Instant recStart,
			ZoneId zone, LocalDate date, String title, String summary, String topics, boolean ambient, String stage,
			String status, String sourceType, String audioMode, String workPath, int part) {
	}

	private static final String CONV_SQL = """
			SELECT c.id, c.recording_id, c.meeting_id, c.duplicate_of_meeting_id, c.start_offset_s, c.end_offset_s,
			       r.started_at AS rec_start, r.time_zone, c.local_date, c.title, c.summary::text AS summary, c.topics::text AS topics,
			       c.is_ambient, c.stage, c.status, r.source_type, m.audio_mode, r.work_path, c.part
			FROM conversation c JOIN recording r ON r.id = c.recording_id LEFT JOIN meeting m ON m.id = c.meeting_id
			""";

	private static Conv conv(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Conv(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), (Long) rs.getObject(4), rs.getDouble(5), rs.getDouble(6),
				rs.getTimestamp(7).toInstant(), ZoneId.of(rs.getString(8)), rs.getObject(9, LocalDate.class), rs.getString(10),
				rs.getString(11), rs.getString(12), rs.getBoolean(13), rs.getString(14), rs.getString(15), rs.getString(16),
				rs.getString(17), rs.getString(18), rs.getInt(19));
	}

	static String clock(Conv c, double offsetS, DateTimeFormatter f) {
		// Rounded: 19.871999… s must read as the second it was taken, not the one before.
		return c.recStart().plusMillis(Math.round(offsetS * 1000)).atZone(c.zone()).format(f);
	}

	public static String key(SpeakerNames.Speaker s) {
		if (s.isMe()) {
			return "me";
		}
		return s.personId() != null ? "p" + s.personId() : "u" + s.clusterId();
	}

	public List<Map<String, Object>> days() {
		return db.sql("""
				SELECT local_date::text AS local_date, count(*) FILTER (WHERE NOT is_ambient AND duplicate_of_meeting_id IS NULL) AS conversations,
				       count(*) FILTER (WHERE is_ambient) AS ambient
				FROM conversation GROUP BY local_date ORDER BY local_date DESC""").query().listOfRows();
	}

	public DayView day(LocalDate date) {
		var convs = db.sql(CONV_SQL + " WHERE c.local_date = :d ORDER BY r.started_at + make_interval(secs => c.start_offset_s)")
				.param("d", date).query((rs, i) -> conv(rs)).list();
		var rows = new ArrayList<ConversationRow>();
		int ambient = 0, meetings = 0;
		for (Conv c : convs) {
			var speakers = names.forConversation(c.id());
			var tags = new LinkedHashSet<Tag>();
			cardSpeakers(speakers, firstSpoken(c.id())).forEach(s -> tags.add(new Tag(s.name(), key(s))));
			if (speakers.isEmpty() && c.meetingId() != null) {
				// No speaker separation (recorded before it existed): say what the channels can tell.
				if ("mic".equals(c.audioMode())) {
					tags.add(new Tag("Room", "room"));
				} else {
					if ("mic_system".equals(c.audioMode())) {
						tags.add(new Tag("Me", "me"));
					}
					tags.add(new Tag("Others", "others"));
				}
			}
			ambient += c.ambient() ? 1 : 0;
			meetings += c.meetingId() != null ? 1 : 0;
			rows.add(new ConversationRow(c.id(), c.recordingId(), c.meetingId(), c.dupOf(), clock(c, c.startOff(), HM),
					clock(c, c.endOff(), HM), c.endOff() - c.startOff(), displayTitle(c, speakers), List.copyOf(tags), c.ambient(),
					c.stage(), c.status(), c.recStart().plusMillis((long) (c.startOff() * 1000))));
		}
		return new DayView(date, rows, ambient, meetings, false);
	}

	/**
	 * When each speaker first spoke, for the speakers shown: as on the conversation page, Earshot's speakers
	 * are hidden while another engine's transcript is in use. Not yet heard: no entry's value (null).
	 */
	private Map<Long, Double> firstSpoken(long id) {
		var out = new HashMap<Long, Double>();
		db.sql("""
				SELECT sc.id, (SELECT min(s.start_s) FROM segment s WHERE s.speaker_cluster_id = sc.id) FROM speaker_cluster sc
				WHERE sc.conversation_id = :c
				  AND (EXISTS (SELECT 1 FROM segment s WHERE s.speaker_cluster_id = sc.id)
				       OR (SELECT transcript_version_id FROM conversation WHERE id = :c) IS NULL)""")
				.param("c", id).query(rs -> {
					out.put(rs.getLong(1), (Double) rs.getObject(2));
				});
		return out;
	}

	/** A day card's speakers: only those shown for the conversation, "Me" first, then by who spoke first. */
	static List<SpeakerNames.Speaker> cardSpeakers(Map<Long, SpeakerNames.Speaker> speakers, Map<Long, Double> firstSpoken) {
		return speakers.values().stream().filter(s -> firstSpoken.containsKey(s.clusterId()))
				.sorted(speakerOrder(SpeakerNames.Speaker::isMe, SpeakerNames.Speaker::clusterId, firstSpoken)).toList();
	}

	/** "Me" first, then by who spoke first; not yet heard last. Day cards and the conversation page list speakers alike. */
	static <T> Comparator<T> speakerOrder(Predicate<T> isMe, ToLongFunction<T> clusterId, Map<Long, Double> firstSpoken) {
		return Comparator.comparing((T s) -> !isMe.test(s))
				.thenComparing(s -> firstSpoken.get(clusterId.applyAsLong(s)), Comparator.nullsLast(Comparator.<Double>naturalOrder()))
				.thenComparingLong(clusterId);
	}

	private String displayTitle(Conv c, Map<Long, SpeakerNames.Speaker> speakers) {
		if (c.title() != null) {
			return names.render(c.title(), speakers);
		}
		if (c.ambient()) {
			return "Ambient (not transcribed)";
		}
		return "done".equals(c.status()) ? "Conversation" : "Processing…";
	}

	/** Speakers of a conversation with current names and, for unnamed ones, a near-threshold suggestion. */
	public List<SpeakerView> speakers(long id) {
		return speakers(id, names.forConversation(id));
	}

	private List<SpeakerView> speakers(long id, Map<Long, SpeakerNames.Speaker> speakers) {
		// While another engine's transcript is in use, Earshot's speakers have no lines here and are not shown.
		var speechByCluster = db.sql("""
				SELECT id, speech_s, embedding::text, person_id, manual, match_score FROM speaker_cluster sc WHERE conversation_id = :c
				  AND (EXISTS (SELECT 1 FROM segment s WHERE s.speaker_cluster_id = sc.id)
				       OR (SELECT transcript_version_id FROM conversation WHERE id = :c) IS NULL)""")
				.param("c", id).query((rs, i) -> new Object[] { rs.getLong(1), rs.getDouble(2), rs.getString(3), rs.getObject(4), rs.getBoolean(5),
						(Double) rs.getObject(6) }).list();
		var personNames = new HashMap<Long, String>();
		db.sql("SELECT id, CASE WHEN is_me THEN 'Me' ELSE name END FROM person").query((rs, i) -> personNames.put(rs.getLong(1), rs.getString(2))).list();
		var speakerViews = new ArrayList<SpeakerView>();
		for (Object[] row : speechByCluster) {
			var s = speakers.get((Long) row[0]);
			if (s == null) {
				continue;
			}
			var ranked = row[2] == null ? List.<SpeakerMatcher.Best>of() : matcher.ranked(Vectors.parse((String) row[2]));
			var candidates = matcher.candidates(ranked, 4).stream()
					.map(b -> new Candidate(b.personId(), personNames.get(b.personId()), b.isMe(), b.score())).toList();
			boolean manual = (boolean) row[4];
			// Unnamed by the app, not by the user ("I don't know who this is" offers nothing more).
			boolean nearTie = row[3] == null && !manual && matcher.nearTie(ranked);
			String suggestion = null;
			Long suggestionId = null;
			if (row[3] == null && !manual && !candidates.isEmpty()) {
				var best = candidates.getFirst();
				suggestionId = best.personId();
				suggestion = nearTie
						? best.name() + " or " + candidates.get(1).name()
						: best.name() + " (" + String.format("%.2f", best.score()) + ")";
			}
			// Named by the app from a remembered voice, not by the user: worth a glance.
			boolean recognised = s.personId() != null && !s.isMe() && !manual;
			speakerViews.add(new SpeakerView(s.clusterId(), s.name(), s.personId(), s.isMe(), key(s), s.channel(), (double) row[1],
					suggestion, suggestionId, recognised, recognised ? (Double) row[5] : null, candidates, nearTie));
		}
		// The same order as the day card (owner, 2026-10-26).
		var first = firstSpoken(id);
		speakerViews.sort(speakerOrder(SpeakerView::isMe, SpeakerView::clusterId, first));
		return speakerViews;
	}

	public Detail detail(long id) {
		Conv c = db.sql(CONV_SQL + " WHERE c.id = :id").param("id", id).query((rs, i) -> conv(rs)).single();
		var speakers = names.forConversation(id);
		var summary = new ArrayList<String>();
		if (c.summary() != null) {
			json.readTree(c.summary()).path("bullets").forEach(b -> summary.add(names.render(b.asString(), speakers)));
		}
		var topics = new ArrayList<String>();
		if (c.topics() != null) {
			json.readTree(c.topics()).forEach(t -> topics.add(t.asString()));
		}
		var actions = db.sql("SELECT id, text, owner_cluster_id, done FROM action_item WHERE conversation_id = :c ORDER BY id")
				.param("c", id).query((rs, i) -> {
					Long owner = (Long) rs.getObject(3);
					var sp = owner == null ? null : speakers.get(owner);
					return new ActionItem(rs.getLong(1), names.render(rs.getString(2), speakers), sp == null ? null : sp.name(), rs.getBoolean(4));
				}).list();
		var speakerViews = speakers(id, speakers);
		// A meeting's earlier parts count towards elapsed time; a recorder conversation starts at zero.
		double base = c.meetingId() == null ? -c.startOff() : db.sql("""
				SELECT COALESCE(sum(end_offset_s - start_offset_s), 0) FROM conversation WHERE meeting_id = :m AND part < :p""")
				.param("m", c.meetingId()).param("p", c.part()).query(Double.class).single();
		var segments = db.sql("""
				SELECT id, start_s, end_s, speaker_cluster_id, channel, text, live, original_text, edited_by FROM segment
				WHERE conversation_id = :c
				  -- While being re-transcribed, the current lines (marked live) show until the new ones replace them.
				  AND (live OR NOT EXISTS (SELECT 1 FROM conversation x WHERE x.id = :c AND x.stage = 'retranscribing'))
				ORDER BY start_s""").param("c", id).query((rs, i) -> {
			Long cid = (Long) rs.getObject(4);
			var sp = cid == null ? null : speakers.get(cid);
			String channel = rs.getString(5);
			String name = sp != null ? sp.name() : SpeakerNames.channelName(channel, c.audioMode());
			String key = sp != null ? key(sp) : ("Others".equals(name) ? "others" : "Room".equals(name) ? "room" : "me");
			return new SegmentView(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), clock(c, rs.getDouble(2), HMS), name, key, cid,
					channel, rs.getString(6), rs.getBoolean(7), base + rs.getDouble(2), rs.getString(8), rs.getString(9));
		}).list();
		var attachments = db.sql("""
				SELECT id, anchor_s, caption, path, original_name, placed_by, taken_at FROM attachment WHERE conversation_id = :c ORDER BY anchor_s, id""")
				.param("c", id).query((rs, i) -> new AttachmentView(rs.getLong(1), rs.getDouble(2), clock(c, rs.getDouble(2), HMS),
						base + rs.getDouble(2), rs.getString(3), "/api/attachments/" + rs.getLong(1) + "/file", rs.getString(4), rs.getString(5),
						rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant())).list();
		return new Detail(id, c.recordingId(), c.meetingId(), c.sourceType(), c.date(), clock(c, c.startOff(), HM),
				clock(c, c.endOff(), HM), c.endOff() - c.startOff(), displayTitle(c, speakers), summary, topics, actions,
				speakerViews, segments, notes(c), attachments, c.ambient(), c.stage(), c.status(), c.audioMode(), c.workPath() != null);
	}

	public JsonNode raw(String text) {
		return json.readTree(text);
	}
}
