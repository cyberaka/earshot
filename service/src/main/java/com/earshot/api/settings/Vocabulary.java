package com.earshot.api.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Names and terms Whisper should spell right (owner request R-3, 2026-10-24): the global list in
 * Settings plus a meeting's own. Passed to Whisper as its initial prompt. On the ISB class it fixed
 * "Parath" → Bharat, "Sejal" → Sajal, "Zometo" → Zomato, "Captiv9" → Capgemini, with no more
 * invented lines over silence.
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
		return split(settings.str("vocabulary"));
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
