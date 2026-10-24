package com.earshot.api.search;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.llm.Insights;
import com.earshot.api.llm.Insights.Feature;
import com.earshot.api.speaker.Vectors;

/**
 * Embeds segments for semantic search (PRD §5.2 step 11), only when the user clicks "Prepare search
 * by meaning" (owner rule, 2026-10-24). Keyword search needs none of this: full text is a generated column.
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

	/** Lines that have no vector for the chosen model yet, and how many characters they hold. */
	public Map<String, Object> status() {
		var out = new java.util.LinkedHashMap<String, Object>();
		out.put("provider", insights.provider(Feature.SEMANTIC_SEARCH).id());
		if (!insights.enabled(Feature.SEMANTIC_SEARCH)) {
			out.put("missing", 0);
			return out;
		}
		String model = model();
		var row = db.sql("""
				SELECT count(*), COALESCE(sum(length(s.text)), 0) FROM segment s
				WHERE NOT EXISTS (SELECT 1 FROM segment_vec v WHERE v.segment_id = s.id AND v.model = :m)""").param("m", model)
				.query((rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) }).single();
		out.put("model", model);
		out.put("missing", row[0]);
		out.put("tokens", Math.round(row[1] / 4.0));
		out.put("embedded", db.sql("SELECT count(*) FROM segment_vec WHERE model = :m").param("m", model).query(Long.class).single());
		return out;
	}

	/** Every line not yet embedded, in batches; throws when off, or local during a live meeting. */
	public int indexAll() {
		insights.client(Feature.SEMANTIC_SEARCH);
		String model = model();
		int done = 0;
		while (true) {
			var rows = db.sql("""
					SELECT s.id, s.text FROM segment s
					WHERE NOT EXISTS (SELECT 1 FROM segment_vec v WHERE v.segment_id = s.id AND v.model = :m)
					ORDER BY s.id LIMIT :n""").param("m", model).param("n", BATCH)
					.query((rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }).list();
			if (rows.isEmpty()) {
				log.info("event=index_done embedded={} model={}", done, model);
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

	public String model() {
		return insights.model(Feature.SEMANTIC_SEARCH);
	}

	public float[] embedQuery(String text) {
		return insights.client(Feature.SEMANTIC_SEARCH).embed(model(), List.of(query(text)))[0];
	}
}
