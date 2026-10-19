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

	private final PeopleService people;

	public PeopleController(PeopleService people) {
		this.people = people;
	}

	@GetMapping("/people")
	public Map<String, Object> list() {
		return Map.of("people", people.list(), "unknowns", people.unknowns(20));
	}

	public record Assign(Long personId, String name, boolean me, boolean addToLibrary) {
	}

	@PatchMapping("/speaker-clusters/{id}")
	public Map<String, Object> assign(@PathVariable long id, @RequestBody Assign req) {
		if (req.personId() == null && !req.me() && (req.name() == null || req.name().isBlank())) {
			throw new IllegalArgumentException("Give a person or a name.");
		}
		people.assign(id, req.personId(), req.name(), req.me(), req.addToLibrary());
		return Map.of("ok", true);
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

	@PostMapping("/people/rematch")
	public Map<String, Object> rematch() {
		people.rematch();
		return Map.of("queued", true);
	}
}
