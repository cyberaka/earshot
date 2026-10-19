package com.earshot.api.search;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.llm.LlmClient;
import com.earshot.api.settings.Settings;
import com.earshot.api.speaker.Vectors;

/** Embeds segments for semantic search (PRD §5.2 step 11). Full text is a generated column. */
@Service
public class Indexer {

	private static final int BATCH = 32;
	private final JdbcClient db;
	private final LlmClient llm;
	private final Settings settings;

	public Indexer(JdbcClient db, LlmClient llm, Settings settings) {
		this.db = db;
		this.llm = llm;
		this.settings = settings;
	}

	/** nomic-embed expects task prefixes; other models ignore them harmlessly. */
	static String document(String text) {
		return "search_document: " + text;
	}

	static String query(String text) {
		return "search_query: " + text;
	}

	public int indexConversation(long conversationId) {
		String model = settings.str("embedding_model");
		int done = 0;
		while (true) {
			var rows = db.sql("""
					SELECT s.id, s.text FROM segment s
					WHERE s.conversation_id = :c AND NOT EXISTS (SELECT 1 FROM segment_vec v WHERE v.segment_id = s.id AND v.model = :m)
					ORDER BY s.id LIMIT :n""").param("c", conversationId).param("m", model).param("n", BATCH)
					.query((rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }).list();
			if (rows.isEmpty()) {
				return done;
			}
			index(rows.stream().map(r -> (Long) r[0]).toList(), rows.stream().map(r -> document((String) r[1])).toList(), model);
			done += rows.size();
		}
	}

	public void index(List<Long> segmentIds, List<String> texts, String model) {
		float[][] vecs = llm.embed(model, texts);
		for (int i = 0; i < segmentIds.size(); i++) {
			db.sql("""
					INSERT INTO segment_vec (segment_id, model, embedding) VALUES (:s, :m, CAST(:v AS vector))
					ON CONFLICT (segment_id, model) DO UPDATE SET embedding = EXCLUDED.embedding""")
					.param("s", segmentIds.get(i)).param("m", model).param("v", Vectors.literal(vecs[i])).update();
		}
	}

	public float[] embedQuery(String text) {
		return llm.embed(settings.str("embedding_model"), List.of(query(text)))[0];
	}
}
