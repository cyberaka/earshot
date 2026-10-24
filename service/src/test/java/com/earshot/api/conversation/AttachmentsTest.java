package com.earshot.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class AttachmentsTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Test
	void screenshotTimeComesFromTheMacFileName() {
		assertThat(Attachments.takenFromName("Screenshot 2026-10-24 at 11.18.52.png", IST)).isEqualTo(Instant.parse("2026-10-24T05:48:52Z"));
		assertThat(Attachments.takenFromName("Screen Shot 2026-10-24 at 2.05.07 PM.png", IST)).isEqualTo(Instant.parse("2026-10-24T08:35:07Z"));
		assertThat(Attachments.takenFromName("Screen Shot 2026-10-24 at 12.05.07 AM.png", IST)).isEqualTo(Instant.parse("2026-10-23T18:35:07Z"));
		assertThat(Attachments.takenFromName("CleanShot 2026-10-24 at 11.18.52@2x.png", IST)).isEqualTo(Instant.parse("2026-10-24T05:48:52Z"));
		assertThat(Attachments.takenFromName("image.png", IST)).isNull();
		assertThat(Attachments.takenFromName(null, IST)).isNull();
	}

	@Test
	void onlyRealImagesAreKept() {
		assertThat(Attachments.imageType(new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 })).isEqualTo("image/png");
		assertThat(Attachments.imageType(new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0 })).isEqualTo("image/jpeg");
		assertThat(Attachments.imageType("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
		assertThat(Attachments.imageType("%PDF-1.7".getBytes())).isNull();
		assertThat(Attachments.imageType(new byte[0])).isNull();
	}
}
