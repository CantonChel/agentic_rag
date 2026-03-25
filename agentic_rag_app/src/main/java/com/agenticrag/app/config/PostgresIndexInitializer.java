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
		jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
		normalizeLobTextColumn("chunk", "content");
		normalizeLobTextColumn("chunk", "image_info_json");
		normalizeLobTextColumn("chunk", "metadata_json");
		normalizeLobTextColumn("knowledge", "metadata_json");
		normalizeLobTextColumn("embedding", "content");
		normalizeLobTextColumn("embedding", "vector_json");
		normalizeLobTextColumn("stored_messages", "content");
		recoverTextifiedLargeObjectRows("chunk", "content");
		recoverTextifiedLargeObjectRows("chunk", "image_info_json");
		recoverTextifiedLargeObjectRows("chunk", "metadata_json");
		recoverTextifiedLargeObjectRows("knowledge", "metadata_json");
		recoverTextifiedLargeObjectRows("embedding", "content");
		recoverTextifiedLargeObjectRows("embedding", "vector_json");
		recoverTextifiedLargeObjectRows("stored_messages", "content");
		executeIgnore("CREATE INDEX IF NOT EXISTS idx_chunk_fts ON chunk USING GIN (to_tsvector('simple', content))");
		executeIgnore("CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm ON chunk USING GIN (content gin_trgm_ops)");
		executeIgnore("CREATE INDEX IF NOT EXISTS idx_embedding_vector_l2 ON embedding USING ivfflat ((vector_json::vector))");
		jdbcTemplate.execute("ANALYZE chunk");
		jdbcTemplate.execute("ANALYZE embedding");
	}

	private void executeIgnore(String sql) {
		try {
			jdbcTemplate.execute(sql);
		} catch (Exception ignored) {
		}
	}

	private void normalizeLobTextColumn(String tableName, String columnName) {
		String sql = ""
			+ "select udt_name from information_schema.columns "
			+ "where table_schema = 'public' and table_name = ? and column_name = ?";
		String udt = jdbcTemplate.query(sql, rs -> rs.next() ? rs.getString("udt_name") : null, tableName, columnName);
		if (udt == null || !"oid".equalsIgnoreCase(udt)) {
			return;
		}
		String alter = String.format(
			"ALTER TABLE %s ALTER COLUMN %s TYPE text USING convert_from(lo_get(%s), 'UTF8')",
			tableName, columnName, columnName
		);
		jdbcTemplate.execute(alter);
	}

	private void recoverTextifiedLargeObjectRows(String tableName, String columnName) {
		String sql = String.format(
			"UPDATE %s SET %s = convert_from(lo_get((%s)::oid), 'UTF8') "
				+ "WHERE %s ~ '^[0-9]+$' "
				+ "AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = (%s)::oid)",
			tableName, columnName, columnName, columnName, columnName
		);
		executeIgnore(sql);
	}
}
