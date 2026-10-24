package com.earshot.api.search;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.llm.LlmClient;
import com.earshot.api.speaker.SpeakerNames;
import com.earshot.api.speaker.Vectors;

/**
 * Hybrid search (PRD §5.6): full text and trigram, plus vectors when the optional semantic search
 * is on, merged by reciprocal-rank fusion.
 * A query word that names a person also ranks that person's segments higher.
 */
@Service
public class SearchService {

	private static final Logger log = LoggerFactory.getLogger(SearchService.class);
	private static final int K = 60;
	private static final int PER_LIST = 40;
	private static final Set<String> STOP = Set.of("a", "an", "the", "was", "were", "is", "are", "be", "been", "did", "do", "does",
			"we", "i", "you", "he", "she", "they", "it", "of", "to", "in", "on", "for", "and", "or", "about", "what", "who", "when",
			"where", "why", "how", "any", "anything", "discussed", "discuss", "talk", "talked", "said", "say", "with", "that", "this",
			"there", "my", "our", "me", "us", "at", "by", "from", "so", "if", "not", "no", "yes");
	private final JdbcClient db;
	private final Indexer indexer;
	private final SpeakerNames names;

	public SearchService(JdbcClient db, Indexer indexer, SpeakerNames names) {
		this.db = db;
		this.indexer = indexer;
		this.names = names;
	}

	/** Scope: a date range, or one set of conversations (a meeting). */
	public record Scope(LocalDate from, LocalDate to, List<Long> conversationIds) {
		public static Scope all() {
			return new Scope(null, null, null);
		}
	}

	public record Hit(long segmentId, long conversationId, long recordingId, double startS, double endS, String text, String speaker,
			LocalDate date, String clock, String conversationTitle, double score) {
	}

	public static List<String> terms(String q) {
		return Arrays.stream(q.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s'-]", " ").split("\\s+"))
				.filter(w -> w.length() > 1 && !STOP.contains(w)).distinct().toList();
	}

	private String scopeSql(Scope s, Map<String, Object> params) {
		var sb = new StringBuilder(" AND NOT c.is_ambient");
		if (s.conversationIds() != null) {
			sb.append(" AND c.id IN (:convs)");
			params.put("convs", s.conversationIds().isEmpty() ? List.of(-1L) : s.conversationIds());
		}
		if (s.from() != null) {
			sb.append(" AND c.local_date >= :from");
			params.put("from", s.from());
		}
		if (s.to() != null) {
			sb.append(" AND c.local_date <= :to");
			params.put("to", s.to());
		}
		return sb.toString();
	}

	private List<Long> ids(String sql, Map<String, Object> params) {
		var spec = db.sql(sql);
		for (var e : params.entrySet()) {
			spec = spec.param(e.getKey(), e.getValue());
		}
		return spec.query(Long.class).list();
	}

	/** Keyword search: no model call. */
	public List<Hit> search(String query, Scope scope, int limit) {
		return search(query, scope, limit, false);
	}

	/** With {@code byMeaning}, one embedding call for the query (the user ticked "by meaning"). */
	public List<Hit> search(String query, Scope scope, int limit, boolean byMeaning) {
		var lists = new ArrayList<List<Long>>();
		var terms = terms(query);
		var base = new HashMap<String, Object>();
		String where = scopeSql(scope, base);
		String from = " FROM segment s JOIN conversation c ON c.id = s.conversation_id WHERE true";
		if (!terms.isEmpty()) {
			var p = new HashMap<>(base);
			p.put("q", String.join(" | ", terms.stream().map(t -> t.replace("'", "")).filter(t -> !t.isBlank()).toList()));
			p.put("n", PER_LIST);
			lists.add(ids("SELECT s.id" + from + where + " AND s.tsv @@ to_tsquery('simple', :q) ORDER BY ts_rank(s.tsv, to_tsquery('simple', :q)) DESC LIMIT :n", p));
			var t = new HashMap<>(base);
			t.put("terms", terms.stream().filter(x -> x.length() >= 4).toList());
			t.put("n", PER_LIST);
			if (!((List<?>) t.get("terms")).isEmpty()) {
				lists.add(ids("""
						SELECT s.id%s%s AND EXISTS (SELECT 1 FROM unnest(ARRAY[:terms]::text[]) term WHERE word_similarity(term, s.text) > 0.55)
						ORDER BY (SELECT max(word_similarity(term, s.text)) FROM unnest(ARRAY[:terms]::text[]) term) DESC LIMIT :n"""
						.formatted(from, where), t));
			}
			var people = db.sql("""
					SELECT p.id FROM person p WHERE lower(p.name) IN (:t) OR EXISTS (SELECT 1 FROM unnest(p.aliases) a WHERE lower(a) IN (:t))""")
					.param("t", terms).query(Long.class).list();
			if (!people.isEmpty()) {
				var sp = new HashMap<>(base);
				sp.put("people", people);
				sp.put("q", p.get("q"));
				sp.put("n", PER_LIST);
				lists.add(ids("SELECT s.id" + from + where + """
						 AND s.speaker_cluster_id IN (SELECT id FROM speaker_cluster WHERE person_id IN (:people))
						ORDER BY ts_rank(s.tsv, to_tsquery('simple', :q)) DESC, s.start_s DESC LIMIT :n""", sp));
			}
		}
		// Meaning-based ranking only when the user asks for it on this search (owner rule, 2026-10-24).
		if (byMeaning) {
			try {
				float[] qv = indexer.embedQuery(query);
				var v = new HashMap<>(base);
				v.put("v", Vectors.literal(qv));
				v.put("m", indexer.model());
				v.put("n", PER_LIST);
				lists.add(ids("""
						SELECT s.id FROM segment_vec sv JOIN segment s ON s.id = sv.segment_id JOIN conversation c ON c.id = s.conversation_id
						WHERE sv.model = :m%s ORDER BY (sv.embedding::vector(%d)) <=> CAST(:v AS vector(%d)) LIMIT :n"""
						.formatted(where, qv.length, qv.length), v));
			} catch (LlmClient.LlmUnavailable e) {
				log.warn("event=semantic_search_skipped reason={}", e.getMessage());
				throw e;
			}
		}
		Map<Long, Double> score = new LinkedHashMap<>();
		for (var list : lists) {
			for (int i = 0; i < list.size(); i++) {
				score.merge(list.get(i), 1.0 / (K + i + 1), Double::sum);
			}
		}
		var top = score.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(limit).toList();
		var hits = new ArrayList<Hit>();
		for (var e : top) {
			hits.add(hit(e.getKey(), e.getValue()));
		}
		return hits;
	}

	public record NoteHit(long noteId, long conversationId, String text, LocalDate date, String clock, String conversationTitle) {
	}

	/** The user's own notes matching the query (full text or fuzzy), best first. */
	public List<NoteHit> searchNotes(String query, Scope scope, int limit) {
		var terms = terms(query);
		if (terms.isEmpty()) {
			return List.of();
		}
		var p = new HashMap<String, Object>();
		String where = scopeSql(scope, p);
		p.put("q", String.join(" | ", terms.stream().map(t -> t.replace("'", "")).filter(t -> !t.isBlank()).toList()));
		var fuzzy = terms.stream().filter(x -> x.length() >= 4).toList();
		p.put("terms", fuzzy.isEmpty() ? terms : fuzzy);
		p.put("n", limit);
		var spec = db.sql("""
				SELECT n.id, n.conversation_id, n.text, c.local_date, n.anchor_s, r.started_at, r.time_zone, c.title
				FROM note n JOIN conversation c ON c.id = n.conversation_id JOIN recording r ON r.id = c.recording_id
				WHERE (n.tsv @@ to_tsquery('simple', :q)
				       OR EXISTS (SELECT 1 FROM unnest(ARRAY[:terms]::text[]) term WHERE word_similarity(term, n.text) > 0.55))""" + where
				+ " ORDER BY ts_rank(n.tsv, to_tsquery('simple', :q)) DESC, n.created_at DESC LIMIT :n");
		for (var e : p.entrySet()) {
			spec = spec.param(e.getKey(), e.getValue());
		}
		return spec.query((rs, i) -> {
			long conv = rs.getLong(2);
			Double anchor = (Double) rs.getObject(5);
			String clock = anchor == null ? null : rs.getTimestamp(6).toInstant().plusMillis((long) (anchor * 1000))
					.atZone(ZoneId.of(rs.getString(7))).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
			return new NoteHit(rs.getLong(1), conv, rs.getString(3), rs.getObject(4, LocalDate.class), clock,
					names.render(rs.getString(8), names.forConversation(conv)));
		}).list();
	}

	public Hit hit(long segmentId, double score) {
		return db.sql("""
				SELECT s.id, s.conversation_id, c.recording_id, s.start_s, s.end_s, s.text, s.speaker_cluster_id, s.channel, c.local_date,
				       r.started_at, r.time_zone, c.title, m.audio_mode
				FROM segment s JOIN conversation c ON c.id = s.conversation_id JOIN recording r ON r.id = c.recording_id
				LEFT JOIN meeting m ON m.id = c.meeting_id WHERE s.id = :id""").param("id", segmentId).query((rs, i) -> {
			long conv = rs.getLong(2);
			var speakers = names.forConversation(conv);
			Long cid = (Long) rs.getObject(7);
			String speaker = cid != null && speakers.containsKey(cid) ? speakers.get(cid).name() : SpeakerNames.channelName(rs.getString(8), rs.getString(13));
			Instant at = rs.getTimestamp(10).toInstant().plusMillis((long) (rs.getDouble(4) * 1000));
			String clock = at.atZone(ZoneId.of(rs.getString(11))).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
			return new Hit(rs.getLong(1), conv, rs.getLong(3), rs.getDouble(4), rs.getDouble(5), rs.getString(6), speaker,
					rs.getObject(9, LocalDate.class), clock, names.render(rs.getString(12), speakers), score);
		}).single();
	}
}
