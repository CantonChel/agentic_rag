package com.agenticrag.app.rag.store;

import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.rag.model.TextChunk;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresVectorStoreTest {
	@Test
	void returnsEmptyWhenQueryEmbeddingMissing() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		EmbeddingRepository embeddingRepository = Mockito.mock(EmbeddingRepository.class);
		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);

		List<TextChunk> out = store.similaritySearch(Collections.emptyList(), 5);
		Assertions.assertTrue(out.isEmpty());
	}

	@Test
	void returnsRankedChunksFromEmbeddingTable() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		EmbeddingRepository embeddingRepository = Mockito.mock(EmbeddingRepository.class);

		Map<String, Object> r1 = new HashMap<>();
		r1.put("chunk_id", "c1");
		r1.put("knowledge_id", "k1");
		Map<String, Object> r2 = new HashMap<>();
		r2.put("chunk_id", "c2");
		r2.put("knowledge_id", "k1");

		Mockito.when(jdbcTemplate.queryForList(Mockito.anyString(), Mockito.<Object[]>any()))
			.thenReturn(Arrays.asList(r1, r2));

		EmbeddingEntity e1 = new EmbeddingEntity();
		e1.setChunkId("c1");
		e1.setKnowledgeId("k1");
		e1.setContent("hello");
		e1.setModelName("m");
		e1.setDimension(2);
		e1.setVectorJson("[0.1,0.2]");
		e1.setEnabled(true);
		e1.setCreatedAt(Instant.now());
		e1.setUpdatedAt(Instant.now());

		EmbeddingEntity e2 = new EmbeddingEntity();
		e2.setChunkId("c2");
		e2.setKnowledgeId("k1");
		e2.setContent("world");
		e2.setModelName("m");
		e2.setDimension(2);
		e2.setVectorJson("[0.2,0.3]");
		e2.setEnabled(true);
		e2.setCreatedAt(Instant.now());
		e2.setUpdatedAt(Instant.now());

		Mockito.when(embeddingRepository.findByChunkIdIn(Mockito.anyList()))
			.thenReturn(Arrays.asList(e1, e2));

		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);
		List<TextChunk> out = store.similaritySearch(Arrays.asList(0.1, 0.2), 2);

		Assertions.assertEquals(2, out.size());
		Assertions.assertEquals("c1", out.get(0).getChunkId());
		Assertions.assertEquals("hello", out.get(0).getText());
		Assertions.assertEquals("c2", out.get(1).getChunkId());
		Assertions.assertEquals("world", out.get(1).getText());
	}

	@Test
	void returnsEmptyOnJdbcFailure() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		EmbeddingRepository embeddingRepository = Mockito.mock(EmbeddingRepository.class);

		Mockito.when(jdbcTemplate.queryForList(Mockito.anyString(), Mockito.<Object[]>any()))
			.thenThrow(new RuntimeException("db down"));

		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);
		List<TextChunk> out = store.similaritySearch(Arrays.asList(0.1, 0.2), 2);
		Assertions.assertTrue(out.isEmpty());
	}
}
