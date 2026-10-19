package com.earshot.api.health;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.earshot.api.job.Jobs;
import com.earshot.api.llm.LlmClient;
import com.earshot.api.meeting.MeetingService;
import com.earshot.api.ml.MlClient;
import com.earshot.api.recording.ImportService;
import com.earshot.api.settings.Settings;

/** Health of every part, plus the resources indicator (PRD §8). */
@RestController
public class HealthController {

	private final JdbcClient db;
	private final MlClient ml;
	private final LlmClient llm;
	private final ImportService imports;
	private final MeetingService meetings;
	private final Jobs jobs;
	private final Settings settings;

	public HealthController(JdbcClient db, MlClient ml, LlmClient llm, ImportService imports, MeetingService meetings, Jobs jobs,
			Settings settings) {
		this.db = db;
		this.ml = ml;
		this.llm = llm;
		this.imports = imports;
		this.meetings = meetings;
		this.jobs = jobs;
		this.settings = settings;
	}

	@GetMapping("/api/health")
	public Map<String, Object> health() {
		var out = new LinkedHashMap<String, Object>();
		out.put("db", up(() -> db.sql("SELECT 1").query(Integer.class).single()));
		out.put("ml", up(ml::health));
		out.put("capture", up(ml::captureHealth));
		out.put("llm", llm.available() ? "up" : "down");
		var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		long total = os.getTotalMemorySize();
		out.put("memory", Map.of("totalBytes", total, "usedBytes", total - os.getFreeMemorySize()));
		long free = imports.freeBytes();
		out.put("disk", Map.of("freeBytes", free, "lowDisk", free < settings.num("min_free_disk_gb") * 1024 * 1024 * 1024));
		out.put("llmQueue", llm.queueDepth());
		out.put("activeMeetingId", meetings.activeId());
		out.put("jobsPending", jobs.pending());
		return out;
	}

	private static String up(Runnable check) {
		try {
			check.run();
			return "up";
		} catch (Exception e) {
			return "down";
		}
	}

	@GetMapping("/api/jobs")
	public List<Map<String, Object>> jobs(@RequestParam(defaultValue = "50") int limit) {
		return jobs.recent(Math.min(limit, 200));
	}
}
