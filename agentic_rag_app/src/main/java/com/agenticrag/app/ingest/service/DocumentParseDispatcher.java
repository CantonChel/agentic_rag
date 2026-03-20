package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.DocreaderProperties;
import com.agenticrag.app.ingest.config.IngestAsyncProperties;
import com.agenticrag.app.ingest.docreader.DocreaderClient;
import com.agenticrag.app.ingest.docreader.DocreaderJobSubmitRequest;
import com.agenticrag.app.ingest.docreader.DocreaderJobSubmitResponse;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.model.JobFailureAction;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentParseDispatcher {
	private static final Logger log = LoggerFactory.getLogger(DocumentParseDispatcher.class);

	private final ParseJobService parseJobService;
	private final KnowledgeRepository knowledgeRepository;
	private final DocreaderClient docreaderClient;
	private final DocreaderProperties docreaderProperties;
	private final IngestAsyncProperties asyncProperties;
	private final FailureClassifier failureClassifier;
	private final DocumentParseQueue queue;

	public DocumentParseDispatcher(
		ParseJobService parseJobService,
		KnowledgeRepository knowledgeRepository,
		DocreaderClient docreaderClient,
		DocreaderProperties docreaderProperties,
		IngestAsyncProperties asyncProperties,
		FailureClassifier failureClassifier,
		DocumentParseQueue queue
	) {
		this.parseJobService = parseJobService;
		this.knowledgeRepository = knowledgeRepository;
		this.docreaderClient = docreaderClient;
		this.docreaderProperties = docreaderProperties;
		this.asyncProperties = asyncProperties;
		this.failureClassifier = failureClassifier;
		this.queue = queue;
	}

	public void dispatch(ReservedJob reservedJob) {
		if (reservedJob == null || reservedJob.getJobId() == null || reservedJob.getJobId().trim().isEmpty()) {
			return;
		}
		String jobId = reservedJob.getJobId().trim();
		Instant leaseUntil = Instant.now().plusSeconds(asyncProperties.getLeaseSeconds());

		boolean claimed = parseJobService.tryMarkDispatched(jobId, leaseUntil);
		if (!claimed) {
			queue.ack(reservedJob);
			return;
		}

		Optional<com.agenticrag.app.ingest.entity.ParseJobEntity> jobOpt = parseJobService.findById(jobId);
		if (!jobOpt.isPresent()) {
			queue.ack(reservedJob);
			return;
		}

		KnowledgeEntity knowledge = knowledgeRepository.findById(jobOpt.get().getKnowledgeId()).orElse(null);
		if (knowledge == null) {
			JobFailureAction action = parseJobService.registerFailure(jobId, "knowledge_not_found", "knowledge not found", false);
			applyFailureQueueAction(reservedJob, action);
			return;
		}

		try {
			DocreaderJobSubmitRequest request = new DocreaderJobSubmitRequest();
			request.setJobId(jobId);
			request.setKnowledgeId(knowledge.getId());
			request.setFileUrl(knowledge.getFilePath());
			request.setCallbackUrl(docreaderProperties.getCallbackBaseUrl() + "/internal/docreader/jobs/" + jobId + "/result");
			request.setPipelineVersion(jobOpt.get().getPipelineVersion());
			Map<String, Object> options = new HashMap<>();
			options.put("file_type", knowledge.getFileType());
			options.put("knowledge_base_id", knowledge.getKnowledgeBaseId());
			request.setOptions(options);

			DocreaderJobSubmitResponse response = docreaderClient.submitJob(request);
			if (response == null || !response.isAccepted()) {
				JobFailureAction action = parseJobService.registerFailure(jobId, "service_unavailable", "docreader rejected request", true);
				applyFailureQueueAction(reservedJob, action);
				return;
			}

			parseJobService.markParsing(jobId, leaseUntil);
			queue.ack(reservedJob);
		} catch (Exception e) {
			boolean retryable = failureClassifier.isRetryable("network_timeout", e);
			JobFailureAction action = parseJobService.registerFailure(jobId, "network_timeout", e.getMessage(), retryable);
			applyFailureQueueAction(reservedJob, action);
			log.warn("dispatch job failed: jobId={}, retryable={}, error={}", jobId, retryable, e.getMessage());
		}
	}

	private void applyFailureQueueAction(ReservedJob reservedJob, JobFailureAction action) {
		if (action == null || reservedJob == null) {
			return;
		}
		switch (action.getDecision()) {
			case RETRY:
				queue.retry(reservedJob, action.getNextRetryAt());
				break;
			case DEAD_LETTER:
				queue.deadLetter(reservedJob);
				break;
			case FAILED:
			default:
				queue.ack(reservedJob);
				break;
		}
	}
}
