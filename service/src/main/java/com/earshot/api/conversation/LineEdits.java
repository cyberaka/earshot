package com.earshot.api.conversation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.llm.CloudTranscriber;
import com.earshot.api.ml.MlClient;
import com.earshot.api.settings.Vocabulary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The user corrects a transcript (owner request R-4, 2026-10-25): edits a paragraph's text, or asks
 * for it to be heard again by another model and keeps the proposal. A paragraph is the run of lines
 * the page shows as one; editing it makes it one line, keeping what Earshot first wrote.
 */
@Service
public class LineEdits {

	private static final Logger log = LoggerFactory.getLogger(LineEdits.class);
	// Audio either side of the paragraph, so the second opinion hears whole words; only words inside are kept.
	private static final double CONTEXT_S = 1.0;
	private static final double EDGE_S = 0.3;

	private final JdbcClient db;
	private final JsonMapper json;
	private final MlClient ml;
	private final Vocabulary vocabulary;
	private final MarkdownExporter exporter;
	private final CloudTranscriber cloud;
	private final TransactionTemplate tx;
	// Paragraphs sent to OpenAI at once when a whole conversation is transcribed there.
	private static final int CLOUD_PARALLEL = 6;

	public LineEdits(JdbcClient db, JsonMapper json, MlClient ml, Vocabulary vocabulary, MarkdownExporter exporter, CloudTranscriber cloud,
			PlatformTransactionManager transactions) {
		this.tx = new TransactionTemplate(transactions);
		this.db = db;
		this.json = json;
		this.ml = ml;
		this.vocabulary = vocabulary;
		this.exporter = exporter;
		this.cloud = cloud;
	}

	record Line(long id, long conversationId, Long clusterId, double start, double end, String text, String original, String words) {
	}

	/** The paragraph's lines in order, all of one conversation and one speaker, and not being rewritten right now. */
	private List<Line> paragraph(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new IllegalArgumentException("No lines given.");
		}
		var lines = db.sql("""
				SELECT id, conversation_id, speaker_cluster_id, start_s, end_s, text, original_text, words_json::text
				FROM segment WHERE id IN (:ids) ORDER BY start_s, id""").param("ids", ids)
				.query((rs, i) -> new Line(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getDouble(4), rs.getDouble(5), rs.getString(6),
						rs.getString(7), rs.getString(8)))
				.list();
		if (lines.size() != ids.stream().distinct().count()) {
			throw new IllegalArgumentException("Some of these lines no longer exist: reload the page.");
		}
		long conv = lines.getFirst().conversationId();
		Long cluster = lines.getFirst().clusterId();
		if (lines.stream().anyMatch(l -> l.conversationId() != conv || !java.util.Objects.equals(l.clusterId(), cluster))) {
			throw new IllegalArgumentException("A paragraph is one speaker's lines in one conversation.");
		}
		notBusy(conv);
		return lines;
	}

	private void notBusy(long conversationId) {
		boolean busy = db.sql("""
				SELECT c.stage IN ('live', 'retranscribing')
				       OR EXISTS (SELECT 1 FROM job j WHERE j.conversation_id = c.id AND j.status IN ('queued', 'running'))
				FROM conversation c WHERE c.id = :c""").param("c", conversationId).query(Boolean.class).single();
		if (busy) {
			throw new IllegalStateException("This conversation is still being transcribed: correct it when it is done.");
		}
	}

	/**
	 * Replaces the paragraph with one line holding {@code text}. {@code by}: "user", or the model whose
	 * proposal the user kept. Returns the line's id.
	 */
	@Transactional
	public long edit(List<Long> ids, String text, String by) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("The text is empty. To hide a line, delete the conversation's audio instead.");
		}
		var lines = paragraph(ids);
		long id = write(lines, text, by);
		exportDay(lines.getFirst().conversationId());
		return id;
	}

	/** The paragraph becomes one line holding {@code text}; Earshot's own text is kept on it. */
	private long write(List<Line> lines, String text, String by) {
		var first = lines.getFirst();
		String now = String.join(" ", lines.stream().map(Line::text).toList());
		if (lines.size() == 1 && now.equals(text.strip())) {
			return first.id();
		}
		// What Earshot first wrote, kept once: a line edited twice still remembers the transcription.
		String original = String.join(" ", lines.stream().map(l -> l.original() != null ? l.original() : l.text()).toList());
		ArrayNode words = json.createArrayNode();
		for (Line l : lines) {
			if (l.words() != null) {
				json.readTree(l.words()).forEach(words::add);
			}
		}
		db.sql("""
				UPDATE segment SET text = :t, original_text = :o, edited_by = :b, end_s = :e, words_json = CAST(:w AS jsonb)
				WHERE id = :id""").param("t", text.strip()).param("o", original).param("b", by == null || by.isBlank() ? "user" : by)
				.param("e", lines.getLast().end()).param("w", words.toString()).param("id", first.id()).update();
		var rest = lines.stream().skip(1).map(Line::id).toList();
		if (!rest.isEmpty()) {
			db.sql("DELETE FROM segment WHERE id IN (:ids)").param("ids", rest).update();
		}
		// Its meaning changed: search by meaning embeds it again when the user next prepares new lines.
		db.sql("DELETE FROM segment_vec WHERE segment_id = :id").param("id", first.id()).update();
		log.info("event=line_edited segment={} merged={} by={}", first.id(), rest.size(), by);
		return first.id();
	}

	/** Puts back what Earshot first wrote (the merged lines stay one line). */
	@Transactional
	public void revert(long id) {
		var line = paragraph(List.of(id)).getFirst();
		if (line.original() == null) {
			return;
		}
		db.sql("UPDATE segment SET text = original_text, original_text = NULL, edited_by = NULL WHERE id = :id").param("id", id).update();
		db.sql("DELETE FROM segment_vec WHERE segment_id = :id").param("id", id).update();
		log.info("event=line_reverted segment={}", id);
		exportDay(line.conversationId());
	}

	public record Proposal(String text, String engine, String model, double audioS) {
	}

	/** What another model hears in the paragraph: shown beside the text, stored only if the user keeps it. */
	public Proposal retranscribe(List<Long> ids, String engine) throws IOException, InterruptedException {
		var lines = paragraph(ids);
		double start = lines.getFirst().start(), end = lines.getLast().end();
		var src = db.sql("""
				SELECT r.work_path, r.language, c.meeting_id FROM conversation c JOIN recording r ON r.id = c.recording_id WHERE c.id = :c""")
				.param("c", lines.getFirst().conversationId())
				.query((rs, i) -> new Object[] { rs.getString(1), rs.getString(2), (Long) rs.getObject(3) }).single();
		String work = (String) src[0], language = (String) src[1];
		if (work == null || !java.nio.file.Files.exists(java.nio.file.Path.of(work))) {
			throw new IllegalStateException("The audio for this conversation is no longer on disk.");
		}
		var terms = vocabulary.forMeeting((Long) src[2]);
		String text;
		String model;
		if ("cloud".equals(engine)) {
			text = cloud.transcribe(wav(work, start, end), language, terms.isEmpty() ? null : "Names and terms: " + String.join(", ", terms) + ".");
			model = "openai:" + cloud.model();
		} else {
			JsonNode res = ml.transcribeLine(work, Math.max(0, start - CONTEXT_S), end + CONTEXT_S, language, terms, true);
			var kept = new ArrayList<String>();
			for (JsonNode s : res.path("segments")) {
				for (JsonNode w : s.path("words")) {
					double mid = (w.path("s").asDouble() + w.path("e").asDouble()) / 2;
					if (mid >= start - EDGE_S && mid <= end + EDGE_S) {
						kept.add(w.path("w").asString());
					}
				}
			}
			text = join(kept);
			model = "local:whisper-large-v3";
		}
		log.info("event=line_retranscribed segments={} engine={} audio_s={}", ids.size(), model, Math.round(end - start));
		return new Proposal(vocabulary.correct(text), engine, model, end - start);
	}

	/** The conversation's lines as the page shows them: one speaker's run, pauses under 3 s, at most 3 minutes (web groupTurns). */
	static List<List<Line>> paragraphs(List<Line> lines) {
		var out = new ArrayList<List<Line>>();
		for (Line l : lines) {
			var g = out.isEmpty() ? null : out.getLast();
			if (g != null && java.util.Objects.equals(g.getLast().clusterId(), l.clusterId()) && l.start() - g.getLast().end() <= 3
					&& l.end() - g.getFirst().start() <= 180) {
				g.add(l);
			} else {
				out.add(new ArrayList<>(List.of(l)));
			}
		}
		return out;
	}

	private List<Line> linesOf(long conversationId) {
		return db.sql("""
				SELECT id, conversation_id, speaker_cluster_id, start_s, end_s, text, original_text, words_json::text
				FROM segment WHERE conversation_id = :c AND NOT live ORDER BY start_s, id""").param("c", conversationId)
				.query((rs, i) -> new Line(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getDouble(4), rs.getDouble(5), rs.getString(6),
						rs.getString(7), rs.getString(8)))
				.list();
	}

	public record CloudPlan(int paragraphs, double audioS, double usd, boolean available, String model) {
	}

	/** What "Transcribe with OpenAI" would send, shown before the click: only the paragraphs' speech, not the silence between. */
	public CloudPlan cloudPlan(long conversationId) {
		var paras = paragraphs(linesOf(conversationId));
		double audio = paras.stream().mapToDouble(g -> g.getLast().end() - g.getFirst().start()).sum();
		return new CloudPlan(paras.size(), audio, audio / 60 * CloudTranscriber.USD_PER_MINUTE, cloud.available(), cloud.model());
	}

	public record CloudResult(int paragraphs, int changed, int unchanged, int failed, double audioS) {
	}

	/**
	 * The whole conversation through OpenAI (owner request, 2026-10-25): each speaker's paragraph is sent
	 * on its own, so its text lands back on the speaker found on this Mac. Applied at once, Earshot's
	 * text kept on every line ({@link #revertCloud} puts it back); a paragraph OpenAI heard nothing in
	 * keeps Earshot's text.
	 */
	public CloudResult transcribeConversation(long conversationId) throws InterruptedException {
		if (!cloud.available()) {
			throw new IllegalStateException("Set OPENAI_API_KEY in .env and restart Earshot to use OpenAI.");
		}
		notBusy(conversationId);
		var src = db.sql("""
				SELECT r.work_path, r.language, c.meeting_id, c.local_date FROM conversation c JOIN recording r ON r.id = c.recording_id WHERE c.id = :c""")
				.param("c", conversationId)
				.query((rs, i) -> new Object[] { rs.getString(1), rs.getString(2), (Long) rs.getObject(3), rs.getObject(4, java.time.LocalDate.class) })
				.single();
		String work = (String) src[0], language = (String) src[1];
		if (work == null || !java.nio.file.Files.exists(java.nio.file.Path.of(work))) {
			throw new IllegalStateException("The audio for this conversation is no longer on disk.");
		}
		var terms = vocabulary.forMeeting((Long) src[2]);
		String prompt = terms.isEmpty() ? null : "Names and terms: " + String.join(", ", terms) + ".";
		var paras = paragraphs(linesOf(conversationId));
		var heard = new String[paras.size()];
		var gate = new java.util.concurrent.Semaphore(CLOUD_PARALLEL);
		long t0 = System.nanoTime();
		try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < paras.size(); i++) {
				int k = i;
				exec.submit(() -> {
					gate.acquireUninterruptibly();
					try {
						var g = paras.get(k);
						heard[k] = vocabulary.correct(cloud.transcribe(wav(work, g.getFirst().start(), g.getLast().end()), language, prompt));
					} catch (Exception e) {
						log.warn("event=cloud_paragraph_failed conversation={} paragraph={} reason={}", conversationId, k, e.getMessage());
					} finally {
						gate.release();
					}
				});
			}
		}
		int changed = 0, unchanged = 0, failed = 0;
		double audio = 0;
		var writes = new ArrayList<Integer>();
		for (int i = 0; i < paras.size(); i++) {
			var g = paras.get(i);
			audio += g.getLast().end() - g.getFirst().start();
			if (heard[i] == null) {
				failed++;
			} else if (heard[i].isBlank() || heard[i].equals(String.join(" ", g.stream().map(Line::text).toList()))) {
				unchanged++;
			} else {
				writes.add(i);
			}
		}
		String by = "openai:" + cloud.model();
		tx.executeWithoutResult(st -> writes.forEach(i -> write(paras.get(i), heard[i], by)));
		changed = writes.size();
		db.sql("DELETE FROM segment_vec WHERE segment_id IN (SELECT id FROM segment WHERE conversation_id = :c)").param("c", conversationId).update();
		exporter.exportDay((java.time.LocalDate) src[3]);
		log.info("event=conversation_cloud_transcribed conversation={} paragraphs={} changed={} unchanged={} failed={} audio_s={} took_s={}",
				conversationId, paras.size(), changed, unchanged, failed, Math.round(audio), (System.nanoTime() - t0) / 1_000_000_000);
		return new CloudResult(paras.size(), changed, unchanged, failed, audio);
	}

	/** Puts back Earshot's text on every line OpenAI wrote in this conversation; the user's own edits stay. */
	@Transactional
	public int revertCloud(long conversationId) {
		notBusy(conversationId);
		int n = db.sql("""
				UPDATE segment SET text = original_text, original_text = NULL, edited_by = NULL
				WHERE conversation_id = :c AND edited_by LIKE 'openai:%' AND original_text IS NOT NULL""").param("c", conversationId).update();
		db.sql("DELETE FROM segment_vec WHERE segment_id IN (SELECT id FROM segment WHERE conversation_id = :c)").param("c", conversationId).update();
		exportDay(conversationId);
		log.info("event=conversation_cloud_reverted conversation={} lines={}", conversationId, n);
		return n;
	}

	/** Words to text: no space before punctuation that ends or continues a word. */
	static String join(List<String> words) {
		var out = new StringBuilder();
		for (String w : words) {
			if (!out.isEmpty() && !w.matches("^[,.!?;:'%].*")) {
				out.append(' ');
			}
			out.append(w);
		}
		return out.toString().strip();
	}

	public Map<String, Object> options() {
		var out = new LinkedHashMap<String, Object>();
		out.put("local", Map.of("model", "Whisper large-v3", "note", "Slower than the everyday model: a second opinion, on this Mac."));
		out.put("cloud", Map.of("available", cloud.available(), "model", cloud.model(), "usdPerMinute", CloudTranscriber.USD_PER_MINUTE));
		return out;
	}

	/** Applies a remembered correction to every line that has it; returns how many lines changed. */
	@Transactional
	public int applyCorrection(long correctionId) {
		var c = vocabulary.corrections().stream().filter(x -> x.id() == correctionId).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No such correction."));
		var pattern = Vocabulary.pattern(c.wrong());
		var hits = db.sql("""
				SELECT s.id, s.text, c.local_date FROM segment s JOIN conversation c ON c.id = s.conversation_id
				WHERE strpos(lower(s.text), lower(:w)) > 0 AND c.stage NOT IN ('live', 'retranscribing')""").param("w", c.wrong())
				.query((rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getObject(3, java.time.LocalDate.class) }).list();
		var days = new java.util.TreeSet<java.time.LocalDate>();
		int changed = 0;
		for (Object[] h : hits) {
			String text = (String) h[1];
			String fixed = pattern.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(c.fixed()));
			if (!fixed.equals(text)) {
				db.sql("""
						UPDATE segment SET text = :t, original_text = COALESCE(original_text, text), edited_by = COALESCE(edited_by, 'correction')
						WHERE id = :id""").param("t", fixed).param("id", h[0]).update();
				db.sql("DELETE FROM segment_vec WHERE segment_id = :id").param("id", h[0]).update();
				days.add((java.time.LocalDate) h[2]);
				changed++;
			}
		}
		days.forEach(exporter::exportDay);
		log.info("event=correction_applied correction={} lines={}", correctionId, changed);
		return changed;
	}

	private void exportDay(long conversationId) {
		db.sql("SELECT local_date FROM conversation WHERE id = :c").param("c", conversationId).query(java.time.LocalDate.class).optional()
				.ifPresent(exporter::exportDay);
	}

	/** The span as 16 kHz mono WAV, as the click-to-play audio is cut. */
	private static byte[] wav(String work, double start, double end) throws IOException, InterruptedException {
		var p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-ss", String.valueOf(start), "-to", String.valueOf(end), "-i", work,
				"-ac", "1", "-ar", "16000", "-f", "wav", "-").start();
		byte[] out = p.getInputStream().readAllBytes();
		if (p.waitFor() != 0 || out.length == 0) {
			throw new IOException("ffmpeg could not cut the line's audio.");
		}
		return out;
	}
}
