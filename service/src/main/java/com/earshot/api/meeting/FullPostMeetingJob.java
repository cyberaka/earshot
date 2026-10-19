package com.earshot.api.meeting;

import org.springframework.stereotype.Component;

import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;

/** The full re-run of a meeting (re-transcribe, re-diarize), only when asked for, never after Stop. */
@Component
public class FullPostMeetingJob implements JobWorker.Handler {

	private final PostMeetingJob delegate;

	public FullPostMeetingJob(PostMeetingJob delegate) {
		this.delegate = delegate;
	}

	@Override
	public String type() {
		return "post_meeting_full";
	}

	@Override
	public void run(Jobs.Job job, JobWorker worker) throws Exception {
		delegate.run(job, worker);
	}
}
