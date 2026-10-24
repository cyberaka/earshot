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
}
