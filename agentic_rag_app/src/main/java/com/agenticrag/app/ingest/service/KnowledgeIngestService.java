package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.IngestAsyncProperties;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.model.KnowledgeUploadResult;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import com.agenticrag.app.ingest.storage.LocalKnowledgeFileStorageService;
import com.agenticrag.app.ingest.storage.StoredFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeIngestService {
	private final KnowledgeRepository knowledgeRepository;
	private final ParseJobRepository parseJobRepository;
	private final LocalKnowledgeFileStorageService fileStorageService;
	private final DocumentParseQueue documentParseQueue;
	private final ParseJobService parseJobService;
	private final IngestAsyncProperties asyncProperties;
	private final ObjectMapper objectMapper;

	public KnowledgeIngestService(
		KnowledgeRepository knowledgeRepository,
		ParseJobRepository parseJobRepository,
		LocalKnowledgeFileStorageService fileStorageService,
		DocumentParseQueue documentParseQueue,
		ParseJobService parseJobService,
		IngestAsyncProperties asyncProperties,
		ObjectMapper objectMapper
	) {
		this.knowledgeRepository = knowledgeRepository;
		this.parseJobRepository = parseJobRepository;
		this.fileStorageService = fileStorageService;
		this.documentParseQueue = documentParseQueue;
		this.parseJobService = parseJobService;
		this.asyncProperties = asyncProperties;
		this.objectMapper = objectMapper;
	}

	public KnowledgeUploadResult createAndEnqueue(
		String knowledgeBaseId,
		String fileName,
		byte[] fileBytes,
		Map<String, Object> metadata
	) {
		CreateRecordResult records = createRecords(knowledgeBaseId, fileName, fileBytes, metadata);
		try {
			documentParseQueue.enqueue(records.getJobId());
		} catch (Exception e) {
			parseJobService.registerFailure(records.getJobId(), "queue_enqueue_failed", e.getMessage(), false);
		}
		return new KnowledgeUploadResult(records.getKnowledgeId(), records.getJobId(), ParseJobStatus.QUEUED);
	}

	@Transactional
	public CreateRecordResult createRecords(
		String knowledgeBaseId,
		String fileName,
		byte[] fileBytes,
		Map<String, Object> metadata
	) {
		String kbId = normalize(knowledgeBaseId, "default");
		String knowledgeId = UUID.randomUUID().toString();
		StoredFile storedFile = fileStorageService.store(knowledgeId, fileName, fileBytes);
		String idempotencyKey = knowledgeId + ":" + storedFile.getFileHash() + ":" + asyncProperties.getPipelineVersion();

		KnowledgeEntity knowledge = new KnowledgeEntity();
		knowledge.setId(knowledgeId);
		knowledge.setKnowledgeBaseId(kbId);
		knowledge.setFileName(normalize(fileName, "upload.bin"));
		knowledge.setFileType(fileType(fileName));
		knowledge.setFileSize(storedFile.getFileSize());
		knowledge.setFileHash(storedFile.getFileHash());
		knowledge.setFilePath(storedFile.getFilePath());
		knowledge.setParseStatus(KnowledgeParseStatus.QUEUED);
		knowledge.setEnableStatus(KnowledgeEnableStatus.DISABLED);
		knowledge.setMetadataJson(toJson(metadata));
		knowledge.setCreatedAt(Instant.now());
		knowledge.setUpdatedAt(Instant.now());
		knowledgeRepository.save(knowledge);

		ParseJobEntity job = new ParseJobEntity();
		job.setId(UUID.randomUUID().toString());
		job.setKnowledgeId(knowledgeId);
		job.setStatus(ParseJobStatus.QUEUED);
		job.setRetryCount(0);
		job.setMaxRetry(asyncProperties.getMaxRetry());
		job.setNextRetryAt(null);
		job.setLeaseUntil(null);
		job.setLastErrorCode(null);
		job.setLastErrorMessage(null);
		job.setPipelineVersion(asyncProperties.getPipelineVersion());
		job.setIdempotencyKey(idempotencyKey);
		job.setCreatedAt(Instant.now());
		job.setUpdatedAt(Instant.now());
		try {
			parseJobRepository.save(job);
		} catch (DataIntegrityViolationException e) {
			Optional<ParseJobEntity> existing = parseJobRepository.findByIdempotencyKey(idempotencyKey);
			if (existing.isPresent()) {
				return new CreateRecordResult(knowledgeId, existing.get().getId());
			}
			throw e;
		}

		return new CreateRecordResult(knowledgeId, job.getId());
	}

	@Transactional(readOnly = true)
	public Optional<ParseJobEntity> getJob(String jobId) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return Optional.empty();
		}
		return parseJobRepository.findById(jobId.trim());
	}

	public boolean retryJob(String jobId) {
		boolean ok = parseJobService.manualRetry(jobId);
		if (ok) {
			documentParseQueue.enqueue(jobId);
		}
		return ok;
	}

	private String toJson(Map<String, Object> metadata) {
		Map<String, Object> safe = new HashMap<>();
		if (metadata != null) {
			safe.putAll(metadata);
		}
		try {
			return objectMapper.writeValueAsString(safe);
		} catch (Exception e) {
			return "{}";
		}
	}

	private String normalize(String value, String fallback) {
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		return value.trim();
	}

	private String fileType(String fileName) {
		String n = normalize(fileName, "upload.bin");
		int idx = n.lastIndexOf('.');
		if (idx <= 0 || idx >= n.length() - 1) {
			return "bin";
		}
		return n.substring(idx + 1).toLowerCase();
	}

	public static class CreateRecordResult {
		private final String knowledgeId;
		private final String jobId;

		public CreateRecordResult(String knowledgeId, String jobId) {
			this.knowledgeId = knowledgeId;
			this.jobId = jobId;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public String getJobId() {
			return jobId;
		}
	}
}
