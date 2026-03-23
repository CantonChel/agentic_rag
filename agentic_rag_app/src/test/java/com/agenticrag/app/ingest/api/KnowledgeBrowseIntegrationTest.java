package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
class KnowledgeBrowseIntegrationTest {
	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private KnowledgeRepository knowledgeRepository;

	@Autowired
	private ChunkRepository chunkRepository;

	@BeforeEach
	void setUp() {
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
	}

	@Test
	void listKnowledgeBasesAndDocuments() {
		KnowledgeEntity k1 = baseKnowledge("k1", "kb-demo", "doc-a.txt");
		KnowledgeEntity k2 = baseKnowledge("k2", "kb-demo", "doc-b.txt");
		knowledgeRepository.save(k1);
		knowledgeRepository.save(k2);

		webTestClient.get()
			.uri("/api/knowledge-bases")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].knowledgeBaseId").isEqualTo("kb-demo")
			.jsonPath("$[0].documentCount").isEqualTo(2);

		webTestClient.get()
			.uri("/api/knowledge-bases/kb-demo/knowledge")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].knowledgeId").isNotEmpty()
			.jsonPath("$[0].parseStatus").isEqualTo("completed");
	}

	@Test
	void listChunksWithImageInfo() {
		KnowledgeEntity k1 = baseKnowledge("k3", "kb-demo", "doc-c.txt");
		knowledgeRepository.save(k1);

		ChunkEntity chunk = new ChunkEntity();
		chunk.setChunkId("c1");
		chunk.setKnowledgeId("k3");
		chunk.setChunkType(ChunkType.TEXT);
		chunk.setChunkIndex(0);
		chunk.setStartAt(0);
		chunk.setEndAt(10);
		chunk.setContent("hello");
		chunk.setImageInfoJson("[{\"url\":\"https://example.com/a.png\",\"original_url\":\"https://example.com/orig.png\",\"caption\":\"cap\",\"ocr_text\":\"ocr\",\"storage_bucket\":\"bucket-a\",\"storage_key\":\"u1/k1/a.png\"}]");
		chunk.setCreatedAt(Instant.now());
		chunk.setUpdatedAt(Instant.now());
		chunkRepository.save(chunk);

		webTestClient.get()
			.uri("/api/knowledge/k3/chunks")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].chunkId").isEqualTo("c1")
			.jsonPath("$[0].imageInfos[0].url").isEqualTo("https://example.com/a.png")
			.jsonPath("$[0].imageInfos[0].originalUrl").isEqualTo("https://example.com/orig.png")
			.jsonPath("$[0].imageInfos[0].caption").isEqualTo("cap")
			.jsonPath("$[0].imageInfos[0].ocrText").isEqualTo("ocr")
			.jsonPath("$[0].imageInfos[0].storageBucket").isEqualTo("bucket-a")
			.jsonPath("$[0].imageInfos[0].storageKey").isEqualTo("u1/k1/a.png");
	}

	@Test
	void toolsEndpointIncludesKnowledgeSearch() {
		webTestClient.get()
			.uri("/api/tools")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[?(@.name=='search_knowledge_base')]").exists();
	}

	private KnowledgeEntity baseKnowledge(String id, String kbId, String filename) {
		KnowledgeEntity k = new KnowledgeEntity();
		k.setId(id);
		k.setKnowledgeBaseId(kbId);
		k.setFileName(filename);
		k.setFileType("text/plain");
		k.setFileSize(12);
		k.setFileHash("hash-" + id);
		k.setFilePath("/tmp/" + filename);
		k.setParseStatus(KnowledgeParseStatus.COMPLETED);
		k.setEnableStatus(KnowledgeEnableStatus.ENABLED);
		k.setCreatedAt(Instant.now());
		k.setUpdatedAt(Instant.now());
		return k;
	}
}
