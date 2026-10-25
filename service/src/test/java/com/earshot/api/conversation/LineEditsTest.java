package com.earshot.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LineEditsTest {

	@Test
	void wordsJoinWithoutSpaceBeforePunctuation() {
		assertThat(LineEdits.join(List.of("Mujhe", "bas", "kaam", "karna", "hai", ",", "aur", "survive", "karna", "hai", "."))).isEqualTo("Mujhe bas kaam karna hai, aur survive karna hai.");
		assertThat(LineEdits.join(List.of("it", "'s", "fine"))).isEqualTo("it's fine");
		assertThat(LineEdits.join(List.of())).isEmpty();
	}

	@Test
	void paragraphsMatchThePage() {
		// One speaker's run with pauses under 3 s; a speaker change or a longer pause starts a new one.
		var a1 = new LineEdits.Line(1, 30, 7L, 0, 2, "a", null, null);
		var a2 = new LineEdits.Line(2, 30, 7L, 4.5, 6, "b", null, null);
		var b1 = new LineEdits.Line(3, 30, 8L, 6.5, 8, "c", null, null);
		var b2 = new LineEdits.Line(4, 30, 8L, 12, 13, "d", null, null);
		var paras = LineEdits.paragraphs(List.of(a1, a2, b1, b2));
		assertThat(paras).hasSize(3);
		assertThat(paras.get(0)).containsExactly(a1, a2);
		assertThat(paras.get(1)).containsExactly(b1);
	}
}
