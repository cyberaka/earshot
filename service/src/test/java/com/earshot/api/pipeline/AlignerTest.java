package com.earshot.api.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AlignerTest {

	private static Aligner.Word w(String t, double s, double e) {
		return new Aligner.Word(t, s, e, 0.9);
	}

	@Test
	void wordsGoToTheTurnTheyOverlapMost() {
		var turns = List.of(new Aligner.Turn(0, 2.0, "A"), new Aligner.Turn(2.0, 4.0, "B"));
		var segs = Aligner.align(List.of(List.of(w("Hello", 0.1, 0.5), w("there.", 0.6, 1.9), w("Hi", 1.95, 2.6), w("back.", 2.7, 3.2))), turns, 1.5);
		assertThat(segs).extracting(Aligner.Seg::speaker, Aligner.Seg::text)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("A", "Hello there."), org.assertj.core.groups.Tuple.tuple("B", "Hi back."));
	}

	@Test
	void subSecondTurnBetweenOneSpeakerIsSmoothedAway() {
		var turns = List.of(new Aligner.Turn(0, 3, "A"), new Aligner.Turn(3, 3.5, "B"), new Aligner.Turn(3.5, 6, "A"));
		assertThat(Aligner.smooth(turns)).extracting(Aligner.Turn::speaker).containsExactly("A", "A", "A");
	}

	@Test
	void longPauseSplitsASegmentOfOneSpeaker() {
		var segs = Aligner.align(List.of(List.of(w("One.", 0, 0.5), w("Two.", 3.0, 3.4))), List.of(new Aligner.Turn(0, 4, "A")), 1.5);
		assertThat(segs).hasSize(2);
	}

	@Test
	void speakerTokensReplaceLabels() {
		assertThat(Enricher.tokens("S1 promised S2 a checklist; S9 unknown", Map.of(1, 10L, 2, 20L)))
				.isEqualTo("{{spk:10}} promised {{spk:20}} a checklist; S9 unknown");
	}

	@Test
	void aStrayWordKeepsTheSentencesSpeaker() {
		// "I felt" / "good" and "I" / "was at a place" were cut mid-sentence by a turn edge (23 Oct recorder day).
		var turns = List.of(new Aligner.Turn(0, 1.5, "A"), new Aligner.Turn(1.5, 2.0, "B"), new Aligner.Turn(2.5, 2.9, "A"), new Aligner.Turn(2.9, 6, "B"));
		var segs = Aligner.align(List.of(List.of(w("I", 0.2, 0.6), w("felt", 0.7, 1.4), w("good.", 1.5, 1.9)),
				List.of(w("I", 2.6, 2.8), w("was", 2.9, 3.2), w("at", 3.3, 3.5), w("a", 3.5, 3.6), w("place.", 3.6, 4.2))), turns, 1.5);
		assertThat(segs.size()).isEqualTo(2);
		assertThat(segs.get(0).speaker()).isEqualTo("A");
		assertThat(segs.get(0).text()).isEqualTo("I felt good.");
		assertThat(segs.get(1).speaker()).isEqualTo("B");
		assertThat(segs.get(1).text()).isEqualTo("I was at a place.");
	}
}
