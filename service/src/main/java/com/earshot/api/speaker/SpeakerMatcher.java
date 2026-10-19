package com.earshot.api.speaker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.settings.Settings;

/** Matches diarized clusters to Me and the person library (PRD §5.2 step 7). */
@Service
public class SpeakerMatcher {

	private static final Logger log = LoggerFactory.getLogger(SpeakerMatcher.class);
	private final JdbcClient db;
	private final Settings settings;

	public SpeakerMatcher(JdbcClient db, Settings settings) {
		this.db = db;
		this.settings = settings;
	}

	record Print(long personId, boolean isMe, float[] embedding) {
	}

	public record Best(Long personId, boolean isMe, double score) {
	}

	public boolean hasMeVoiceprint() {
		return db.sql("SELECT count(*) FROM voiceprint v JOIN person p ON p.id = v.person_id WHERE p.is_me").query(Long.class).single() > 0;
	}

	List<Print> prints() {
		return db.sql("SELECT v.person_id, p.is_me, v.embedding::text FROM voiceprint v JOIN person p ON p.id = v.person_id")
				.query((rs, i) -> new Print(rs.getLong(1), rs.getBoolean(2), Vectors.parse(rs.getString(3)))).list();
	}

	/** Best score per person across all their voiceprints, best person first. */
	public List<Best> rank(float[] embedding, List<Print> prints) {
		Map<Long, Best> best = new HashMap<>();
		for (Print p : prints) {
			double s = Vectors.cosine(embedding, p.embedding());
			best.merge(p.personId(), new Best(p.personId(), p.isMe(), s), (a, b) -> a.score() >= b.score() ? a : b);
		}
		return best.values().stream().sorted((a, b) -> Double.compare(b.score(), a.score())).toList();
	}

	public record Result(double meSpeechS, double meSpeechLooseS, boolean ambient) {
	}

	/** Re-matches every non-manual cluster of a conversation and decides whether it is ambient. */
	public Result match(long conversationId) {
		double meT = settings.num("me_threshold");
		double meLoose = settings.num("me_ambient_threshold");
		double personT = settings.num("person_threshold");
		var prints = prints();
		boolean haveMe = prints.stream().anyMatch(Print::isMe);
		var clusters = db.sql("""
				SELECT id, embedding::text, speech_s, manual, person_id FROM speaker_cluster
				WHERE conversation_id = :c AND embedding IS NOT NULL""").param("c", conversationId)
				.query((rs, i) -> new Object[] { rs.getLong(1), Vectors.parse(rs.getString(2)), rs.getDouble(3), rs.getBoolean(4), rs.getObject(5) })
				.list();
		Long meId = db.sql("SELECT id FROM person WHERE is_me").query(Long.class).optional().orElse(null);
		double meSpeech = 0, meSpeechLoose = 0;
		for (Object[] c : clusters) {
			long id = (long) c[0];
			double speech = (double) c[2];
			var ranked = rank((float[]) c[1], prints);
			double meScore = ranked.stream().filter(Best::isMe).mapToDouble(Best::score).max().orElse(-1);
			if ((boolean) c[3]) {
				if (c[4] != null && ((Long) c[4]).equals(meId)) {
					meSpeech += speech;
					meSpeechLoose += speech;
				}
				continue;
			}
			Long person = null;
			double score = ranked.isEmpty() ? 0 : ranked.getFirst().score();
			if (!ranked.isEmpty() && ranked.getFirst().isMe() && score >= meT) {
				person = ranked.getFirst().personId();
			} else if (!ranked.isEmpty() && !ranked.getFirst().isMe() && score >= personT) {
				person = ranked.getFirst().personId();
			}
			if (meScore >= meT && person != null && person.equals(meId)) {
				meSpeech += speech;
			}
			if (meScore >= meLoose) {
				meSpeechLoose += speech;
			}
			db.sql("UPDATE speaker_cluster SET person_id = :p, match_score = :s WHERE id = :id").param("p", person)
					.param("s", score).param("id", id).update();
		}
		// Errs toward transcribing: only a conversation with no loose Me match either is ambient.
		boolean ambient = haveMe && meSpeechLoose < settings.num("me_min_speech_seconds");
		log.info("event=speakers_matched conversation={} clusters={} me_speech_s={} ambient={}", conversationId, clusters.size(),
				Math.round(meSpeech), ambient);
		return new Result(meSpeech, meSpeechLoose, ambient);
	}

	/**
	 * Matches one live cluster as its voice accumulates. A name the user gave (manual) is never
	 * overridden. In an online call without a Me voiceprint, the mic channel is the user.
	 */
	public void matchLive(long clusterId, boolean micIsMe) {
		var row = db.sql("SELECT embedding::text, manual, channel FROM speaker_cluster WHERE id = :id AND embedding IS NOT NULL")
				.param("id", clusterId).query((rs, i) -> new Object[] { rs.getString(1), rs.getBoolean(2), rs.getString(3) }).optional();
		if (row.isEmpty() || (boolean) row.get()[1]) {
			return;
		}
		var prints = prints();
		var ranked = rank(Vectors.parse((String) row.get()[0]), prints);
		Long person = null;
		double score = ranked.isEmpty() ? 0 : ranked.getFirst().score();
		if (!ranked.isEmpty()) {
			var top = ranked.getFirst();
			if (top.score() >= (top.isMe() ? settings.num("me_threshold") : settings.num("person_threshold"))) {
				person = top.personId();
			}
		}
		if (person == null && micIsMe && "mic".equals(row.get()[2]) && prints.stream().noneMatch(Print::isMe)) {
			person = db.sql("SELECT id FROM person WHERE is_me").query(Long.class).optional()
					.orElseGet(() -> db.sql("INSERT INTO person (name, is_me) VALUES ('Me', true) RETURNING id").query(Long.class).single());
		}
		db.sql("UPDATE speaker_cluster SET person_id = :p, match_score = :s WHERE id = :id").param("p", person).param("s", score)
				.param("id", clusterId).update();
	}

	/** A near-threshold suggestion for an unnamed cluster (PRD §7 naming flow). */
	public Best suggest(float[] embedding) {
		var ranked = rank(embedding, prints());
		if (ranked.isEmpty()) {
			return null;
		}
		var b = ranked.getFirst();
		return b.score() >= settings.num("person_threshold") - 0.2 ? b : null;
	}
}
