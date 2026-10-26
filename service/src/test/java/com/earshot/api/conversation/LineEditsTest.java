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
}
