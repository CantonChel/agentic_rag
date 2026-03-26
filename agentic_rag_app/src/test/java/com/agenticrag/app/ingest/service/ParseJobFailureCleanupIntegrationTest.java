package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.entity.CallbackEventEntity;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.entity.KnowledgeBaseEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.model.JobFailureAction;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import com.agenticrag.app.ingest.repo.CallbackEventRepository;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ParseJobFailureCleanupIntegrationTest {
	@Autowired
	private ParseJobService parseJobService;

	@Autowired
	private KnowledgeCrudService knowledgeCrudService;

	@Autowired
	private KnowledgeBaseRepository knowledgeBaseRepository;

	@Autowired
	private KnowledgeRepository knowledgeRepository;

	@Autowired
	private ChunkRepository chunkRepository;

	@Autowired
	private EmbeddingRepository embeddingRepository;

	@Autowired
	private ParseJobRepository parseJobRepository;

	@Autowired
	private CallbackEventRepository callbackEventRepository;

	@BeforeEach
	void setUp() {
		callbackEventRepository.deleteAll();
		parseJobRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();
	}

	@Test
	void nonRetryableFailureShouldKeepFailureStateForRetryAndCleanup() {
		knowledgeBaseRepository.save(base("kb-failed"));
		knowledgeRepository.save(doc("doc-failed", "kb-failed", "failed.txt", KnowledgeParseStatus.PARSING));
		chunkRepository.save(chunk("doc-failed", "c-failed-1", "chunk-failed"));
		embeddingRepository.save(embedding("doc-failed", "c-failed-1", "chunk-failed"));
		parseJobRepository.save(job("job-failed", "doc-failed", ParseJobStatus.PARSING, 0, 3));
		callbackEventRepository.save(event("evt-failed", "job-failed"));

		JobFailureAction action = parseJobService.registerFailure(
			"job-failed",
			"unsupported_file",
			"unsupported file extension",
			false
		);

		Assertions.assertEquals(JobFailureAction.Decision.FAILED, action.getDecision());
		KnowledgeEntity knowledge = knowledgeRepository.findById("doc-failed").orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.FAILED, knowledge.getParseStatus());
		Assertions.assertEquals(KnowledgeEnableStatus.DISABLED, knowledge.getEnableStatus());

		ParseJobEntity job = parseJobRepository.findById("job-failed").orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.FAILED, job.getStatus());
		Assertions.assertEquals("unsupported_file", job.getLastErrorCode());

		Assertions.assertEquals(1L, chunkRepository.count());
		Assertions.assertEquals(1L, embeddingRepository.count());
		Assertions.assertEquals(1L, callbackEventRepository.count());
	}

	@Test
	void retryExhaustedShouldKeepDeadLetterStateForManualRecovery() {
		knowledgeBaseRepository.save(base("kb-dead"));
		knowledgeRepository.save(doc("doc-dead", "kb-dead", "dead.txt", KnowledgeParseStatus.PARSING));
		chunkRepository.save(chunk("doc-dead", "c-dead-1", "chunk-dead"));
		embeddingRepository.save(embedding("doc-dead", "c-dead-1", "chunk-dead"));
		parseJobRepository.save(job("job-dead", "doc-dead", ParseJobStatus.PARSING, 3, 3));
		callbackEventRepository.save(event("evt-dead", "job-dead"));

		JobFailureAction action = parseJobService.registerFailure(
			"job-dead",
			"network_timeout",
			"max retry reached",
			true
		);

		Assertions.assertEquals(JobFailureAction.Decision.DEAD_LETTER, action.getDecision());
		KnowledgeEntity knowledge = knowledgeRepository.findById("doc-dead").orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.FAILED, knowledge.getParseStatus());

		ParseJobEntity job = parseJobRepository.findById("job-dead").orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.DEAD_LETTER, job.getStatus());
		Assertions.assertEquals("network_timeout", job.getLastErrorCode());

		Assertions.assertEquals(1L, chunkRepository.count());
		Assertions.assertEquals(1L, embeddingRepository.count());
		Assertions.assertEquals(1L, callbackEventRepository.count());
	}

	@Test
	void retryableFailureShouldMoveToRetryWaitWithoutDeletingData() {
		knowledgeBaseRepository.save(base("kb-retry"));
		knowledgeRepository.save(doc("doc-retry", "kb-retry", "retry.txt", KnowledgeParseStatus.PARSING));
		chunkRepository.save(chunk("doc-retry", "c-retry-1", "chunk-retry"));
		embeddingRepository.save(embedding("doc-retry", "c-retry-1", "chunk-retry"));
		parseJobRepository.save(job("job-retry", "doc-retry", ParseJobStatus.PARSING, 0, 3));
		callbackEventRepository.save(event("evt-retry", "job-retry"));

		JobFailureAction action = parseJobService.registerFailure(
			"job-retry",
			"network_timeout",
			"temporary timeout",
			true
		);

		Assertions.assertEquals(JobFailureAction.Decision.RETRY, action.getDecision());
		Assertions.assertNotNull(action.getNextRetryAt());

		ParseJobEntity job = parseJobRepository.findById("job-retry").orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.RETRY_WAIT, job.getStatus());
		Assertions.assertEquals(1, job.getRetryCount());

		KnowledgeEntity knowledge = knowledgeRepository.findById("doc-retry").orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.PARSING, knowledge.getParseStatus());
		Assertions.assertEquals(KnowledgeEnableStatus.DISABLED, knowledge.getEnableStatus());

		Assertions.assertEquals(1L, chunkRepository.count());
		Assertions.assertEquals(1L, embeddingRepository.count());
		Assertions.assertEquals(1L, callbackEventRepository.count());
	}

	@Test
	void residualFailureCleanupShouldSkipTrackedFailedJobsAndDeleteOnlyOrphans() {
		knowledgeBaseRepository.save(base("kb-clean"));
		knowledgeRepository.save(doc("doc-tracked", "kb-clean", "tracked.txt", KnowledgeParseStatus.FAILED));
		knowledgeRepository.save(doc("doc-orphan", "kb-clean", "orphan.txt", KnowledgeParseStatus.FAILED));
		parseJobRepository.save(job("job-tracked", "doc-tracked", ParseJobStatus.FAILED, 1, 3));
		chunkRepository.save(chunk("doc-tracked", "c-tracked-1", "tracked"));
		chunkRepository.save(chunk("doc-orphan", "c-orphan-1", "orphan"));
		embeddingRepository.save(embedding("doc-tracked", "c-tracked-1", "tracked"));
		embeddingRepository.save(embedding("doc-orphan", "c-orphan-1", "orphan"));

		long cleaned = knowledgeCrudService.cleanupResidualFailedKnowledgeDocuments(100);

		Assertions.assertEquals(1L, cleaned);
		Assertions.assertTrue(knowledgeRepository.findById("doc-tracked").isPresent());
		Assertions.assertFalse(knowledgeRepository.findById("doc-orphan").isPresent());
		Assertions.assertTrue(parseJobRepository.findById("job-tracked").isPresent());
		Assertions.assertEquals(1L, chunkRepository.count());
		Assertions.assertEquals(1L, embeddingRepository.count());
	}

	private KnowledgeBaseEntity base(String id) {
		KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
		kb.setId(id);
		kb.setName(id);
		kb.setDescription(null);
		kb.setEnabled(true);
		kb.setCreatedAt(Instant.now());
		kb.setUpdatedAt(Instant.now());
		return kb;
	}

	private KnowledgeEntity doc(String id, String kbId, String filename, KnowledgeParseStatus parseStatus) {
		KnowledgeEntity k = new KnowledgeEntity();
		k.setId(id);
		k.setKnowledgeBaseId(kbId);
		k.setFileName(filename);
		k.setFileType("txt");
		k.setFileSize(10L);
		k.setFileHash("hash-" + id);
		k.setFilePath("/tmp/" + filename);
		k.setParseStatus(parseStatus);
		k.setEnableStatus(KnowledgeEnableStatus.DISABLED);
		k.setMetadataJson("{}");
		k.setCreatedAt(Instant.now());
		k.setUpdatedAt(Instant.now());
		return k;
	}

	private ChunkEntity chunk(String knowledgeId, String chunkId, String content) {
		ChunkEntity c = new ChunkEntity();
		c.setChunkId(chunkId);
		c.setKnowledgeId(knowledgeId);
		c.setChunkType(ChunkType.TEXT);
		c.setChunkIndex(0);
		c.setContent(content);
		c.setCreatedAt(Instant.now());
		c.setUpdatedAt(Instant.now());
		return c;
	}

	private EmbeddingEntity embedding(String knowledgeId, String chunkId, String content) {
		EmbeddingEntity e = new EmbeddingEntity();
		e.setKnowledgeId(knowledgeId);
		e.setChunkId(chunkId);
		e.setModelName("test-model");
		e.setDimension(2);
		e.setVectorJson("[0.1,0.2]");
		e.setContent(content);
		e.setEnabled(true);
		e.setCreatedAt(Instant.now());
		e.setUpdatedAt(Instant.now());
		return e;
	}

	private ParseJobEntity job(String id, String knowledgeId, ParseJobStatus status, int retryCount, int maxRetry) {
		ParseJobEntity j = new ParseJobEntity();
		j.setId(id);
		j.setKnowledgeId(knowledgeId);
		j.setStatus(status);
		j.setRetryCount(retryCount);
		j.setMaxRetry(maxRetry);
		j.setPipelineVersion("v1");
		j.setIdempotencyKey(id + ":idem");
		j.setCreatedAt(Instant.now());
		j.setUpdatedAt(Instant.now());
		return j;
	}

	private CallbackEventEntity event(String eventId, String jobId) {
		CallbackEventEntity e = new CallbackEventEntity();
		e.setEventId(eventId);
		e.setJobId(jobId);
		e.setPayloadHash("hash-" + eventId);
		e.setCreatedAt(Instant.now());
		return e;
	}
}
