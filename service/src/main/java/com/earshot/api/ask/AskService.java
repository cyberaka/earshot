package com.earshot.api.ask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.conversation.ConversationService;
import com.earshot.api.llm.LlmClient;
import com.earshot.api.llm.LlmClient.Message;
import com.earshot.api.search.SearchService;
import com.earshot.api.settings.Settings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grounded Q&A over a meeting, a day or a date range (PRD §6.5, A-9). Evidence is numbered;
 * the model cites [n]; citations resolve to recording offsets, which survive the post-meeting
 * pass replacing segments (§9.2).
 */
@Service
public class AskService {

	private static final Logger log = LoggerFactory.getLogger(AskService.class);
	private final JdbcClient db;
	private final SearchService search;
	private final ConversationService conversations;
	private final LlmClient llm;
	private final Settings settings;
	private final JsonMapper json;

	public AskService(JdbcClient db, SearchService search, ConversationService conversations, LlmClient llm, Settings settings,
			JsonMapper json) {
		this.db = db;
		this.search = search;
		this.conversations = conversations;
		this.llm = llm;
		this.settings = settings;
		this.json = json;
	}

	public record Ask(String scopeType, String scopeRef, String question, String action) {
	}

	public record Evidence(int n, long recordingId, long conversationId, double startS, double endS, String clock, String speaker,
			String text) {
	}

	public record Citation(int n, long recordingId, long conversationId, double startS, double endS, String clock, String speaker) {
	}

	public interface Sink {
		void token(String t);

		void citations(List<Citation> c);

		void done(long qaId, String answer);
	}

	public void ask(Ask req, Sink sink) {
		var ctx = switch (req.scopeType()) {
			case "meeting" -> meetingContext(Long.parseLong(req.scopeRef()), req);
			case "day" -> rangeContext(LocalDate.parse(req.scopeRef()), LocalDate.parse(req.scopeRef()), req);
			case "range" -> {
				String[] p = req.scopeRef().split("\\.\\.");
				yield rangeContext(LocalDate.parse(p[0]), LocalDate.parse(p[1]), req);
			}
			default -> throw new IllegalArgumentException("scope " + req.scopeType());
		};
		String model = "meeting".equals(req.scopeType()) ? settings.str("llm_live_model") : settings.str("llm_batch_model");
		String answer = llm.chatStream(model, ctx.messages(), sink::token);
		var cited = new LinkedHashSet<Integer>();
		// "Not discussed" carries no evidence, whatever the model appended.
		if (!answer.strip().toLowerCase().startsWith("not discussed")) {
			var m = Pattern.compile("\\[([\\d,\\s\\-–]+)]").matcher(answer);
			while (m.find()) {
				for (String part : m.group(1).split(",")) {
					String[] range = part.trim().split("\\s*[-–]\\s*");
					try {
						int a = Integer.parseInt(range[0].trim());
						int b = range.length > 1 ? Integer.parseInt(range[1].trim()) : a;
						for (int n = a; n <= Math.min(b, a + 20); n++) {
							cited.add(n);
						}
					} catch (NumberFormatException e) {
						// not a citation
					}
				}
			}
		}
		var citations = new ArrayList<Citation>();
		for (int n : cited) {
			if (n >= 1 && n <= ctx.evidence().size()) {
				var e = ctx.evidence().get(n - 1);
				citations.add(new Citation(n, e.recordingId(), e.conversationId(), e.startS(), e.endS(), e.clock(), e.speaker()));
			}
		}
		sink.citations(citations);
		long qa = db.sql("""
				INSERT INTO qa (scope_type, scope_ref, question, answer, citations_json) VALUES (:t, :r, :q, :a, CAST(:c AS jsonb)) RETURNING id""")
				.param("t", req.scopeType()).param("r", req.scopeRef()).param("q", label(req)).param("a", answer)
				.param("c", json.writeValueAsString(citations)).query(Long.class).single();
		log.info("event=asked scope={} action={} evidence={} cited={}", req.scopeType(), req.action(), ctx.evidence().size(), citations.size());
		sink.done(qa, answer);
	}

	static String label(Ask req) {
		if (req.action() == null || req.action().isBlank()) {
			return req.question();
		}
		return switch (req.action()) {
			case "was_discussed" -> "Was " + req.question() + " discussed?";
			case "suggest" -> "Suggest a question I could ask";
			case "last5" -> "Summarize the last 5 minutes";
			case "decisions" -> "What decisions / action items so far?";
			case "open_agenda" -> "What's still open from the agenda?";
			default -> req.question();
		};
	}

	record Context(List<Message> messages, List<Evidence> evidence) {
	}

	private int budgetChars() {
		return (int) settings.num("qa_prompt_max_tokens") * 4;
	}

	private Context meetingContext(long meetingId, Ask req) {
		var meeting = db.sql("SELECT m.pre_context, m.rolling_summary::text, c.id FROM meeting m JOIN conversation c ON c.meeting_id = m.id WHERE m.id = :id")
				.param("id", meetingId).query((rs, i) -> new Object[] { rs.getString(1), rs.getString(2), rs.getLong(3) }).single();
		long convId = (long) meeting[2];
		var detail = conversations.detail(convId);
		var segs = detail.segments();
		var evidence = new ArrayList<Evidence>();
		var seen = new LinkedHashMap<Long, Integer>();
		String action = req.action() == null ? "" : req.action();
		String q = action.equals("was_discussed") ? req.question() : label(req);
		// 1. Retrieved segments (hybrid), the evidence for recall questions.
		if (!action.equals("last5") && !action.equals("suggest")) {
			for (var h : search.search(q, new SearchService.Scope(null, null, List.of(convId)), 10)) {
				seen.computeIfAbsent(h.segmentId(), k -> {
					evidence.add(new Evidence(evidence.size() + 1, h.recordingId(), h.conversationId(), h.startS(), h.endS(), h.clock(),
							h.speaker(), h.text()));
					return evidence.size();
				});
			}
		}
		// 2. Recent verbatim transcript (~2 min; 5 min for the last-5 quick action).
		double end = segs.isEmpty() ? 0 : segs.getLast().endS();
		double window = action.equals("last5") ? 300 : 120;
		for (var s : segs) {
			if (s.endS() >= end - window && !seen.containsKey(s.id())) {
				seen.put(s.id(), evidence.size() + 1);
				evidence.add(new Evidence(evidence.size() + 1, detail.recordingId(), convId, s.startS(), s.endS(), s.clock(), s.speaker(), s.text()));
			}
		}
		String summary = meeting[1] == null ? "" : summaryText(json.readTree((String) meeting[1]));
		String pre = meeting[0] == null ? "" : (String) meeting[0];
		return build(req, evidence, summary, pre, "meeting", detail.title());
	}

	private Context rangeContext(LocalDate from, LocalDate to, Ask req) {
		var evidence = new ArrayList<Evidence>();
		var convIds = new LinkedHashSet<Long>();
		for (var h : search.search(req.question(), new SearchService.Scope(from, to, null), 12)) {
			evidence.add(new Evidence(evidence.size() + 1, h.recordingId(), h.conversationId(), h.startS(), h.endS(),
					h.date() + " " + h.clock(), h.speaker(), h.text()));
			convIds.add(h.conversationId());
		}
		var sb = new StringBuilder();
		for (long id : convIds) {
			var d = conversations.detail(id);
			sb.append("- ").append(d.date()).append(' ').append(d.startClock()).append(" \"").append(d.title()).append("\": ")
					.append(String.join(" ", d.summary())).append('\n');
		}
		var titles = new StringBuilder();
		for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
			for (var c : conversations.day(day).conversations()) {
				if (!c.ambient() && titles.length() < 3000) {
					titles.append("- ").append(day).append(' ').append(c.startClock()).append('–').append(c.endClock()).append(' ')
							.append(c.title()).append('\n');
				}
			}
		}
		String summaries = sb.isEmpty() ? "" : "Summaries of the conversations the evidence comes from:\n" + sb;
		String scopeLabel = from.equals(to) ? "the day " + from : "the days " + from + " to " + to;
		return build(req, evidence, summaries + (titles.isEmpty() ? "" : "\nAll conversations in scope:\n" + titles), "", scopeLabel, null);
	}

	static String summaryText(JsonNode s) {
		var sb = new StringBuilder();
		s.path("summary").forEach(b -> sb.append("- ").append(b.asString()).append('\n'));
		if (s.path("decisions").size() > 0) {
			sb.append("Decisions:\n");
			s.path("decisions").forEach(d -> sb.append("- ").append(d.path("text").asString()).append('\n'));
		}
		if (s.path("action_items").size() > 0) {
			sb.append("Action items:\n");
			s.path("action_items").forEach(d -> sb.append("- ").append(d.path("text").asString())
					.append(d.path("owner").isString() ? " (" + d.path("owner").asString() + ")" : "").append('\n'));
		}
		if (s.path("agenda").size() > 0) {
			sb.append("Agenda status:\n");
			s.path("agenda").forEach(d -> sb.append("- ").append(d.path("item").asString()).append(": ").append(d.path("state").asString()).append('\n'));
		}
		return sb.toString();
	}

	private Context build(Ask req, List<Evidence> evidence, String summary, String preContext, String scopeLabel, String title) {
		int budget = budgetChars();
		var ev = new StringBuilder();
		for (var e : evidence) {
			String line = "[" + e.n() + "] " + e.clock() + " " + e.speaker() + ": " + e.text() + "\n";
			if (ev.length() + line.length() > budget * 0.6) {
				break;
			}
			ev.append(line);
		}
		int left = budget - ev.length();
		// Trimmed in this order (PRD §6.5): pre-meeting context first, then the summary.
		String sum = summary.length() > left * 0.6 ? summary.substring(0, (int) (left * 0.6)) : summary;
		left -= sum.length();
		String pre = preContext.length() > Math.max(0, left) ? preContext.substring(0, Math.max(0, left)) : preContext;
		String action = req.action() == null ? "" : req.action();
		String task = switch (action) {
			case "was_discussed" -> "Was \"" + req.question() + "\" discussed? First decide which evidence lines are actually about it. "
					+ "If none are, answer exactly: Not discussed. (no citation) Otherwise answer yes/no in one sentence, then summarise what was said, citing lines.";
			case "suggest" -> "Suggest exactly 3 short questions the user could ask now. They must not already be answered in the evidence. "
					+ "Use open agenda items, gaps and ambiguities. Number them 1-3. One line each.";
			case "last5" -> "Summarise the last 5 minutes in 3-5 bullets, citing lines.";
			case "decisions" -> "List the decisions made and action items (with owners) so far, citing lines. If none, say so.";
			case "open_agenda" -> "Using the agenda in the pre-meeting context and the agenda status, list the agenda items that are still open "
					+ "or only partly covered.";
			default -> req.question();
		};
		String system = """
				You answer questions about %s%s using ONLY the numbered transcript evidence and the notes below.
				After each factual claim, cite the evidence line number in square brackets, e.g. [3]. Never cite the summary or notes. If the evidence does not contain the answer,
				say plainly that it was not discussed. Be brief.""".formatted(scopeLabel, title == null ? "" : " \"" + title + "\"");
		String user = (pre.isBlank() ? "" : "Pre-meeting context (agenda/notes):\n" + pre + "\n\n")
				+ (sum.isBlank() ? "" : "Notes (navigation only, not evidence):\n" + sum + "\n\n")
				+ "Transcript evidence:\n" + (ev.isEmpty() ? "(none)\n" : ev) + "\nTask: " + task;
		return new Context(List.of(Message.system(system), Message.user(user)), evidence);
	}
}
