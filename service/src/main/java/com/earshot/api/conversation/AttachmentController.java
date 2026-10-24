package com.earshot.api.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Screenshots in a meeting's transcript (R-1). */
@RestController
@RequestMapping("/api")
public class AttachmentController {

	private final Attachments attachments;
	private final com.earshot.api.meeting.LiveTranscript transcripts;

	public AttachmentController(Attachments attachments, com.earshot.api.meeting.LiveTranscript transcripts) {
		this.attachments = attachments;
		this.transcripts = transcripts;
	}

	/** {@code lastModified}: the browser's File.lastModified (ms), a hint for when it was taken. */
	@PostMapping("/meetings/{id}/attachments")
	public Map<String, Object> add(@PathVariable long id, @RequestParam("file") MultipartFile file,
			@RequestParam(value = "lastModified", required = false) Long lastModified,
			@RequestParam(value = "caption", required = false) String caption) throws IOException {
		try (var in = file.getInputStream()) {
			var a = attachments.add(id, file.getOriginalFilename(), file.getSize(), in, lastModified, caption);
			transcripts.changed(id);
			return Map.of("id", a.id(), "conversationId", a.conversationId(), "placedBy", a.placedBy());
		}
	}

	public record Caption(String caption) {
	}

	@PatchMapping("/attachments/{id}")
	public Map<String, Object> caption(@PathVariable long id, @RequestBody Caption req) {
		attachments.caption(id, req.caption());
		return Map.of("ok", true);
	}

	@DeleteMapping("/attachments/{id}")
	public Map<String, Object> delete(@PathVariable long id) throws IOException {
		attachments.delete(id);
		return Map.of("ok", true);
	}

	@GetMapping("/attachments/{id}/file")
	public ResponseEntity<FileSystemResource> file(@PathVariable long id) {
		var f = attachments.file(id);
		if (!Files.exists(f.path())) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
				.cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePrivate()).body(new FileSystemResource(f.path()));
	}
}
