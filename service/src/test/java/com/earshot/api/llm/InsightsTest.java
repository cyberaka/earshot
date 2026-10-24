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
	void theLocalModelCanBeChosenForEveryChatFeature() {
		// A click during a live meeting is honoured (owner, 2026-10-24): the choice is the user's, the UI warns.
		for (String key : new String[] { "insights_live_summary", "insights_meeting_summary", "insights_ask" }) {
			assertThatCode(() -> Insights.validate(key, "local")).doesNotThrowAnyException();
		}
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
