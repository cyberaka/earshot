package com.earshot.api.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VocabularyTest {

	@Test
	void splitsOnCommasSemicolonsAndLinesAndDropsRepeats() {
		assertThat(Vocabulary.split("Abhinav, Hardik\nRapido;  Zomato ,, Abhinav")).containsExactly("Abhinav", "Hardik", "Rapido", "Zomato");
		assertThat(Vocabulary.split(null)).isEmpty();
		assertThat(Vocabulary.split("  ")).isEmpty();
	}

	@Test
	void correctionsReplaceWholeWordsAndPhrasesInAnyCase() {
		// From the 23 Oct recorder day: "Taaf engineer" was "staff engineer" (owner request R-4).
		var fixes = java.util.List.of(new Vocabulary.Correction(1, "Taaf engineer", "staff engineer"), new Vocabulary.Correction(2, "Saige", "Sage"));
		assertThat(Vocabulary.correct("Taaf engineer dudo. taaf ENGINEER", fixes)).isEqualTo("staff engineer dudo. staff engineer");
		assertThat(Vocabulary.correct("Saige me baht focus", fixes)).isEqualTo("Sage me baht focus");
		// Only whole words: a longer word that contains it is left alone.
		assertThat(Vocabulary.correct("Saiges and Taaf engineers", fixes)).isEqualTo("Saiges and Taaf engineers");
		assertThat(Vocabulary.correct("$1 for Saige", java.util.List.of(new Vocabulary.Correction(3, "Saige", "Sage $2")))).isEqualTo("$1 for Sage $2");
		assertThat(Vocabulary.correct(null, fixes)).isNull();
	}
}
