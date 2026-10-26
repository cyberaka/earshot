package com.earshot.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.earshot.api.speaker.SpeakerNames;

import tools.jackson.databind.json.JsonMapper;

class TranscriptVersionsTest {

	@Test
	void aConversationUnder23MinutesGoesInOneCall() {
		// Conversation 30: 862 s.
		var pieces = TranscriptVersions.pieces(1285.1, 2147.4, List.of(new double[] { 1300, 1310 }));
		assertThat(pieces).hasSize(1);
		assertThat(pieces.getFirst()).containsExactly(1285.1, 2147.4);
	}

	@Test
	void aLongConversationIsCutAtThePauseNearest20Minutes() {
		// 50 minutes of talk with a 30 s pause at 21 min and a 5 s one at 19 min: the first cut is in the long pause.
		var lines = List.of(new double[] { 0, 1140 }, new double[] { 1145, 1260 }, new double[] { 1290, 2400 }, new double[] { 2405, 3000 });
		var pieces = TranscriptVersions.pieces(0, 3000, lines);
		assertThat(pieces).hasSize(3);
		assertThat(pieces.get(0)[1]).isEqualTo(1275.0);
		assertThat(pieces.get(1)[0]).isEqualTo(1275.0);
		assertThat(pieces.get(2)[1]).isEqualTo(3000.0);
		assertThat(pieces).allSatisfy(p -> assertThat(p[1] - p[0]).isLessThanOrEqualTo(TranscriptVersions.ONE_CALL_MAX_S));
	}

	@Test
	void withNoPauseNearbyThePieceEndsAt20Minutes() {
		var pieces = TranscriptVersions.pieces(0, 1500, List.of(new double[] { 0, 1500 }));
		assertThat(pieces).hasSize(2);
		assertThat(pieces.get(0)[1]).isEqualTo(TranscriptVersions.PIECE_S);
	}

	@Test
	void openaisLinesKeepTheirScriptAndMoveToTheRecordingsClock() {
		var json = JsonMapper.builder().build();
		String one = """
				{"text":"x","segments":[{"start":0.0,"end":8.3,"speaker":"A","text":" data engineering totally aapka tha."},
				{"start":9.0,"end":9.5,"speaker":"B","text":"  "},
				{"start":100.0,"end":104.0,"speaker":"A","text":"बड़ी बात है न भाई?"}],
				"usage":{"input_token_details":{"audio_tokens":10,"text_tokens":2},"output_tokens":30}}""";
		String two = """
				{"segments":[{"start":1.0,"end":2.0,"speaker":"A","text":"Theek hai."}],"usage":{"input_token_details":{"audio_tokens":5,"text_tokens":1},"output_tokens":4}}""";
		var p = TranscriptVersions.parse(json, new String[] { one, two }, List.of(new double[] { 1285.1, 2485.1 }, new double[] { 2485.1, 3000 }));
		assertThat(p.lines()).hasSize(3);
		assertThat(p.lines().get(0).path("s").asDouble()).isEqualTo(1285.1);
		assertThat(p.lines().get(0).path("t").asString()).isEqualTo("data engineering totally aapka tha.");
		assertThat(p.lines().get(1).path("t").asString()).isEqualTo("बड़ी बात है न भाई?");
		// Each piece was heard on its own: its A is not the first piece's A.
		assertThat(p.lines().get(0).path("spk").asString()).isEqualTo("openai:1:A");
		assertThat(p.lines().get(2).path("spk").asString()).isEqualTo("openai:2:A");
		assertThat(p.lines().get(2).path("s").asDouble()).isCloseTo(2486.1, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(p.usage().path("audio_tokens").asLong()).isEqualTo(15);
		assertThat(p.usage().path("output_tokens").asLong()).isEqualTo(34);
	}

	@Test
	void oneCallsSpeakersHaveNoPartNumber() {
		var json = JsonMapper.builder().build();
		var p = TranscriptVersions.parse(json, new String[] { """
				{"segments":[{"start":0,"end":1,"speaker":"L","text":"Mil jaayega."}]}""" }, List.of(new double[] { 10, 20 }));
		assertThat(p.lines().get(0).path("spk").asString()).isEqualTo("openai:L");
		assertThat(SpeakerNames.openaiName("openai:L")).isEqualTo("Speaker L");
		assertThat(SpeakerNames.openaiName("openai:2:A")).isEqualTo("Speaker A (part 2)");
	}
}
