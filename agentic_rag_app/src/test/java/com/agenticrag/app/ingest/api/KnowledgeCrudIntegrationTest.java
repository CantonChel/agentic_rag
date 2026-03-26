package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.entity.CallbackEventEntity;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.entity.KnowledgeBaseEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.ChunkType;
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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
class KnowledgeCrudIntegrationTest {
	@Autowired
	private WebTestClient webTestClient;

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
	void knowledgeBaseCrudWorks() {
		Map<String, Object> createBody = new HashMap<>();
		createBody.put("knowledgeBaseId", "kb-crud");
		createBody.put("name", "知识库A");
		createBody.put("description", "desc");
		createBody.put("enabled", true);

		webTestClient.post()
			.uri("/api/knowledge-bases")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(createBody)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeBaseId").isEqualTo("kb-crud")
			.jsonPath("$.name").isEqualTo("知识库A")
			.jsonPath("$.enabled").isEqualTo(true);

		webTestClient.get()
			.uri("/api/knowledge-bases/{kbId}", "kb-crud")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeBaseId").isEqualTo("kb-crud")
			.jsonPath("$.description").isEqualTo("desc");

		Map<String, Object> updateBody = new HashMap<>();
		updateBody.put("name", "知识库A-更新");
		updateBody.put("description", "desc-2");
		updateBody.put("enabled", false);

		webTestClient.patch()
			.uri("/api/knowledge-bases/{kbId}", "kb-crud")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(updateBody)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.name").isEqualTo("知识库A-更新")
			.jsonPath("$.enabled").isEqualTo(false);
	}

	@Test
	void deleteKnowledgeDocumentShouldCascadeChunksEmbeddingsAndJobs() {
		knowledgeBaseRepository.save(base("kb-doc"));
		knowledgeRepository.save(doc("doc-1", "kb-doc", "a.txt"));
		chunkRepository.save(chunk("doc-1", "c-1", "chunk-1"));
		chunkRepository.save(chunk("doc-1", "c-2", "chunk-2"));
		embeddingRepository.save(embedding("doc-1", "c-1", "chunk-1"));
		embeddingRepository.save(embedding("doc-1", "c-2", "chunk-2"));
		parseJobRepository.save(job("job-1", "doc-1"));
		callbackEventRepository.save(event("evt-1", "job-1"));

		webTestClient.delete()
			.uri("/api/knowledge/{knowledgeId}", "doc-1")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeId").isEqualTo("doc-1")
			.jsonPath("$.deletedChunks").isEqualTo(2)
			.jsonPath("$.deletedEmbeddings").isEqualTo(2)
			.jsonPath("$.deletedParseJobs").isEqualTo(1)
			.jsonPath("$.deletedCallbackEvents").isEqualTo(1);

		webTestClient.get()
			.uri("/api/knowledge/{knowledgeId}", "doc-1")
			.exchange()
			.expectStatus().isNotFound();
	}

	@Test
	void deleteKnowledgeBaseShouldCascadeAllDocuments() {
		knowledgeBaseRepository.save(base("kb-cascade"));
		knowledgeRepository.save(doc("doc-a", "kb-cascade", "a.txt"));
		knowledgeRepository.save(doc("doc-b", "kb-cascade", "b.txt"));
		chunkRepository.save(chunk("doc-a", "ca-1", "a"));
		chunkRepository.save(chunk("doc-b", "cb-1", "b"));
		embeddingRepository.save(embedding("doc-a", "ca-1", "a"));
		embeddingRepository.save(embedding("doc-b", "cb-1", "b"));
		parseJobRepository.save(job("job-a", "doc-a"));
		parseJobRepository.save(job("job-b", "doc-b"));
		callbackEventRepository.save(event("evt-a", "job-a"));
		callbackEventRepository.save(event("evt-b", "job-b"));

		webTestClient.delete()
			.uri("/api/knowledge-bases/{kbId}", "kb-cascade")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeBaseId").isEqualTo("kb-cascade")
			.jsonPath("$.deletedDocuments").isEqualTo(2)
			.jsonPath("$.deletedChunks").isEqualTo(2)
			.jsonPath("$.deletedEmbeddings").isEqualTo(2)
			.jsonPath("$.deletedParseJobs").isEqualTo(2)
			.jsonPath("$.deletedCallbackEvents").isEqualTo(2);

		webTestClient.get()
			.uri("/api/knowledge-bases/{kbId}", "kb-cascade")
			.exchange()
			.expectStatus().isNotFound();
	}

	@Test
	void cleanupFailedKnowledgeShouldDeleteOnlyFailedDocumentsAndBeIdempotent() {
		knowledgeBaseRepository.save(base("kb-cleanup"));
		knowledgeRepository.save(doc("doc-failed", "kb-cleanup", "failed.txt", KnowledgeParseStatus.FAILED));
		knowledgeRepository.save(doc("doc-success", "kb-cleanup", "success.txt", KnowledgeParseStatus.COMPLETED));
		chunkRepository.save(chunk("doc-failed", "cf-1", "failed-chunk"));
		chunkRepository.save(chunk("doc-success", "cs-1", "success-chunk"));
		embeddingRepository.save(embedding("doc-failed", "cf-1", "failed-chunk"));
		embeddingRepository.save(embedding("doc-success", "cs-1", "success-chunk"));
		parseJobRepository.save(job("job-failed", "doc-failed"));
		parseJobRepository.save(job("job-success", "doc-success"));
		callbackEventRepository.save(event("evt-failed", "job-failed"));
		callbackEventRepository.save(event("evt-success", "job-success"));

		webTestClient.post()
			.uri("/api/knowledge-bases/{kbId}/cleanup-failed", "kb-cleanup")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeBaseId").isEqualTo("kb-cleanup")
			.jsonPath("$.scannedFailedDocuments").isEqualTo(1)
			.jsonPath("$.deletedDocuments").isEqualTo(1)
			.jsonPath("$.deletedParseJobs").isEqualTo(1)
			.jsonPath("$.deletedCallbackEvents").isEqualTo(1)
			.jsonPath("$.deletedChunks").isEqualTo(1)
			.jsonPath("$.deletedEmbeddings").isEqualTo(1);

		Assertions.assertFalse(knowledgeRepository.findById("doc-failed").isPresent());
		Assertions.assertTrue(knowledgeRepository.findById("doc-success").isPresent());
		Assertions.assertTrue(parseJobRepository.findById("job-success").isPresent());
		Assertions.assertEquals(1L, chunkRepository.count());
		Assertions.assertEquals(1L, embeddingRepository.count());
		Assertions.assertEquals(1L, callbackEventRepository.count());

		webTestClient.post()
			.uri("/api/knowledge-bases/{kbId}/cleanup-failed", "kb-cleanup")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.scannedFailedDocuments").isEqualTo(0)
			.jsonPath("$.deletedDocuments").isEqualTo(0)
			.jsonPath("$.deletedParseJobs").isEqualTo(0)
			.jsonPath("$.deletedCallbackEvents").isEqualTo(0)
			.jsonPath("$.deletedChunks").isEqualTo(0)
			.jsonPath("$.deletedEmbeddings").isEqualTo(0);
	}

	@Test
	void updateDocumentShouldMoveKnowledgeBaseAndUpdateMetadata() {
		knowledgeBaseRepository.save(base("kb-old"));
		knowledgeRepository.save(doc("doc-move", "kb-old", "before.txt"));

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("source", "ui");
		metadata.put("tag", "new");

		Map<String, Object> body = new HashMap<>();
		body.put("knowledgeBaseId", "kb-new");
		body.put("fileName", "after.txt");
		body.put("enableStatus", "disabled");
		body.put("metadata", metadata);

		webTestClient.patch()
			.uri("/api/knowledge/{knowledgeId}", "doc-move")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeBaseId").isEqualTo("kb-new")
			.jsonPath("$.fileName").isEqualTo("after.txt")
			.jsonPath("$.enableStatus").isEqualTo("disabled")
			.jsonPath("$.metadata.source").isEqualTo("ui")
			.jsonPath("$.metadata.tag").isEqualTo("new");

		webTestClient.get()
			.uri("/api/knowledge-bases/{kbId}", "kb-new")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.knowledgeBaseId").isEqualTo("kb-new")
			.jsonPath("$.documentCount").isEqualTo(1);
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

	private KnowledgeEntity doc(String id, String kbId, String filename) {
		return doc(id, kbId, filename, KnowledgeParseStatus.COMPLETED);
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
		k.setEnableStatus(parseStatus == KnowledgeParseStatus.COMPLETED ? KnowledgeEnableStatus.ENABLED : KnowledgeEnableStatus.DISABLED);
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

	private ParseJobEntity job(String id, String knowledgeId) {
		ParseJobEntity j = new ParseJobEntity();
		j.setId(id);
		j.setKnowledgeId(knowledgeId);
		j.setStatus(ParseJobStatus.SUCCESS);
		j.setRetryCount(0);
		j.setMaxRetry(3);
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
