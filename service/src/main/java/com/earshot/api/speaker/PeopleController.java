package com.earshot.api.speaker;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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

@RestController
@RequestMapping("/api")
public class PeopleController {

	private static final java.util.concurrent.ExecutorService VOICES = java.util.concurrent.Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("voice-refresh").factory());
	private final PeopleService people;
	private final com.earshot.api.meeting.MeetingService meetings;

	public PeopleController(PeopleService people, com.earshot.api.meeting.MeetingService meetings) {
		this.people = people;
		this.meetings = meetings;
	}

	@GetMapping("/people")
	public Map<String, Object> list() {
		return Map.of("people", people.list(), "unknowns", people.unknowns(20));
	}

	public record Assign(Long personId, String name, boolean me, boolean addToLibrary, boolean unnamed) {
	}

	@PatchMapping("/speaker-clusters/{id}")
	public Map<String, Object> assign(@PathVariable long id, @RequestBody Assign req) {
		if (req.unnamed()) {
			people.unname(id);
			meetings.onSpeakerChanged(id);
			return Map.of("ok", true);
		}
		if (req.personId() == null && !req.me() && (req.name() == null || req.name().isBlank())) {
			throw new IllegalArgumentException("Give a person or a name.");
		}
		people.assign(id, req.personId(), req.name(), req.me(), req.addToLibrary());
		// Naming a speaker during a meeting updates the live view at once.
		meetings.onSpeakerChanged(id);
		return Map.of("ok", true);
	}

	@PatchMapping("/segments/{id}/speaker")
	public Map<String, Object> assignLine(@PathVariable long id, @RequestBody Assign req) {
		if (req.personId() == null && !req.me() && (req.name() == null || req.name().isBlank())) {
			throw new IllegalArgumentException("Give a person or a name.");
		}
		long[] moved = people.assignLine(id, req.personId(), req.name(), req.me(), req.addToLibrary());
		meetings.onLineChanged(id, moved[1]);
		// Both speakers' voices now come from different lines (the old one may be a remembered voice).
		// Embedding a few minutes of audio takes seconds: the line moves at once, the voices follow.
		VOICES.submit(() -> {
			try {
				if (moved[0] > 0) {
					people.refreshVoice(moved[0]);
				}
				people.refreshVoice(moved[1]);
				people.rematch();
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger(PeopleController.class).warn("event=voice_refresh_failed segment={} reason={}", id, e.getMessage());
			}
		});
		return Map.of("ok", true, "clusterId", moved[1]);
	}

	public record AssignLines(List<Long> ids, Long personId, String name, boolean me, boolean addToLibrary) {
	}

	/** A run of lines said by one person (R-4): one request, the voices refreshed once. */
	@PatchMapping("/segments/speaker")
	public Map<String, Object> assignLines(@RequestBody AssignLines req) {
		if (req.personId() == null && !req.me() && (req.name() == null || req.name().isBlank())) {
			throw new IllegalArgumentException("Give a person or a name.");
		}
		var moved = people.assignLines(req.ids(), req.personId(), req.name(), req.me(), req.addToLibrary());
		var clusters = new java.util.LinkedHashSet<Long>();
		for (int i = 0; i < moved.size(); i++) {
			meetings.onLineChanged(req.ids().get(i), moved.get(i)[1]);
			if (moved.get(i)[0] > 0) {
				clusters.add(moved.get(i)[0]);
			}
			clusters.add(moved.get(i)[1]);
		}
		VOICES.submit(() -> {
			try {
				clusters.forEach(people::refreshVoice);
				people.rematch();
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger(PeopleController.class).warn("event=voice_refresh_failed segments={} reason={}", req.ids().size(), e.getMessage());
			}
		});
		return Map.of("ok", true, "lines", moved.size());
	}

	public record Update(String name, List<String> aliases) {
	}

	@PatchMapping("/people/{id}")
	public Map<String, Object> update(@PathVariable long id, @RequestBody Update req) {
		people.update(id, req.name(), req.aliases());
		return Map.of("ok", true);
	}

	public record Merge(long intoId) {
	}

	@PostMapping("/people/{id}/merge")
	public Map<String, Object> merge(@PathVariable long id, @RequestBody Merge req) {
		people.merge(id, req.intoId());
		return Map.of("ok", true);
	}

	@DeleteMapping("/people/{id}")
	public Map<String, Object> delete(@PathVariable long id) {
		people.delete(id);
		return Map.of("ok", true);
	}

	@PostMapping("/enroll")
	public Map<String, Object> enroll(@RequestParam("file") MultipartFile file,
			@RequestParam(value = "source", defaultValue = "laptop_mic") String source,
			@RequestParam(value = "personId", required = false) Long personId) throws IOException {
		people.enrollFromClip(file, source, personId);
		return Map.of("ok", true);
	}

	public record Segments(List<Long> segmentIds) {
	}

	@PostMapping("/enroll/segments")
	public Map<String, Object> enrollSegments(@RequestBody Segments req) {
		people.enrollFromSegments(req.segmentIds());
		return Map.of("ok", true);
	}

	@GetMapping("/people/{id}/voiceprints")
	public List<Map<String, Object>> voiceprints(@PathVariable long id) {
		return people.voiceprints(id);
	}

	@DeleteMapping("/voiceprints/{id}")
	public Map<String, Object> deleteVoiceprint(@PathVariable long id) {
		people.deleteVoiceprint(id);
		return Map.of("ok", true);
	}

	@PostMapping("/people/rematch")
	public Map<String, Object> rematch() {
		people.rematch();
		return Map.of("queued", true);
	}
}
