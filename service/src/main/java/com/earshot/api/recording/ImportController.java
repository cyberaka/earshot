package com.earshot.api.recording;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.earshot.api.settings.Settings;

@RestController
@RequestMapping("/api")
public class ImportController {

	private final ImportService imports;
	private final Settings settings;

	public ImportController(ImportService imports, Settings settings) {
		this.imports = imports;
		this.settings = settings;
	}

	@PostMapping("/import")
	public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files,
			@RequestParam(value = "lastModified", required = false) List<Long> lastModified,
			@RequestParam(value = "timeZone", required = false) String timeZone,
			@RequestParam(value = "clockOffsetS", defaultValue = "0") int clockOffsetS,
			@RequestParam(value = "language", required = false) String language) throws IOException {
		var in = new ArrayList<ImportService.Incoming>();
		for (int i = 0; i < files.size(); i++) {
			Long mod = lastModified != null && i < lastModified.size() ? lastModified.get(i) : null;
			in.add(new ImportService.Incoming(files.get(i).getOriginalFilename(), files.get(i).getInputStream(),
					mod == null ? null : Instant.ofEpochMilli(mod)));
		}
		String tz = timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault().getId() : timeZone;
		String lang = language == null || language.isBlank() ? settings.str("language") : language;
		return imports.importFiles(in, tz, clockOffsetS, lang);
	}

	public record Adjust(String timeZone, int clockOffsetS) {
	}

	@PatchMapping("/imports/{id}")
	public List<Map<String, Object>> adjust(@PathVariable long id, @RequestBody Adjust req) {
		imports.adjust(id, req.timeZone(), req.clockOffsetS());
		return imports.files(id);
	}

	@GetMapping("/imports/{id}")
	public List<Map<String, Object>> files(@PathVariable long id) {
		return imports.files(id);
	}

	@PostMapping("/imports/{id}/process")
	public Map<String, Object> process(@PathVariable long id) {
		return Map.of("queued", imports.process(id));
	}
}
