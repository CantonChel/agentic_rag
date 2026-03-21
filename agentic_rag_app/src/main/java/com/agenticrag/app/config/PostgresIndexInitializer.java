package com.agenticrag.app.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.PostConstruct;

@Configuration
@ConditionalOnProperty(name = "rag.postgres.auto-index", havingValue = "true")
public class PostgresIndexInitializer {
	private final JdbcTemplate jdbcTemplate;

	public PostgresIndexInitializer(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@PostConstruct
	public void init() {
		jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunk_fts ON chunk USING GIN (to_tsvector('simple', content))");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_embedding_vector_l2 ON embedding USING ivfflat (vector_json::vector)");
		jdbcTemplate.execute("ANALYZE chunk");
		jdbcTemplate.execute("ANALYZE embedding");
	}
}
