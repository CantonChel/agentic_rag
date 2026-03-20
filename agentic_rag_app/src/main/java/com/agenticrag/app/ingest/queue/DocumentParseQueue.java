package com.agenticrag.app.ingest.queue;

import java.time.Duration;
import java.time.Instant;

public interface DocumentParseQueue {
	void enqueue(String jobId);

	ReservedJob reserve(Duration timeout);

	void ack(ReservedJob job);

	void retry(ReservedJob job, Instant nextRetryAtEpochMillis);

	void deadLetter(ReservedJob job);

	int replayDueRetries(Instant now, int batchSize);
}
