package com.earshot.api.job;

import java.util.Map;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.earshot.api.llm.LlmClient;

/**
 * One ML job at a time (PRD §8). While a meeting is live, batch jobs wait; a running one stops
 * at its next stage checkpoint because each handler checks {@link #mustPause()}.
 */
@Component
public class JobWorker {

	private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
	private static final int MAX_ATTEMPTS = 5;

	public interface Handler {
		String type();

		void run(Jobs.Job job, JobWorker worker) throws Exception;
	}

	/** Thrown by a handler at a checkpoint to put the job back in the queue. */
	public static class Paused extends RuntimeException {
		public Paused(String reason) {
			super(reason);
		}
	}

	private final Jobs jobs;
	// Resolved at start: handlers depend on services that depend on this worker.
	private final org.springframework.beans.factory.ObjectProvider<Handler> handlerProvider;
	private Map<String, Handler> handlers = Map.of();
	private volatile BooleanSupplier liveMeeting = () -> false;
	private final Object wake = new Object();
	private volatile boolean running = true;

	public JobWorker(Jobs jobs, org.springframework.beans.factory.ObjectProvider<Handler> handlers) {
		this.jobs = jobs;
		this.handlerProvider = handlers;
	}

	public void setLiveMeeting(BooleanSupplier live) {
		this.liveMeeting = live;
	}

	/**
	 * Jobs the user started by clicking during a live meeting (type ending "_now") run anyway: "the
	 * user knows why" (owner, 2026-10-24). Everything else waits for the meeting and gives way to it.
	 */
	private static final ThreadLocal<Boolean> CLICKED_DURING_LIVE = ThreadLocal.withInitial(() -> false);

	public static boolean clickedDuringLive() {
		return CLICKED_DURING_LIVE.get();
	}

	public boolean mustPause() {
		return liveMeeting.getAsBoolean() && !CLICKED_DURING_LIVE.get();
	}

	public void checkpoint() {
		if (mustPause()) {
			throw new Paused("paused for a live meeting");
		}
	}

	public void wake() {
		synchronized (wake) {
			wake.notifyAll();
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		handlers = handlerProvider.orderedStream().collect(java.util.stream.Collectors.toMap(Handler::type, h -> h));
		int n = jobs.recoverInterrupted();
		if (n > 0) {
			log.info("event=jobs_recovered count={}", n);
		}
		Thread.ofVirtual().name("job-worker").start(this::loop);
	}

	private void loop() {
		while (running) {
			try {
				var job = (mustPause() ? jobs.claimClickedDuringLive() : jobs.claim()).orElse(null);
				if (job == null) {
					synchronized (wake) {
						wake.wait(2_000);
					}
					continue;
				}
				run(job);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("event=job_loop_error reason={}", e.toString());
			}
		}
	}

	private void run(Jobs.Job job) {
		Handler h = handlers.get(job.type());
		long t = System.nanoTime();
		try {
			if (h == null) {
				throw new IllegalStateException("no handler for " + job.type());
			}
			CLICKED_DURING_LIVE.set(job.type().endsWith("_now"));
			h.run(job, this);
			jobs.finish(job.id());
			log.info("event=job_done id={} type={} took_s={}", job.id(), job.type(), (System.nanoTime() - t) / 1_000_000_000);
		} catch (Paused p) {
			// A meeting started: batch work waits for it to end.
			jobs.requeue(job.id(), p.getMessage());
			log.info("event=job_paused id={} type={}", job.id(), job.type());
		} catch (LlmClient.LlmUnavailable e) {
			// An AI provider being down never fails a job permanently (PRD §8): retry later.
			jobs.requeue(job.id(), "AI provider unavailable: " + e.getMessage());
			log.warn("event=job_waiting_for_llm id={} type={}", job.id(), job.type());
			sleepQuietly(15_000);
		} catch (Exception e) {
			log.error("event=job_failed id={} type={} attempt={} reason={}", job.id(), job.type(), job.attempts(), e.toString(), e);
			if (job.attempts() >= MAX_ATTEMPTS) {
				jobs.fail(job.id(), e.toString());
			} else {
				jobs.requeue(job.id(), e.toString());
				sleepQuietly(3_000);
			}
		} finally {
			CLICKED_DURING_LIVE.set(false);
		}
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
