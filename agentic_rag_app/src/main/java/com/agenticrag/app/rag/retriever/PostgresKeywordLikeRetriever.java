package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.model.TextChunkMetadataHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "rag.retriever.postgres.enabled", havingValue = "true")
public class PostgresKeywordLikeRetriever implements Retriever {
	private static final Logger log = LoggerFactory.getLogger(PostgresKeywordLikeRetriever.class);
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public PostgresKeywordLikeRetriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<TextChunk> retrieve(String query, int topK) {
		return retrieve(query, topK, "n/a", null);
	}

	public List<TextChunk> retrieve(String query, int topK, String traceId) {
		return retrieve(query, topK, traceId, null);
	}

	public List<TextChunk> retrieve(String query, int topK, String traceId, String knowledgeBaseId) {
		if (query == null || query.trim().isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		long startNs = System.nanoTime();
		String normalized = query.trim();
		String likePattern = "%" + escapeLike(normalized) + "%";
		String scopedKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);

		String sql = scopedKnowledgeBaseId == null
			? ""
				+ "select c.chunk_id, c.knowledge_id, c.content, c.metadata_json "
				+ "     , ((case when c.content ilike ? escape '\\' then 2.0 else 0.0 end) "
				+ " + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) "
				+ " + similarity(c.content, ?)) as retrieval_score "
				+ "from chunk c "
				+ "where c.content ilike ? escape '\\' "
				+ "   or to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?) "
				+ "   or c.content % ? "
				+ "order by "
				+ "   (case when c.content ilike ? escape '\\' then 2.0 else 0.0 end) "
				+ " + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) "
				+ " + similarity(c.content, ?) desc "
				+ "limit ?"
			: ""
				+ "select c.chunk_id, c.knowledge_id, c.content, c.metadata_json "
				+ "     , ((case when c.content ilike ? escape '\\' then 2.0 else 0.0 end) "
				+ " + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) "
				+ " + similarity(c.content, ?)) as retrieval_score "
				+ "from chunk c "
				+ "join knowledge k on k.id = c.knowledge_id "
				+ "where k.knowledge_base_id = ? "
				+ "  and (c.content ilike ? escape '\\' "
				+ "   or to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?) "
				+ "   or c.content % ?) "
				+ "order by "
				+ "   (case when c.content ilike ? escape '\\' then 2.0 else 0.0 end) "
				+ " + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) "
				+ " + similarity(c.content, ?) desc "
				+ "limit ?";

		try {
			ResultSetExtractor<List<TextChunk>> extractor = rs -> mapRows(rs);
			List<TextChunk> rows = scopedKnowledgeBaseId == null
				? jdbcTemplate.query(
					sql,
					extractor,
					likePattern,
					normalized,
					normalized,
					likePattern,
					normalized,
					normalized,
					likePattern,
					normalized,
					normalized,
					topK
				)
				: jdbcTemplate.query(
					sql,
					extractor,
					likePattern,
					normalized,
					normalized,
					scopedKnowledgeBaseId,
					likePattern,
					normalized,
					normalized,
					likePattern,
					normalized,
					normalized,
					topK
				);
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.info(
				"event=pg_keyword_like_retrieve traceId={} query={} knowledgeBaseId={} topK={} resultCount={} durationMs={}",
				traceId,
				normalized,
				scopedKnowledgeBaseId,
				topK,
				rows != null ? rows.size() : 0,
				durationMs
			);
			return rows != null ? rows : new ArrayList<>();
		} catch (Exception e) {
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.warn(
				"event=pg_keyword_like_retrieve_error traceId={} query={} knowledgeBaseId={} topK={} durationMs={} type={} message={}",
				traceId,
				normalized,
				scopedKnowledgeBaseId,
				topK,
				durationMs,
				e.getClass().getSimpleName(),
				e.getMessage()
			);
			return new ArrayList<>();
		}
	}

	private String normalizeKnowledgeBaseId(String knowledgeBaseId) {
		if (knowledgeBaseId == null) {
			return null;
		}
		String normalized = knowledgeBaseId.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private String escapeLike(String input) {
		return input
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
	}

	private List<TextChunk> mapRows(java.sql.ResultSet rs) throws java.sql.SQLException {
		List<TextChunk> out = new ArrayList<>();
		while (rs.next()) {
			String chunkId = rs.getString("chunk_id");
			String knowledgeId = rs.getString("knowledge_id");
			String content = rs.getString("content");
			String metadataJson = rs.getString("metadata_json");
			Map<String, Object> metadata = parseMetadata(metadataJson);
			out.add(TextChunkMetadataHelper.withRetrievalScore(
				new TextChunk(chunkId, knowledgeId, content, null, metadata),
				readNullableDouble(rs, "retrieval_score")
			));
		}
		return out;
	}

	private Double readNullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private Map<String, Object> parseMetadata(String json) {
		if (json == null || json.trim().isEmpty()) {
			return new HashMap<>();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
			return parsed != null ? parsed : new HashMap<>();
		} catch (Exception e) {
			return new HashMap<>();
		}
	}
}
