package com.agenticrag.app.rag.retriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
}
