package com.earshot.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.earshot.api.conversation.ConversationService.SpeakerView;
import com.earshot.api.speaker.SpeakerNames.Speaker;

class ConversationServiceTest {

	@Test
	void aDayCardShowsOnlyTheSpeakersInUseMeFirstThenByWhoSpokeFirst() {
		// Conversation 30 on OpenAI's transcript: Earshot's "Me" (cluster 1) is kept but hidden, so it has no entry.
		var speakers = Map.of(1L, new Speaker(1, "Me", 7L, true, null), 10L, new Speaker(10, "Speaker C", null, false, null),
				11L, new Speaker(11, "Speaker A", null, false, null), 12L, new Speaker(12, "Thanveer", 9L, false, null));
		var first = new HashMap<Long, Double>(Map.of(10L, 1400.0, 11L, 1285.1, 12L, 1300.0));
		assertThat(ConversationService.cardSpeakers(speakers, first)).extracting(Speaker::name)
				.containsExactly("Speaker A", "Thanveer", "Speaker C");

		// Named "Me" comes first however late they spoke; a speaker not yet heard comes last.
		first.put(1L, 2000.0);
		first.put(13L, null);
		var withNew = new HashMap<>(speakers);
		withNew.put(13L, new Speaker(13, "Speaker D", null, false, null));
		assertThat(ConversationService.cardSpeakers(withNew, first)).extracting(Speaker::name)
				.containsExactly("Me", "Speaker A", "Thanveer", "Speaker C", "Speaker D");
	}

	@Test
	void theConversationPageListsSpeakersInTheDayCardsOrder() {
		// Alphabetically this was Me, Anjali, Zed; by who spoke first it is Me, Zed, Anjali.
		var views = new ArrayList<>(List.of(view(3, "Anjali", false), view(2, "Zed", false), view(1, "Me", true)));
		views.sort(ConversationService.speakerOrder(SpeakerView::isMe, SpeakerView::clusterId, Map.of(1L, 50.0, 2L, 10.0, 3L, 20.0)));
		assertThat(views).extracting(SpeakerView::name).containsExactly("Me", "Zed", "Anjali");
	}

	private static SpeakerView view(long id, String name, boolean me) {
		return new SpeakerView(id, name, null, me, "u" + id, null, 0, null, null, false, null, List.of(), false);
	}
}
