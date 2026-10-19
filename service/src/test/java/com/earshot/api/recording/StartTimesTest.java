package com.earshot.api.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class StartTimesTest {

	private static final String PATTERN = "(\\d{8})[_-](\\d{6})";
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Test
	void filenameStampIsLocalTimeInTheImportZone() {
		var d = StartTimes.derive("REC_20261020_094000.wav", PATTERN, IST, null);
		assertThat(d.source()).isEqualTo("filename");
		assertThat(d.startedAt()).isEqualTo(Instant.parse("2026-10-20T04:10:00Z"));
	}

	@Test
	void fallsBackToModifiedTime() {
		Instant mod = Instant.parse("2026-10-20T10:00:00Z");
		var d = StartTimes.derive("REC0007.WAV", PATTERN, IST, mod);
		assertThat(d.source()).isEqualTo("mtime");
		assertThat(d.startedAt()).isEqualTo(mod);
	}

	@Test
	void invalidStampIsIgnored() {
		assertThat(StartTimes.fromFilename("REC_20261399_999999.wav", PATTERN, IST)).isEmpty();
	}
}
