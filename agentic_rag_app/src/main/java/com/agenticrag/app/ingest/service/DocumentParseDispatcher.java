package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.DocreaderProperties;
import com.agenticrag.app.ingest.config.IngestAsyncProperties;
import com.agenticrag.app.ingest.docreader.DocreaderClient;
import com.agenticrag.app.ingest.docreader.DocreaderReadRequest;
import com.agenticrag.app.ingest.docreader.DocreaderReadResponse;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.JobFailureAction;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.storage.KnowledgeFileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;

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
	private final KnowledgeFileStorageService fileStorageService;
	private final ObjectMapper objectMapper;
	private final DocreaderReadResultService docreaderReadResultService;

	public DocumentParseDispatcher(
		ParseJobService parseJobService,
		KnowledgeRepository knowledgeRepository,
		DocreaderClient docreaderClient,
		DocreaderProperties docreaderProperties,
		IngestAsyncProperties asyncProperties,
		FailureClassifier failureClassifier,
		DocumentParseQueue queue,
		KnowledgeFileStorageService fileStorageService,
		ObjectMapper objectMapper,
		DocreaderReadResultService docreaderReadResultService
	) {
		this.parseJobService = parseJobService;
		this.knowledgeRepository = knowledgeRepository;
		this.docreaderClient = docreaderClient;
		this.docreaderProperties = docreaderProperties;
		this.asyncProperties = asyncProperties;
		this.failureClassifier = failureClassifier;
		this.queue = queue;
		this.fileStorageService = fileStorageService;
		this.objectMapper = objectMapper;
		this.docreaderReadResultService = docreaderReadResultService;
	}

	public void dispatch(ReservedJob reservedJob) {
		if (reservedJob == null || reservedJob.getJobId() == null || reservedJob.getJobId().trim().isEmpty()) {
			return;
		}
		String jobId = reservedJob.getJobId().trim();
		Instant leaseUntil = Instant.now().plusSeconds(resolveLeaseSeconds());

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
			DocreaderReadRequest request = new DocreaderReadRequest();
			request.setJobId(jobId);
			request.setKnowledgeId(knowledge.getId());
			request.setFileUrl(fileStorageService.resolveReadUrl(knowledge.getFilePath()));
			request.setPipelineVersion(jobOpt.get().getPipelineVersion());
			Map<String, Object> options = new HashMap<>();
			options.put("file_type", knowledge.getFileType());
			options.put("knowledge_base_id", knowledge.getKnowledgeBaseId());
			options.put("knowledge_id", knowledge.getId());
			options.put("user_id", extractUserId(knowledge.getMetadataJson()));
			request.setOptions(options);

			parseJobService.markParsing(jobId, leaseUntil);
			DocreaderReadResponse response = docreaderClient.readDocument(request);
			String error = response != null ? response.getError() : null;
			if (error != null && !error.trim().isEmpty()) {
				String errorCode = parseErrorCode(error);
				String errorMessage = parseErrorMessage(error);
				boolean retryable = failureClassifier.isRetryable(errorCode, null);
				JobFailureAction action = parseJobService.registerFailure(jobId, errorCode, errorMessage, retryable);
				applyFailureQueueAction(reservedJob, action);
				log.warn("docreader read failed: jobId={}, retryable={}, errorCode={}, error={}", jobId, retryable, errorCode, errorMessage);
				return;
			}

			boolean indexingMarked = parseJobService.markIndexing(jobId);
			if (!indexingMarked) {
				ParseJobEntity latest = parseJobService.findById(jobId).orElse(null);
				if (latest == null) {
					queue.ack(reservedJob);
					log.warn("sync docreader skip indexing because job disappeared: jobId={}", jobId);
					return;
				}
				ParseJobStatus latestStatus = latest.getStatus();
				if (latestStatus != ParseJobStatus.INDEXING
					&& latestStatus != ParseJobStatus.PARSING
					&& latestStatus != ParseJobStatus.DISPATCHED) {
					queue.ack(reservedJob);
					log.info("sync docreader skip due to terminal status: jobId={}, status={}", jobId, latestStatus);
					return;
				}
			}
			DocreaderReadResultService.ProcessResult result = docreaderReadResultService.process(
				knowledge.getId(),
				extractUserId(knowledge.getMetadataJson()),
				response
			);
			parseJobService.markSuccess(jobId);
			queue.ack(reservedJob);
			log.info("sync docreader processed success: jobId={}, chunks={}, embeddings={}", jobId, result.getChunks(), result.getEmbeddings());
		} catch (Exception e) {
			DispatchFailure failure = classifyDispatchFailure(e);
			JobFailureAction action = parseJobService.registerFailure(jobId, failure.errorCode, failure.errorMessage, failure.retryable);
			applyFailureQueueAction(reservedJob, action);
			log.warn("dispatch job failed: jobId={}, retryable={}, errorCode={}, error={}", jobId, failure.retryable, failure.errorCode, failure.errorMessage);
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

	private String extractUserId(String metadataJson) {
		if (metadataJson == null || metadataJson.trim().isEmpty()) {
			return "anonymous";
		}
		try {
			JsonNode root = objectMapper.readTree(metadataJson);
			String v = text(root, "user_id");
			if (v == null) {
				v = text(root, "userId");
			}
			if (v == null) {
				v = text(root, "uid");
			}
			if (v == null || v.trim().isEmpty()) {
				return "anonymous";
			}
			return v.replaceAll("[^a-zA-Z0-9._-]", "_");
		} catch (Exception ignored) {
			return "anonymous";
		}
	}

	private long resolveLeaseSeconds() {
		long baseLeaseSeconds = Math.max(1L, asyncProperties.getLeaseSeconds());
		long docreaderReadSeconds = Math.max(1L, docreaderProperties.getReadTimeoutMillis() / 1000L);
		return Math.max(baseLeaseSeconds, docreaderReadSeconds + 120L);
	}

	private String parseErrorCode(String error) {
		String safe = error != null ? error.trim() : "";
		if (safe.isEmpty()) {
			return "service_unavailable";
		}
		int idx = safe.indexOf(':');
		String code = idx > 0 ? safe.substring(0, idx).trim() : safe;
		return code.isEmpty() ? "service_unavailable" : code.toLowerCase(Locale.ROOT);
	}

	private String parseErrorMessage(String error) {
		String safe = error != null ? error.trim() : "";
		int idx = safe.indexOf(':');
		if (idx < 0 || idx >= safe.length() - 1) {
			return safe;
		}
		return safe.substring(idx + 1).trim();
	}

	private String text(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String out = value.asText();
		return out != null && !out.trim().isEmpty() ? out : null;
	}

	private DispatchFailure classifyDispatchFailure(Exception error) {
		if (hasCause(error, DataBufferLimitException.class) || containsMessage(error, "Exceeded limit on max bytes to buffer")) {
			return new DispatchFailure(
				"response_too_large",
				"docreader response exceeded in-memory limit of " + docreaderProperties.getMaxInMemorySizeBytes() + " bytes",
				false
			);
		}
		if (containsMessage(error, "invalid byte sequence for encoding")
			|| containsMessage(error, "invalid byte sequence for encoding \"utf8\"")
			|| containsMessage(error, "0x00")) {
			return new DispatchFailure("invalid_text_data", "parsed content contains unsupported NUL characters", false);
		}

		String errorCode = "service_unavailable";
		String errorMessage = mostSpecificMessage(error);
		if (hasCause(error, java.util.concurrent.TimeoutException.class) || containsMessage(error, "timeout")) {
			errorCode = "network_timeout";
		} else if (hasCause(error, WebClientRequestException.class) || containsMessage(error, "connection")) {
			errorCode = "service_unavailable";
		}
		return new DispatchFailure(errorCode, errorMessage, failureClassifier.isRetryable(errorCode, error));
	}

	private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private boolean containsMessage(Throwable error, String needle) {
		if (needle == null || needle.trim().isEmpty()) {
			return false;
		}
		String target = needle.toLowerCase(Locale.ROOT);
		Throwable current = error;
		while (current != null) {
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(target)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private String mostSpecificMessage(Throwable error) {
		String fallback = error != null && error.getMessage() != null ? error.getMessage() : "unexpected dispatch failure";
		Throwable current = error;
		String best = fallback;
		while (current != null) {
			String message = current.getMessage();
			if (message != null && !message.trim().isEmpty()) {
				best = message.trim();
			}
			current = current.getCause();
		}
		return best;
	}

	private static class DispatchFailure {
		private final String errorCode;
		private final String errorMessage;
		private final boolean retryable;

		private DispatchFailure(String errorCode, String errorMessage, boolean retryable) {
			this.errorCode = errorCode;
			this.errorMessage = errorMessage;
			this.retryable = retryable;
		}
	}
}
