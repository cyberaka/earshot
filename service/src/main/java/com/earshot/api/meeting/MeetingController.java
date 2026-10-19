package com.earshot.api.meeting;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.earshot.api.ml.MlClient;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

	private final MeetingService meetings;
	private final MlClient ml;
	private final JdbcClient db;

	public MeetingController(MeetingService meetings, MlClient ml, JdbcClient db) {
		this.meetings = meetings;
		this.ml = ml;
		this.db = db;
	}

	/** Devices, the headphone check (B-10) and whether a meeting is already live. */
	@GetMapping("/preflight")
	public Map<String, Object> preflight() {
		JsonNode devices = ml.devices();
		return Map.of("devices", devices, "activeMeetingId", meetings.activeId() == null ? "" : meetings.activeId());
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		return db.sql("""
				SELECT m.id, m.title, m.audio_mode, m.started_at, m.ended_at, m.status FROM meeting m ORDER BY m.id DESC LIMIT 50""")
				.query().listOfRows();
	}

	@PostMapping
	public Map<String, Object> start(@RequestBody MeetingService.Start req) throws Exception {
		return Map.of("meetingId", meetings.start(req));
	}

	@PostMapping("/{id}/stop")
	public Map<String, Object> stop(@PathVariable long id) {
		return meetings.stop(id);
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(@PathVariable long id) {
		return meetings.view(id);
	}

	/** Pre-meeting context from an uploaded .md, .txt or .pdf (B-6). */
	@PostMapping("/context")
	public Map<String, Object> context(@RequestParam("file") MultipartFile file) throws IOException {
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
		String text;
		if (name.endsWith(".pdf")) {
			try (var doc = Loader.loadPDF(file.getBytes())) {
				text = new PDFTextStripper().getText(doc);
			}
		} else {
			text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		return Map.of("text", text.strip());
	}
}
