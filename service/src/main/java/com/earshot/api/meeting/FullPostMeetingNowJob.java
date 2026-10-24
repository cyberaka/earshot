package com.earshot.api.meeting;

import org.springframework.stereotype.Component;

import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;

/**
 * Re-transcribe clicked while another meeting is live: runs at once, alongside that meeting, because
 * the user asked for it then (owner, 2026-10-24). The UI warns that it slows the live transcript.
 */
@Component
public class FullPostMeetingNowJob implements JobWorker.Handler {

	private final PostMeetingJob delegate;

	public FullPostMeetingNowJob(PostMeetingJob delegate) {
		this.delegate = delegate;
	}

	@Override
	public String type() {
		return "post_meeting_full_now";
	}

	@Override
	public void run(Jobs.Job job, JobWorker worker) throws Exception {
		delegate.run(job, worker);
	}
}
