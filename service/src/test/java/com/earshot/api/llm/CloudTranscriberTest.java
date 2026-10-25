package com.earshot.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CloudTranscriberTest {

	@Test
	void theModelReturningItsPromptIsNotSpeech() {
		// OpenAI returned the Hinglish example word for word for a garbled line (23 Oct recorder day, conversation 30).
		String prompt = CloudTranscriber.HINGLISH_PROMPT + " Names and terms: Thanveer, Sage.";
		assertThat(CloudTranscriber.echoes("Haan bhai, theek hai. Mujhe lagta hai ki yeh kaam aaj karna hai, data ka issue dekh lo.", prompt)).isTrue();
		assertThat(CloudTranscriber.echoes("Thanveer, Sage.", prompt)).isTrue();
		assertThat(CloudTranscriber.echoes("Data engineering totally aapka tha, coding part mera tha.", prompt)).isFalse();
		assertThat(CloudTranscriber.echoes("Haan.", prompt)).isFalse();
		assertThat(CloudTranscriber.echoes("anything", null)).isFalse();
	}

	@Test
	void hinglishMustComeBackInLatinLetters() {
		// Conversation 30: OpenAI answered in Devanagari and once in Urdu script.
		assertThat(CloudTranscriber.notLatin("काम में कोई ये नहीं चलेगा")).isTrue();
		assertThat(CloudTranscriber.notLatin("میں ٹھیک ہے")).isTrue();
		assertThat(CloudTranscriber.notLatin("Data engineering totally aapka tha, café")).isFalse();
	}
}
