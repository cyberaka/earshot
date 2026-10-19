package com.earshot.api.speaker;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Current display names for clusters (PRD §5.5). Derived text stores {{spk:<cluster id>}} and
 * is rendered here, so renaming, merging or deleting a person changes every view at once.
 */
@Service
public class SpeakerNames {

	private static final Pattern TOKEN = Pattern.compile("\\{\\{spk:(\\d+)}}");
	private final JdbcClient db;

	public SpeakerNames(JdbcClient db) {
		this.db = db;
	}

	public record Speaker(long clusterId, String name, Long personId, boolean isMe, String channel) {
	}

	/** Cluster id -> speaker for one conversation. Unnamed clusters are "Speaker N" by first appearance. */
	public Map<Long, Speaker> forConversation(long conversationId) {
		var rows = db.sql("""
				SELECT sc.id, sc.channel, p.id AS person_id, p.name, COALESCE(p.is_me, false) AS is_me,
				       (SELECT min(s.start_s) FROM segment s WHERE s.speaker_cluster_id = sc.id) AS first_s
				FROM speaker_cluster sc LEFT JOIN person p ON p.id = sc.person_id
				WHERE sc.conversation_id = :c ORDER BY first_s NULLS LAST, sc.id""").param("c", conversationId).query().listOfRows();
		Map<Long, Speaker> out = new HashMap<>();
		int n = 0;
		for (var r : rows) {
			long id = ((Number) r.get("id")).longValue();
			boolean me = (boolean) r.get("is_me");
			String name;
			if (me) {
				name = "Me";
			} else if (r.get("name") != null) {
				name = (String) r.get("name");
			} else {
				name = "Speaker " + (++n);
			}
			out.put(id, new Speaker(id, name, r.get("person_id") == null ? null : ((Number) r.get("person_id")).longValue(), me,
					(String) r.get("channel")));
		}
		return out;
	}

	/** Live segments carry no cluster: the channel says who spoke (PRD §6.2). */
	public static String channelName(String channel, String audioMode) {
		return switch (channel) {
			case "mic" -> "mic".equals(audioMode) ? "Room" : "Me";
			case "system" -> "Others";
			default -> "Unknown";
		};
	}

	public String render(String text, Map<Long, Speaker> speakers) {
		if (text == null) {
			return null;
		}
		var m = TOKEN.matcher(text);
		var sb = new StringBuilder();
		while (m.find()) {
			var s = speakers.get(Long.parseLong(m.group(1)));
			m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(s == null ? "Unknown" : s.name()));
		}
		return m.appendTail(sb).toString();
	}
}
