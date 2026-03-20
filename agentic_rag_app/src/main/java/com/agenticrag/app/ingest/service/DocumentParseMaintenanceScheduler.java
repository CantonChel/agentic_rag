package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.IngestAsyncProperties;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseMaintenanceScheduler {
	private static final Logger log = LoggerFactory.getLogger(DocumentParseMaintenanceScheduler.class);

	private final IngestAsyncProperties asyncProperties;
	private final DocumentParseQueue queue;
	private final ParseJobService parseJobService;

	public DocumentParseMaintenanceScheduler(
		IngestAsyncProperties asyncProperties,
		DocumentParseQueue queue,
		ParseJobService parseJobService
	) {
		this.asyncProperties = asyncProperties;
		this.queue = queue;
		this.parseJobService = parseJobService;
	}

	@Scheduled(fixedDelayString = "${ingest.async.retry-replay-interval-ms:3000}")
	public void replayRetries() {
		if (!asyncProperties.isEnabled()) {
			return;
		}
		try {
			queue.replayDueRetries(Instant.now(), asyncProperties.getRetryReplayBatchSize());
		} catch (Exception e) {
			log.warn("retry replay failed: {}", e.getMessage());
		}
	}

	@Scheduled(fixedDelayString = "${ingest.async.lease-recover-interval-ms:30000}")
	public void recoverLeases() {
		if (!asyncProperties.isEnabled()) {
			return;
		}
		try {
			List<String> recovered = parseJobService.recoverExpiredLeases(Instant.now());
			for (String jobId : recovered) {
				if (jobId == null || jobId.trim().isEmpty()) {
					continue;
				}
				queue.enqueue(jobId);
			}
		} catch (Exception e) {
			log.warn("lease recover failed: {}", e.getMessage());
		}
	}
}
