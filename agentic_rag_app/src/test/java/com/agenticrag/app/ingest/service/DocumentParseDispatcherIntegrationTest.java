package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.docreader.DocreaderClient;
import com.agenticrag.app.ingest.docreader.DocreaderReadResponse;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import com.agenticrag.app.ingest.model.KnowledgeUploadResult;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.ingest.repo.CallbackEventRepository;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentParseDispatcherIntegrationTest {
	@Autowired
	private DocumentParseDispatcher documentParseDispatcher;

	@Autowired
	private KnowledgeIngestService knowledgeIngestService;

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

	@MockBean
	private DocumentParseQueue documentParseQueue;

	@MockBean
	private DocreaderClient docreaderClient;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() {
		callbackEventRepository.deleteAll();
		parseJobRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();
		Mockito.reset(documentParseQueue, docreaderClient, embeddingModel);
	}

	@Test
	void unsupportedFileShouldRemainVisibleAsFailed() {
		KnowledgeUploadResult uploadResult = knowledgeIngestService.createAndEnqueue(
			"kb-unsupported",
			"bad.xyz",
			"bad-file".getBytes(StandardCharsets.UTF_8),
			Collections.singletonMap("user_id", "test-user")
		);

		DocreaderReadResponse response = new DocreaderReadResponse();
		response.setError("unsupported_file: file extension not supported");
		Mockito.when(docreaderClient.readDocument(ArgumentMatchers.any())).thenReturn(response);

		documentParseDispatcher.dispatch(new ReservedJob(uploadResult.getJobId(), null));

		KnowledgeEntity knowledge = knowledgeRepository.findById(uploadResult.getKnowledgeId()).orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.FAILED, knowledge.getParseStatus());

		ParseJobEntity job = parseJobRepository.findById(uploadResult.getJobId()).orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.FAILED, job.getStatus());
		Assertions.assertEquals("unsupported_file", job.getLastErrorCode());

		Assertions.assertEquals(0L, chunkRepository.count());
		Assertions.assertEquals(0L, embeddingRepository.count());
		Mockito.verify(documentParseQueue, Mockito.atLeastOnce()).ack(ArgumentMatchers.any(ReservedJob.class));
	}

	@Test
	void networkTimeoutShouldMoveJobToRetryWait() {
		KnowledgeUploadResult uploadResult = knowledgeIngestService.createAndEnqueue(
			"kb-retry",
			"retry.txt",
			"retry-file".getBytes(StandardCharsets.UTF_8),
			Collections.singletonMap("user_id", "test-user")
		);

		Mockito.when(docreaderClient.readDocument(ArgumentMatchers.any()))
			.thenThrow(new RuntimeException("connection timeout"));

		documentParseDispatcher.dispatch(new ReservedJob(uploadResult.getJobId(), null));

		ParseJobEntity job = parseJobRepository.findById(uploadResult.getJobId()).orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.RETRY_WAIT, job.getStatus());
		Assertions.assertEquals(1, job.getRetryCount());
		Assertions.assertNotNull(job.getNextRetryAt());

		KnowledgeEntity knowledge = knowledgeRepository.findById(uploadResult.getKnowledgeId()).orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.PARSING, knowledge.getParseStatus());
		Assertions.assertEquals(0L, chunkRepository.count());
		Assertions.assertEquals(0L, embeddingRepository.count());
		Mockito.verify(documentParseQueue, Mockito.atLeastOnce())
			.retry(ArgumentMatchers.any(ReservedJob.class), ArgumentMatchers.any());
	}

	@Test
	void responseTooLargeShouldFailWithoutRetrying() {
		KnowledgeUploadResult uploadResult = knowledgeIngestService.createAndEnqueue(
			"kb-large",
			"large.pdf",
			"large-file".getBytes(StandardCharsets.UTF_8),
			Collections.singletonMap("user_id", "test-user")
		);

		Mockito.when(docreaderClient.readDocument(ArgumentMatchers.any()))
			.thenThrow(new RuntimeException("200 OK from POST http://127.0.0.1:8090/read; nested exception is org.springframework.core.io.buffer.DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144"));

		documentParseDispatcher.dispatch(new ReservedJob(uploadResult.getJobId(), null));

		KnowledgeEntity knowledge = knowledgeRepository.findById(uploadResult.getKnowledgeId()).orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.FAILED, knowledge.getParseStatus());

		ParseJobEntity job = parseJobRepository.findById(uploadResult.getJobId()).orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.FAILED, job.getStatus());
		Assertions.assertEquals("response_too_large", job.getLastErrorCode());

		Mockito.verify(documentParseQueue, Mockito.never())
			.retry(ArgumentMatchers.any(ReservedJob.class), ArgumentMatchers.any());
		Mockito.verify(documentParseQueue, Mockito.atLeastOnce()).ack(ArgumentMatchers.any(ReservedJob.class));
	}

	@Test
	void nulCharactersShouldBeSanitizedBeforePersistence() {
		KnowledgeUploadResult uploadResult = knowledgeIngestService.createAndEnqueue(
			"kb-sanitize",
			"sanitize.txt",
			"sanitize-file".getBytes(StandardCharsets.UTF_8),
			Collections.singletonMap("user_id", "test-user")
		);

		DocreaderReadResponse response = new DocreaderReadResponse();
		response.setMarkdownContent("hello\u0000world");
		response.setMetadata(Collections.singletonMap("source", "nul\u0000source"));
		Mockito.when(docreaderClient.readDocument(ArgumentMatchers.any())).thenReturn(response);
		Mockito.when(embeddingModel.embedTexts(ArgumentMatchers.anyList()))
			.thenReturn(Collections.singletonList(Arrays.asList(0.1d, 0.2d)));

		documentParseDispatcher.dispatch(new ReservedJob(uploadResult.getJobId(), null));

		KnowledgeEntity knowledge = knowledgeRepository.findById(uploadResult.getKnowledgeId()).orElse(null);
		Assertions.assertNotNull(knowledge);
		Assertions.assertEquals(KnowledgeParseStatus.COMPLETED, knowledge.getParseStatus());

		ParseJobEntity job = parseJobRepository.findById(uploadResult.getJobId()).orElse(null);
		Assertions.assertNotNull(job);
		Assertions.assertEquals(ParseJobStatus.SUCCESS, job.getStatus());

		List<com.agenticrag.app.ingest.entity.ChunkEntity> chunks = chunkRepository.findByKnowledgeIdOrderByChunkIndexAsc(uploadResult.getKnowledgeId());
		Assertions.assertFalse(chunks.isEmpty());
		Assertions.assertFalse(chunks.get(0).getContent().contains("\u0000"));
		Assertions.assertFalse(chunks.get(0).getMetadataJson().contains("\u0000"));

		List<com.agenticrag.app.ingest.entity.EmbeddingEntity> embeddings = embeddingRepository.findAll();
		Assertions.assertFalse(embeddings.isEmpty());
		Assertions.assertFalse(embeddings.get(0).getContent().contains("\u0000"));
	}
}
