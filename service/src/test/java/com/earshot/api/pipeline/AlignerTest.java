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
}
