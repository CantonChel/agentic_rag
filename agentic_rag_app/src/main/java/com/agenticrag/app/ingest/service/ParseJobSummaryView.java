package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.entity.ParseJobEntity;
import java.util.Locale;

public class ParseJobSummaryView {
	private final String jobId;
	private final String status;
	private final int retryCount;
	private final int maxRetry;
	private final String nextRetryAt;
	private final String leaseUntil;
	private final String lastErrorCode;
	private final String lastErrorMessage;
	private final String updatedAt;

	public ParseJobSummaryView(
		String jobId,
		String status,
		int retryCount,
		int maxRetry,
		String nextRetryAt,
		String leaseUntil,
		String lastErrorCode,
		String lastErrorMessage,
		String updatedAt
	) {
		this.jobId = jobId;
		this.status = status;
		this.retryCount = retryCount;
		this.maxRetry = maxRetry;
		this.nextRetryAt = nextRetryAt;
		this.leaseUntil = leaseUntil;
		this.lastErrorCode = lastErrorCode;
		this.lastErrorMessage = lastErrorMessage;
		this.updatedAt = updatedAt;
	}

	public static ParseJobSummaryView from(ParseJobEntity job) {
		if (job == null) {
			return null;
		}
		return new ParseJobSummaryView(
			job.getId(),
			job.getStatus() != null ? job.getStatus().name().toLowerCase(Locale.ROOT) : "unknown",
			job.getRetryCount(),
			job.getMaxRetry(),
			job.getNextRetryAt() != null ? job.getNextRetryAt().toString() : null,
			job.getLeaseUntil() != null ? job.getLeaseUntil().toString() : null,
			job.getLastErrorCode(),
			job.getLastErrorMessage(),
			job.getUpdatedAt() != null ? job.getUpdatedAt().toString() : null
		);
	}

	public String getJobId() {
		return jobId;
	}

	public String getStatus() {
		return status;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public int getMaxRetry() {
		return maxRetry;
	}

	public String getNextRetryAt() {
		return nextRetryAt;
	}

	public String getLeaseUntil() {
		return leaseUntil;
	}

	public String getLastErrorCode() {
		return lastErrorCode;
	}

	public String getLastErrorMessage() {
		return lastErrorMessage;
	}

	public String getUpdatedAt() {
		return updatedAt;
	}
}
