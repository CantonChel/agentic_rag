package com.agenticrag.app.rag.retriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PostgresBm25RetrieverTest {
	@Test
	void returnsEmptyForBlankQuery() {
		JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
		ObjectMapper objectMapper = new ObjectMapper();
		PostgresBm25Retriever retriever = new PostgresBm25Retriever(jdbcTemplate, objectMapper);

		List<?> out = retriever.retrieve("  ", 10);
		Assertions.assertTrue(out.isEmpty());
	}

	@Test
	void returnsEmptyOnJdbcFailure() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate() {
			@Override
			public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) {
				throw new RuntimeException("db down");
			}
		};
		ObjectMapper objectMapper = new ObjectMapper();
		PostgresBm25Retriever retriever = new PostgresBm25Retriever(jdbcTemplate, objectMapper);

		List<?> out = retriever.retrieve("hello", 5);
		Assertions.assertTrue(out.isEmpty());
	}

	@Test
	void includesKnowledgeBaseScopeInQueryArguments() {
		AtomicReference<Object[]> capturedArgs = new AtomicReference<>();
			JdbcTemplate jdbcTemplate = new JdbcTemplate() {
				@Override
				public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) {
					capturedArgs.set(args);
					try {
						return rse.extractData(Mockito.mock(java.sql.ResultSet.class));
					} catch (java.sql.SQLException e) {
						throw new RuntimeException(e);
					}
				}
			};
		ObjectMapper objectMapper = new ObjectMapper();
		PostgresBm25Retriever retriever = new PostgresBm25Retriever(jdbcTemplate, objectMapper);

		List<?> out = retriever.retrieve("hello", 5, "trace-1", "kb-1");

		Assertions.assertTrue(out.isEmpty());
		Assertions.assertNotNull(capturedArgs.get());
		Assertions.assertEquals("hello", capturedArgs.get()[0]);
		Assertions.assertEquals("kb-1", capturedArgs.get()[1]);
		Assertions.assertEquals("hello", capturedArgs.get()[2]);
	}
}
