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

	private final java.nio.file.Path replayDir;

	public MeetingController(MeetingService meetings, MlClient ml, JdbcClient db, com.earshot.api.config.EarshotProperties props) {
		this.meetings = meetings;
		this.ml = ml;
		this.db = db;
		this.replayDir = props.data().resolve("audio/replay");
	}

	/**
	 * A file dropped or picked for "Replay audio files": browsers never give a page a file's path, so
	 * the file is uploaded and turned into 16 kHz mono FLAC here (any format ffmpeg reads, Zoom's MP4
	 * included). Returns the path to replay. Same name again replaces the earlier copy.
	 */
	@PostMapping("/replay-file")
	public Map<String, Object> replayFile(@RequestParam("file") MultipartFile file) throws IOException, InterruptedException {
		String original = file.getOriginalFilename() == null ? "audio" : file.getOriginalFilename();
		String base = original.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
		java.nio.file.Files.createDirectories(replayDir);
		var upload = java.nio.file.Files.createTempFile(replayDir, "upload-", ".tmp");
		var out = replayDir.resolve((base.isEmpty() ? "audio" : base) + ".flac");
		try {
			file.transferTo(upload);
			var p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-i", upload.toString(), "-vn", "-ac", "1", "-ar", "16000",
					"-c:a", "flac", out.toString()).redirectErrorStream(true).start();
			String err = new String(p.getInputStream().readAllBytes()).strip();
			if (p.waitFor() != 0) {
				org.slf4j.LoggerFactory.getLogger(MeetingController.class).warn("event=replay_file_unreadable name={} reason={}", original,
						err.replace('\n', ' '));
				throw new IllegalArgumentException("“" + original + "” has no audio Earshot can read. Try a WAV, FLAC, MP3, M4A or MP4 file.");
			}
		} finally {
			java.nio.file.Files.deleteIfExists(upload);
		}
		return Map.of("path", out.toString(), "name", original);
	}

	/** Devices and whether a meeting is already live. */
	@GetMapping("/preflight")
	public Map<String, Object> preflight() {
		JsonNode devices = ml.devices();
		return Map.of("devices", devices, "activeMeetingId", meetings.activeId() == null ? "" : meetings.activeId());
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		return db.sql("""
				SELECT m.id, m.title, m.audio_mode, m.started_at, m.ended_at, m.status,
				       (SELECT count(*) FROM conversation c WHERE c.meeting_id = m.id) AS parts,
				       (SELECT sum(c.end_offset_s - c.start_offset_s) FROM conversation c WHERE c.meeting_id = m.id) AS duration_s
				FROM meeting m ORDER BY m.id DESC LIMIT 50""")
				.query().listOfRows();
	}

	@PostMapping
	public Map<String, Object> start(@RequestBody MeetingService.Start req) throws Exception {
		return Map.of("meetingId", meetings.start(req));
	}

	@PostMapping("/{id}/resume")
	public Map<String, Object> resume(@PathVariable long id, @RequestBody MeetingService.Start req) throws Exception {
		return Map.of("meetingId", meetings.resume(id, req));
	}

	@PostMapping("/{id}/device")
	public Map<String, Object> device(@PathVariable long id, @RequestBody MeetingService.Start req) {
		return meetings.switchDevice(id, req);
	}

	public record Language(String language) {
	}

	@PostMapping("/{id}/language")
	public Map<String, Object> language(@PathVariable long id, @RequestBody Language req) {
		return meetings.switchLanguage(id, req.language());
	}

	public record VocabularyText(String vocabulary) {
	}

	@PostMapping("/{id}/vocabulary")
	public Map<String, Object> vocabulary(@PathVariable long id, @RequestBody VocabularyText req) {
		return meetings.switchVocabulary(id, req.vocabulary());
	}

	@PostMapping("/{id}/stop")
	public Map<String, Object> stop(@PathVariable long id) {
		return meetings.stop(id);
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(@PathVariable long id) {
		return meetings.view(id);
	}

	/** The user clicked Re-transcribe: a full pass from the saved audio, as a batch job. */
	@PostMapping("/{id}/retranscribe")
	public Map<String, Object> retranscribe(@PathVariable long id) {
		return meetings.retranscribe(id);
	}

	/** What a click on Summarize would send (no model call). */
	@GetMapping("/{id}/summary")
	public Map<String, Object> summaryEstimate(@PathVariable long id) {
		return meetings.summaryEstimate(id);
	}

	/** The user clicked Summarize: one model call (owner rule, 2026-10-24). */
	@PostMapping("/{id}/summary")
	public Map<String, Object> summarize(@PathVariable long id) {
		return meetings.summarize(id);
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
