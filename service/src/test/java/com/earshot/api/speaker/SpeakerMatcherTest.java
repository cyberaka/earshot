package com.earshot.api.speaker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.earshot.api.speaker.SpeakerMatcher.Best;

class SpeakerMatcherTest {

	private static Long pick(Best... ranked) {
		return SpeakerMatcher.pick(List.of(ranked), 0.6, 0.6, 0.05);
	}

	@Test
	void namesAClearWinner() {
		assertThat(pick(new Best(22L, false, 0.70), new Best(23L, false, 0.60))).isEqualTo(22L);
		assertThat(pick(new Best(22L, false, 0.63))).isEqualTo(22L);
	}

	@Test
	void leavesANearTieUnnamed() {
		// Conversation 113, 27 Oct: Pooja's voice, Thejasvini 0.63 and Macrina 0.60.
		assertThat(pick(new Best(22L, false, 0.630), new Best(23L, false, 0.596))).isNull();
		// Me as the runner-up counts too.
		assertThat(pick(new Best(27L, false, 0.610), new Best(1L, true, 0.609))).isNull();
	}

	@Test
	void belowTheThresholdIsUnnamed() {
		assertThat(pick(new Best(22L, false, 0.59))).isNull();
		assertThat(pick()).isNull();
	}

	@Test
	void meNeedsOnlyItsThreshold() {
		assertThat(pick(new Best(1L, true, 0.61), new Best(22L, false, 0.60))).isEqualTo(1L);
		assertThat(pick(new Best(1L, true, 0.55))).isNull();
	}
}
