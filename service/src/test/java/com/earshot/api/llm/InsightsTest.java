package com.earshot.api.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InsightsTest {

	@Test
	void everyFeatureCanBeOffOrCloud() {
		for (var f : Insights.Feature.values()) {
			assertThatCode(() -> Insights.validate(f.settingKey(), "off")).doesNotThrowAnyException();
			assertThatCode(() -> Insights.validate(f.settingKey(), "openai")).doesNotThrowAnyException();
		}
	}

	@Test
	void liveSummaryNeverUsesTheLocalModel() {
		// It would share the GPU with live transcription (meeting 20, 2026-10-24).
		assertThatThrownBy(() -> Insights.validate("insights_live_summary", "local")).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> Insights.validate("insights_meeting_summary", "local")).doesNotThrowAnyException();
	}

	@Test
	void semanticSearchNeedsEmbeddings() {
		assertThatThrownBy(() -> Insights.validate("insights_semantic_search", "anthropic")).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> Insights.validate("insights_semantic_search", "local")).doesNotThrowAnyException();
	}

	@Test
	void unknownProvidersAreRejectedAndOtherSettingsIgnored() {
		assertThatThrownBy(() -> Insights.validate("insights_ask", "gemini")).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> Insights.validate("language", "en+hi")).doesNotThrowAnyException();
	}
}
