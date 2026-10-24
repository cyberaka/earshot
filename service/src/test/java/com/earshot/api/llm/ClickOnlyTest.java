package com.earshot.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Owner rule D-10 (2026-10-24): no model call, and no re-transcription, without the user's click. Every call goes through a
 * few entry points, and those may be called only from controllers, never from a job, a timer or the
 * live path. A new caller fails this test until it is a button.
 */
class ClickOnlyTest {

	private static final Path SRC = Path.of("src/main/java/com/earshot/api");

	// Where a model is actually called, and the only files allowed to do it.
	private static final Pattern MODEL_CALL = Pattern.compile("(?<!ml)\\.(chat|chatStream|embed)\\(");
	private static final List<String> MAY_CALL_MODEL = List.of("llm/", "pipeline/Enricher.java", "meeting/MeetingService.java",
			"ask/AskService.java", "search/Indexer.java");

	// The entry points, and the classes that wrap them (the click arrives through a controller).
	private static final Map<Pattern, List<String>> ENTRY_POINTS = Map.of(
			Pattern.compile("(enricher|Enricher)\\.summarize\\("), List.of("conversation/ConversationController.java"),
			Pattern.compile("meetings\\.summarize\\("), List.of("meeting/MeetingController.java"),
			Pattern.compile("\\.indexAll\\("), List.of("search/SearchController.java"),
			Pattern.compile("\\.embedQuery\\("), List.of("search/SearchService.java"),
			// A full re-transcription (heavy CPU and GPU) is queued only by the Re-transcribe button.
			Pattern.compile("meetings\\.retranscribe\\("), List.of("meeting/MeetingController.java"),
			Pattern.compile("enqueue\\(\"post_meeting_full\""), List.of("meeting/MeetingService.java"));

	private static List<String> files() throws IOException {
		try (var s = Files.walk(SRC)) {
			return s.filter(p -> p.toString().endsWith(".java")).map(p -> SRC.relativize(p).toString()).sorted().toList();
		}
	}

	@Test
	void onlyTheInsightClassesCallAModel() throws IOException {
		var offenders = new ArrayList<String>();
		for (String f : files()) {
			if (MODEL_CALL.matcher(Files.readString(SRC.resolve(f))).find() && MAY_CALL_MODEL.stream().noneMatch(f::startsWith)) {
				offenders.add(f);
			}
		}
		assertThat(offenders).as("files calling a model outside the click-only entry points").isEmpty();
	}

	@Test
	void summariesAndIndexingStartOnlyFromAClick() throws IOException {
		var offenders = new ArrayList<String>();
		for (String f : files()) {
			String code = Files.readString(SRC.resolve(f));
			ENTRY_POINTS.forEach((p, allowed) -> {
				if (p.matcher(code).find() && !allowed.contains(f)) {
					offenders.add(f + " → " + p.pattern());
				}
			});
		}
		assertThat(offenders).as("summary or indexing started outside a controller").isEmpty();
	}

	@Test
	void searchByMeaningOnlyWhenAsked() throws IOException {
		// The query is embedded only on the user's tick; Ask and the plain search use keywords.
		String search = Files.readString(SRC.resolve("search/SearchService.java"));
		assertThat(search).contains("if (byMeaning) {");
		assertThat(Files.readString(SRC.resolve("ask/AskService.java"))).doesNotContainPattern("search\\.search\\([^;]*,\\s*true\\)");
	}
}
