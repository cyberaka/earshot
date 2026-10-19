package com.earshot.api.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.earshot.api.llm.LlmClient;
import com.earshot.api.llm.LlmClient.Message;
import com.earshot.api.settings.Settings;
import com.earshot.api.speaker.SpeakerNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Title, summary, action items and topics per conversation (PRD §5.2 step 10). The model sees
 * speakers as S1, S2, … and its output is stored with {{spk:id}} tokens, never names (§5.5).
 */
@Service
public class Enricher {

	private static final Pattern S_LABEL = Pattern.compile("\\bS(\\d+)\\b");
	// Keeps a prompt inside a small model's context; longer conversations are summarised in parts.
	private static final int CHUNK_CHARS = 9000;
	private final JdbcClient db;
	private final LlmClient llm;
	private final Settings settings;
	private final SpeakerNames names;
	private final JsonMapper json;

	public Enricher(JdbcClient db, LlmClient llm, Settings settings, SpeakerNames names, JsonMapper json) {
		this.db = db;
		this.llm = llm;
		this.settings = settings;
		this.names = names;
		this.json = json;
	}

	record Line(String label, String text) {
	}

	@Transactional
	public void enrich(long conversationId) {
		var speakers = names.forConversation(conversationId);
		var segs = db.sql("SELECT speaker_cluster_id, channel, text FROM segment WHERE conversation_id = :c ORDER BY start_s")
				.param("c", conversationId).query((rs, i) -> new Object[] { rs.getObject(1), rs.getString(2), rs.getString(3) }).list();
		if (segs.isEmpty()) {
			return;
		}
		// S-labels by first appearance; the user is always called out.
		Map<Long, Integer> sOf = new LinkedHashMap<>();
		var lines = new ArrayList<Line>();
		for (Object[] s : segs) {
			Long cid = s[0] == null ? null : ((Number) s[0]).longValue();
			String label;
			if (cid == null) {
				label = "mic".equals(s[1]) ? "Me" : "Others";
			} else {
				int n = sOf.computeIfAbsent(cid, k -> sOf.size() + 1);
				label = "S" + n;
			}
			lines.add(new Line(label, (String) s[2]));
		}
		var legend = new StringBuilder();
		sOf.forEach((cid, n) -> {
			var sp = speakers.get(cid);
			legend.append("S").append(n).append(sp != null && sp.isMe() ? " = the user (write \"S" + n + "\" when referring to them)" : "")
					.append('\n');
		});
		String transcript = render(lines);
		if (transcript.length() > CHUNK_CHARS) {
			transcript = condense(lines, legend.toString());
		}
		String prompt = """
				Speakers:
				%s
				Transcript:
				%s

				Return JSON only, in this shape:
				{"title": "short title, may name speakers as S1, S2", "summary": ["3 to 5 short bullets"],
				 "action_items": [{"text": "what was promised", "owner": "S1 or null"}], "topics": ["1 to 4 topic tags"]}
				Refer to speakers only as S1, S2, ... Use only facts in the transcript. If there are no action items, return [].
				""".formatted(legend, transcript);
		String reply = llm.chat(settings.str("llm_batch_model"), List.of(
				Message.system("You write concise meeting notes as JSON. Never invent facts."), Message.user(prompt)), true);
		JsonNode out = llm.parseJson(reply);
		Map<Integer, Long> clusterOf = new LinkedHashMap<>();
		sOf.forEach((cid, n) -> clusterOf.put(n, cid));
		var bullets = new ArrayList<String>();
		out.path("summary").forEach(b -> bullets.add(tokens(b.asString(), clusterOf)));
		var topics = new ArrayList<String>();
		out.path("topics").forEach(t -> topics.add(t.asString()));
		db.sql("UPDATE conversation SET title = :t, summary = CAST(:s AS jsonb), topics = CAST(:tp AS jsonb) WHERE id = :id")
				.param("t", tokens(out.path("title").asString("Conversation"), clusterOf))
				.param("s", json.writeValueAsString(Map.of("bullets", bullets)))
				.param("tp", json.writeValueAsString(topics)).param("id", conversationId).update();
		db.sql("DELETE FROM action_item WHERE conversation_id = :c").param("c", conversationId).update();
		for (JsonNode a : out.path("action_items")) {
			String text = a.isString() ? a.asString() : a.path("text").asString("");
			if (text.isBlank()) {
				continue;
			}
			Long owner = null;
			var m = S_LABEL.matcher(a.path("owner").asString(""));
			if (m.find()) {
				owner = clusterOf.get(Integer.parseInt(m.group(1)));
			}
			db.sql("INSERT INTO action_item (conversation_id, text, owner_cluster_id) VALUES (:c, :t, :o)").param("c", conversationId)
					.param("t", tokens(text, clusterOf)).param("o", owner).update();
		}
	}

	private static String render(List<Line> lines) {
		var sb = new StringBuilder();
		lines.forEach(l -> sb.append(l.label()).append(": ").append(l.text()).append('\n'));
		return sb.toString();
	}

	/** Map step for long conversations: notes per chunk, then the final prompt reads the notes. */
	private String condense(List<Line> lines, String legend) {
		var notes = new StringBuilder();
		var chunk = new ArrayList<Line>();
		int size = 0;
		for (Line l : lines) {
			chunk.add(l);
			size += l.text().length() + 6;
			if (size >= CHUNK_CHARS) {
				notes.append(noteChunk(chunk, legend)).append('\n');
				chunk.clear();
				size = 0;
			}
		}
		if (!chunk.isEmpty()) {
			notes.append(noteChunk(chunk, legend));
		}
		return "(condensed notes of a long conversation, in order)\n" + notes;
	}

	private String noteChunk(List<Line> chunk, String legend) {
		return llm.chat(settings.str("llm_batch_model"), List.of(Message.system("You take terse factual notes."),
				Message.user("Speakers:\n" + legend + "\nWrite up to 8 terse bullet notes (decisions, promises, facts) of this part. "
						+ "Keep speaker labels S1, S2.\n\n" + render(chunk))), false);
	}

	static String tokens(String text, Map<Integer, Long> clusterOf) {
		var m = S_LABEL.matcher(text);
		var sb = new StringBuilder();
		while (m.find()) {
			Long cid = clusterOf.get(Integer.parseInt(m.group(1)));
			m.appendReplacement(sb, cid == null ? m.group() : "{{spk:" + cid + "}}");
		}
		return m.appendTail(sb).toString();
	}
}
