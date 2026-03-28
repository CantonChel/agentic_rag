package com.agenticrag.app.config;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import com.agenticrag.app.rag.retriever.PostgresBm25Retriever;
import com.agenticrag.app.rag.store.InMemoryVectorStore;
import com.agenticrag.app.rag.store.PostgresVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class RetrieverStartupLoggerTest {
	@Test
	void rebuildInMemoryIndexesUsesKnowledgeAwareEmbeddingKeyAndBackfillsKnowledgeBaseMetadata() throws Exception {
		ChunkRepository chunkRepository = Mockito.mock(ChunkRepository.class);
		EmbeddingRepository embeddingRepository = Mockito.mock(EmbeddingRepository.class);
		KnowledgeRepository knowledgeRepository = Mockito.mock(KnowledgeRepository.class);

		ChunkEntity leftChunk = chunk("shared", "knowledge-left", "left chunk");
		ChunkEntity rightChunk = chunk("shared", "knowledge-right", "right chunk");
		Mockito.when(chunkRepository.count()).thenReturn(2L);
		Mockito.when(chunkRepository.findAll()).thenReturn(Arrays.asList(leftChunk, rightChunk));

		EmbeddingEntity leftEmbedding = embedding("shared", "knowledge-left", "[1.0,0.0]");
		EmbeddingEntity rightEmbedding = embedding("shared", "knowledge-right", "[0.0,1.0]");
		Mockito.when(embeddingRepository.count()).thenReturn(2L);
		Mockito.when(embeddingRepository.findAll()).thenReturn(Arrays.asList(leftEmbedding, rightEmbedding));

		KnowledgeEntity leftKnowledge = knowledge("knowledge-left", "kb-left");
		KnowledgeEntity rightKnowledge = knowledge("knowledge-right", "kb-right");
		Mockito.when(knowledgeRepository.findAll()).thenReturn(Arrays.asList(leftKnowledge, rightKnowledge));

		InMemoryVectorStore vectorStore = new InMemoryVectorStore();
		RetrieverStartupLogger logger = new RetrieverStartupLogger(
			providerOf(null),
			providerOf(null),
			new MockEnvironment()
				.withProperty("spring.datasource.url", "jdbc:h2:mem:test")
				.withProperty("rag.retriever.postgres.enabled", "false")
				.withProperty("rag.vector-store.postgres.enabled", "false"),
			chunkRepository,
			embeddingRepository,
			knowledgeRepository,
			Collections.singletonList((ChunkIndexer) vectorStore),
			new ObjectMapper()
		);

		logger.run(new DefaultApplicationArguments(new String[0]));

		Assertions.assertEquals(2, vectorStore.size());

		List<TextChunk> leftScoped = vectorStore.similaritySearch(Arrays.asList(1.0, 0.0), 5, "kb-left");
		List<TextChunk> rightScoped = vectorStore.similaritySearch(Arrays.asList(0.0, 1.0), 5, "kb-right");

		Assertions.assertEquals(1, leftScoped.size());
		Assertions.assertEquals("knowledge-left", leftScoped.get(0).getDocumentId());
		Assertions.assertEquals("kb-left", leftScoped.get(0).getMetadata().get("knowledge_base_id"));

		Assertions.assertEquals(1, rightScoped.size());
		Assertions.assertEquals("knowledge-right", rightScoped.get(0).getDocumentId());
		Assertions.assertEquals("kb-right", rightScoped.get(0).getMetadata().get("knowledge_base_id"));
	}

	private ChunkEntity chunk(String chunkId, String knowledgeId, String content) {
		ChunkEntity entity = new ChunkEntity();
		entity.setChunkId(chunkId);
		entity.setKnowledgeId(knowledgeId);
		entity.setChunkType(ChunkType.TEXT);
		entity.setContent(content);
		entity.setMetadataJson("{}");
		entity.setCreatedAt(Instant.now());
		entity.setUpdatedAt(Instant.now());
		return entity;
	}

	private EmbeddingEntity embedding(String chunkId, String knowledgeId, String vectorJson) {
		EmbeddingEntity entity = new EmbeddingEntity();
		entity.setChunkId(chunkId);
		entity.setKnowledgeId(knowledgeId);
		entity.setModelName("text-embedding-3-large");
		entity.setDimension(2);
		entity.setVectorJson(vectorJson);
		entity.setContent("content");
		entity.setEnabled(true);
		entity.setCreatedAt(Instant.now());
		entity.setUpdatedAt(Instant.now());
		return entity;
	}

	private KnowledgeEntity knowledge(String knowledgeId, String knowledgeBaseId) {
		KnowledgeEntity entity = new KnowledgeEntity();
		entity.setId(knowledgeId);
		entity.setKnowledgeBaseId(knowledgeBaseId);
		return entity;
	}

	private <T> ObjectProvider<T> providerOf(T value) {
		return new ObjectProvider<T>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getIfUnique() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}
}
