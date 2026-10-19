package com.earshot.api.ml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.earshot.api.config.EarshotProperties;

import jakarta.annotation.PreDestroy;

/**
 * Starts the ML and capture processes and keeps them alive (PRD §10, process lifecycle).
 * Each child exits by itself when heartbeats stop, so a crashed service leaves no orphans
 * except a capture process finishing a live meeting.
 */
@Component
public class PythonSupervisor {

	private static final Logger log = LoggerFactory.getLogger(PythonSupervisor.class);
	private final EarshotProperties props;
	private final MlClient ml;
	private final Map<String, Process> children = new LinkedHashMap<>();
	private volatile boolean ready;
	private final java.util.Set<String> warnedUnknown = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public PythonSupervisor(EarshotProperties props, MlClient ml) {
		this.props = props;
		this.ml = ml;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (!props.ml().manage()) {
			log.info("event=python_unmanaged");
			return;
		}
		ensure("ml", "earshot_ml.app");
		ensure("capture", "earshot_ml.capture");
		ready = true;
	}

	private synchronized void ensure(String name, String module) {
		Process p = children.get(name);
		if (p != null && p.isAlive()) {
			return;
		}
		Path pidFile = props.data().resolve("run").resolve(name + ".pid");
		if (p == null && children.containsKey(name) && readPid(pidFile).map(ProcessHandle::isAlive).orElse(false)) {
			return; // re-attached earlier and still running
		}
		if (p == null) {
			var old = readPid(pidFile).filter(h -> h.isAlive()).or(() -> name.equals("ml") ? listener(props.ml().baseUrl(), "earshot_ml.app")
					: listener(props.ml().captureUrl(), "earshot_ml.capture"));
			if (old.isPresent() && old.get().isAlive()) {
				if (name.equals("capture")) {
					// May still be recording a meeting through a backend crash: re-attach.
					log.info("event=python_reattached process={} pid={}", name, old.get().pid());
					writePid(pidFile, old.get().pid());
					children.put(name, null);
					return;
				}
				// The ML process holds no state worth keeping; a fresh one runs the current code.
				old.get().destroy();
				old.get().onExit().orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).exceptionally(e -> null).join();
				log.info("event=python_replaced process={} pid={}", name, old.get().pid());
			}
		}
		if (isUp(name)) {
			if (warnedUnknown.add(name)) {
				log.warn("event=python_unknown_instance process={} reason=port_in_use_by_unrecorded_process", name);
			}
			return;
		}
		try {
			Path logs = props.data().resolve("logs");
			Files.createDirectories(logs);
			var pb = new ProcessBuilder(props.ml().python(), "-m", module)
					.directory(new File(props.ml().workdir()))
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(name + ".log").toFile()));
			pb.environment().put("EARSHOT_ML_SECRET", props.ml().secret());
			pb.environment().put("EARSHOT_HEARTBEAT_TIMEOUT_SECONDS", "60");
			pb.environment().put("EARSHOT_CAPTURE_ORPHAN_SECONDS", "1800");
			Process started = pb.start();
			children.put(name, started);
			writePid(pidFile, started.pid());
			log.info("event=python_started process={} pid={}", name, started.pid());
		} catch (IOException e) {
			log.error("event=python_start_failed process={} reason={}", name, e.getMessage());
		}
	}

	/** The process listening on the URL's port, if it is ours (its command line names the module). */
	private static java.util.Optional<ProcessHandle> listener(String url, String module) {
		try {
			int port = java.net.URI.create(url).getPort();
			var p = new ProcessBuilder("lsof", "-ti", "tcp:" + port, "-sTCP:LISTEN").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes()).strip();
			if (out.isEmpty()) {
				return java.util.Optional.empty();
			}
			long pid = Long.parseLong(out.lines().findFirst().orElseThrow());
			// ProcessHandle.info() hides other processes' arguments on macOS; ps does not.
			var ps = new ProcessBuilder("ps", "-o", "command=", "-p", String.valueOf(pid)).start();
			String cmd = new String(ps.getInputStream().readAllBytes());
			return cmd.contains(module) ? ProcessHandle.of(pid) : java.util.Optional.empty();
		} catch (IOException | RuntimeException e) {
			return java.util.Optional.empty();
		}
	}

	private static void writePid(Path file, long pid) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, String.valueOf(pid));
		} catch (IOException e) {
			log.warn("event=pid_write_failed file={}", file);
		}
	}

	private static java.util.Optional<ProcessHandle> readPid(Path file) {
		try {
			return ProcessHandle.of(Long.parseLong(Files.readString(file).strip()));
		} catch (IOException | NumberFormatException e) {
			return java.util.Optional.empty();
		}
	}

	private boolean isUp(String name) {
		try {
			if (name.equals("ml")) {
				ml.health();
			} else {
				ml.captureHealth();
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
	public void heartbeat() {
		if (!ready) {
			return;
		}
		try {
			ml.heartbeat();
		} catch (Exception e) {
			log.debug("event=heartbeat_failed reason={}", e.getMessage());
		}
		if (ready) {
			ensure("ml", "earshot_ml.app");
			ensure("capture", "earshot_ml.capture");
		}
	}

	@PreDestroy
	public synchronized void stop() {
		ready = false;
		// A capture process mid-meeting outlives us on purpose (orphan timeout).
		Process p = children.get("ml");
		if (p != null) {
			p.destroy();
			try {
				if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					p.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
