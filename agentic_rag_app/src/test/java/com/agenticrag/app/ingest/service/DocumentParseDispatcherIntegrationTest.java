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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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

	@BeforeEach
	void setUp() {
		callbackEventRepository.deleteAll();
		parseJobRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();
		Mockito.reset(documentParseQueue, docreaderClient);
	}

	@Test
	void unsupportedFileShouldHardDeleteKnowledgeWithoutResidue() {
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

		Assertions.assertFalse(knowledgeRepository.findById(uploadResult.getKnowledgeId()).isPresent());
		Assertions.assertFalse(parseJobRepository.findById(uploadResult.getJobId()).isPresent());
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
}
