package com.earshot.api.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Names and terms Whisper should spell right (owner request R-3, 2026-10-24): the global list in
 * Settings plus a meeting's own. Passed to Whisper as its initial prompt. On the ISB class it fixed
 * "Parath" → Bharat, "Sejal" → Sajal, "Zometo" → Zomato, "Captiv9" → Capgemini, with no more
 * invented lines over silence.
 *
 * <p>Also the user's corrections (owner request R-4, 2026-10-25): "X is always Y", applied to every
 * new transcript, and their right spelling added to the terms Whisper is given.
 */
@Service
public class Vocabulary {

	private final Settings settings;
	private final JdbcClient db;

	public Vocabulary(Settings settings, JdbcClient db) {
		this.settings = settings;
		this.db = db;
	}

	/** "Abhinav, Hardik\nRapido; Zomato" -> [Abhinav, Hardik, Rapido, Zomato]. */
	public static List<String> split(String text) {
		var out = new LinkedHashSet<String>();
		if (text != null) {
			for (String t : text.split("[,;\\n]")) {
				if (!t.isBlank()) {
					out.add(t.strip());
				}
			}
		}
		return new ArrayList<>(out);
	}

	public List<String> global() {
		var out = new LinkedHashSet<>(split(settings.str("vocabulary")));
		corrections().forEach(c -> out.add(c.fixed()));
		return new ArrayList<>(out);
	}

	public record Correction(long id, String wrong, String fixed) {
	}

	private volatile List<Correction> cache;

	public List<Correction> corrections() {
		var c = cache;
		if (c == null) {
			c = db.sql("SELECT id, wrong, fixed FROM correction ORDER BY length(wrong) DESC, id")
					.query((rs, i) -> new Correction(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
			cache = c;
		}
		return c;
	}

	/** Remembers "wrong is always fixed"; the same wrong text again replaces the earlier fix. */
	public Correction addCorrection(String wrong, String fixed) {
		if (wrong == null || wrong.isBlank() || fixed == null || fixed.isBlank()) {
			throw new IllegalArgumentException("Give the wrong text and its correction.");
		}
		if (wrong.strip().equalsIgnoreCase(fixed.strip())) {
			throw new IllegalArgumentException("The correction is the same as the text.");
		}
		long id = db.sql("""
				INSERT INTO correction (wrong, fixed) VALUES (:w, :f)
				ON CONFLICT (lower(wrong)) DO UPDATE SET fixed = EXCLUDED.fixed RETURNING id""").param("w", wrong.strip()).param("f", fixed.strip())
				.query(Long.class).single();
		cache = null;
		return new Correction(id, wrong.strip(), fixed.strip());
	}

	public void deleteCorrection(long id) {
		db.sql("DELETE FROM correction WHERE id = :id").param("id", id).update();
		cache = null;
	}

	/** The text with every remembered correction applied (whole words and phrases, any case). */
	public String correct(String text) {
		return correct(text, corrections());
	}

	public static String correct(String text, List<Correction> corrections) {
		if (text == null || corrections.isEmpty()) {
			return text;
		}
		String out = text;
		for (Correction c : corrections) {
			out = pattern(c.wrong()).matcher(out).replaceAll(Matcher.quoteReplacement(c.fixed()));
		}
		return out;
	}

	private static final Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Whole words only: "Taaf" must not change "Taaffe". Letters and digits of any script count as word characters. */
	public static Pattern pattern(String wrong) {
		return PATTERNS.computeIfAbsent(wrong, w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
	}

	/** The global list, then the meeting's own terms. */
	public List<String> forMeeting(Long meetingId) {
		var out = new LinkedHashSet<>(global());
		if (meetingId != null) {
			out.addAll(split(db.sql("SELECT vocabulary FROM meeting WHERE id = :id").param("id", meetingId).query(String.class).optional().orElse(null)));
		}
		return new ArrayList<>(out);
	}
}
