package com.earshot.api.conversation;

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
import org.springframework.web.bind.annotation.RestController;

import com.earshot.api.settings.Vocabulary;

/** The user's corrections (owner request R-4): edit a paragraph, hear it again, remember a correction. */
@RestController
@RequestMapping("/api")
public class LineController {

	private final LineEdits lines;
	private final Vocabulary vocabulary;

	public LineController(LineEdits lines, Vocabulary vocabulary) {
		this.lines = lines;
		this.vocabulary = vocabulary;
	}

	public record Edit(List<Long> ids, String text, String by) {
	}

	@PatchMapping("/lines/text")
	public Map<String, Object> edit(@RequestBody Edit req) {
		return Map.of("id", lines.edit(req.ids(), req.text(), req.by()));
	}

	@PostMapping("/lines/{id}/revert")
	public Map<String, Object> revert(@PathVariable long id) {
		lines.revert(id);
		return Map.of("ok", true);
	}

	public record Retranscribe(List<Long> ids, String engine) {
	}

	/** The user clicked "Re-transcribe" on a paragraph: a proposal, not stored until kept. */
	@PostMapping("/lines/retranscribe")
	public LineEdits.Proposal retranscribe(@RequestBody Retranscribe req) throws IOException, InterruptedException {
		return lines.retranscribe(req.ids(), req.engine());
	}

	@GetMapping("/lines/retranscribe")
	public Map<String, Object> options() {
		return lines.options();
	}

	@GetMapping("/corrections")
	public List<Vocabulary.Correction> corrections() {
		return vocabulary.corrections();
	}

	public record NewCorrection(String wrong, String fixed) {
	}

	@PostMapping("/corrections")
	public Vocabulary.Correction add(@RequestBody NewCorrection req) {
		return vocabulary.addCorrection(req.wrong(), req.fixed());
	}

	@DeleteMapping("/corrections/{id}")
	public Map<String, Object> delete(@PathVariable long id) {
		vocabulary.deleteCorrection(id);
		return Map.of("ok", true);
	}

	/** Fixes the lines already transcribed with this mistake. */
	@PostMapping("/corrections/{id}/apply")
	public Map<String, Object> apply(@PathVariable long id) {
		return Map.of("lines", lines.applyCorrection(id));
	}
}
