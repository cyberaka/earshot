package com.earshot.api.search;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.llm.Insights;
import com.earshot.api.llm.Insights.Feature;
import com.earshot.api.speaker.Vectors;

/**
 * Embeds segments for semantic search (PRD §5.2 step 11), when that optional feature is on.
 * Keyword search needs none of this: full text is a generated column.
 */
@Service
public class Indexer {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Indexer.class);
	private static final int BATCH = 32;
	private final JdbcClient db;
	private final Insights insights;

	public Indexer(JdbcClient db, Insights insights) {
		this.db = db;
		this.insights = insights;
	}

	/** nomic-embed expects task prefixes; other models ignore them harmlessly. */
	static String document(String text) {
		return "search_document: " + text;
	}

	static String query(String text) {
		return "search_query: " + text;
	}

	/**
	 * Embeds what isn't yet; does nothing while semantic search is off, or local during a live
	 * meeting. A provider failure is logged and skipped: keyword search works without vectors.
	 */
	public int indexConversation(long conversationId) {
		try {
			return embedMissing(conversationId);
		} catch (com.earshot.api.llm.LlmClient.LlmUnavailable e) {
			log.warn("event=index_skipped conversation={} reason={}", conversationId, e.getMessage());
			return 0;
		}
	}

	private int embedMissing(long conversationId) {
		if (!insights.usableNow(Feature.SEMANTIC_SEARCH)) {
			return 0;
		}
		String model = insights.model(Feature.SEMANTIC_SEARCH);
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
		float[][] vecs = insights.client(Feature.SEMANTIC_SEARCH).embed(model, texts);
		for (int i = 0; i < segmentIds.size(); i++) {
			db.sql("""
					INSERT INTO segment_vec (segment_id, model, embedding) VALUES (:s, :m, CAST(:v AS vector))
					ON CONFLICT (segment_id, model) DO UPDATE SET embedding = EXCLUDED.embedding""")
					.param("s", segmentIds.get(i)).param("m", model).param("v", Vectors.literal(vecs[i])).update();
		}
	}

	public boolean semanticReady() {
		return insights.usableNow(Feature.SEMANTIC_SEARCH);
	}

	public String model() {
		return insights.model(Feature.SEMANTIC_SEARCH);
	}

	public float[] embedQuery(String text) {
		return insights.client(Feature.SEMANTIC_SEARCH).embed(model(), List.of(query(text)))[0];
	}
}
