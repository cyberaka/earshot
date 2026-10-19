package com.earshot.api.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SegmenterTest {

	@Test
	void gapOfTwoMinutesStartsANewConversation() {
		var regions = List.of(new double[] { 0, 30 }, new double[] { 35, 60 }, new double[] { 200, 260 });
		var spans = Segmenter.split(regions, 120, 5400, 20, 300);
		assertThat(spans).hasSize(2);
		assertThat(spans.get(0).end()).isLessThan(61);
		assertThat(spans.get(1).start()).isGreaterThan(199);
	}

	@Test
	void shortConversationsAreDropped() {
		var spans = Segmenter.split(List.of(new double[] { 0, 10 }, new double[] { 200, 260 }), 120, 5400, 20, 300);
		assertThat(spans).hasSize(1);
	}

	@Test
	void tooLongConversationSplitsAtItsLongestInternalGap() {
		var regions = new ArrayList<double[]>();
		for (int i = 0; i < 100; i++) {
			// 100 minutes of talk with 30 s pauses, and one 90 s pause at minute 40.
			double start = i * 60 + (i >= 40 ? 60 : 0);
			regions.add(new double[] { start, start + 30 });
		}
		var spans = Segmenter.split(regions, 120, 90 * 60, 20, 7000);
		assertThat(spans).hasSize(2);
		assertThat(spans.get(0).regions()).hasSize(40);
	}
}
