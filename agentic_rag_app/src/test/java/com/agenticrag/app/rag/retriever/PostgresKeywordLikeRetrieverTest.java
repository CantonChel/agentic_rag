package com.agenticrag.app.rag.retriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PostgresKeywordLikeRetrieverTest {
	@Test
	void returnsEmptyForBlankQuery() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate();
		ObjectMapper objectMapper = new ObjectMapper();
		PostgresKeywordLikeRetriever retriever = new PostgresKeywordLikeRetriever(jdbcTemplate, objectMapper);

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
		PostgresKeywordLikeRetriever retriever = new PostgresKeywordLikeRetriever(jdbcTemplate, objectMapper);

		List<?> out = retriever.retrieve("keyword", 5);
		Assertions.assertTrue(out.isEmpty());
	}
}
