package com.agenticrag.app.rag.store;

import com.agenticrag.app.rag.model.TextChunk;
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
		com.agenticrag.app.ingest.repo.EmbeddingRepository embeddingRepository = Mockito.mock(com.agenticrag.app.ingest.repo.EmbeddingRepository.class);
		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);

		List<TextChunk> out = store.similaritySearch(Collections.emptyList(), 5);
		Assertions.assertTrue(out.isEmpty());
	}

	@Test
	void returnsRankedChunksFromEmbeddingTable() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		com.agenticrag.app.ingest.repo.EmbeddingRepository embeddingRepository = Mockito.mock(com.agenticrag.app.ingest.repo.EmbeddingRepository.class);

		Map<String, Object> r1 = new HashMap<>();
		r1.put("chunk_id", "c1");
		r1.put("knowledge_id", "k1");
		r1.put("content", "hello");
		Map<String, Object> r2 = new HashMap<>();
		r2.put("chunk_id", "c2");
		r2.put("knowledge_id", "k1");
		r2.put("content", "world");

		Mockito.when(jdbcTemplate.queryForList(Mockito.anyString(), Mockito.<Object[]>any()))
			.thenReturn(Arrays.asList(r1, r2));

		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);
		List<TextChunk> out = store.similaritySearch(Arrays.asList(0.1, 0.2), 2);

		Assertions.assertEquals(2, out.size());
		Assertions.assertEquals("c1", out.get(0).getChunkId());
		Assertions.assertEquals("hello", out.get(0).getText());
		Assertions.assertEquals("c2", out.get(1).getChunkId());
		Assertions.assertEquals("world", out.get(1).getText());
	}

	@Test
	void passesKnowledgeBaseScopeToJdbcQuery() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		com.agenticrag.app.ingest.repo.EmbeddingRepository embeddingRepository = Mockito.mock(com.agenticrag.app.ingest.repo.EmbeddingRepository.class);

		Map<String, Object> row = new HashMap<>();
		row.put("chunk_id", "c1");
		row.put("knowledge_id", "k1");
		row.put("knowledge_base_id", "kb-1");
		row.put("content", "hello");

		Mockito.when(jdbcTemplate.queryForList(Mockito.anyString(), Mockito.<Object[]>any()))
			.thenReturn(Collections.singletonList(row));

		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);
		List<TextChunk> out = store.similaritySearch(Arrays.asList(0.1, 0.2), 2, "trace-1", "kb-1");

		Assertions.assertEquals(1, out.size());
		Assertions.assertEquals("kb-1", out.get(0).getMetadata().get("knowledge_base_id"));
		Mockito.verify(jdbcTemplate).queryForList(Mockito.anyString(), Mockito.eq("kb-1"), Mockito.eq("kb-1"), Mockito.anyString(), Mockito.eq(2));
	}

	@Test
	void returnsEmptyOnJdbcFailure() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		com.agenticrag.app.ingest.repo.EmbeddingRepository embeddingRepository = Mockito.mock(com.agenticrag.app.ingest.repo.EmbeddingRepository.class);

		Mockito.when(jdbcTemplate.queryForList(Mockito.anyString(), Mockito.<Object[]>any()))
			.thenThrow(new RuntimeException("db down"));

		PostgresVectorStore store = new PostgresVectorStore(jdbcTemplate, embeddingRepository);
		List<TextChunk> out = store.similaritySearch(Arrays.asList(0.1, 0.2), 2);
		Assertions.assertTrue(out.isEmpty());
	}
}
