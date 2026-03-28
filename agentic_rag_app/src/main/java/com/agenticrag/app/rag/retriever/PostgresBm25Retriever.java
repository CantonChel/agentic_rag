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
public class PostgresBm25Retriever implements Retriever {
	private static final Logger log = LoggerFactory.getLogger(PostgresBm25Retriever.class);
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public PostgresBm25Retriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
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
		String scopedKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);

		String sql = scopedKnowledgeBaseId == null
			? ""
				+ "select c.chunk_id, c.knowledge_id, c.content, c.metadata_json "
				+ "     , ts_rank(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) as retrieval_score "
				+ "from chunk c "
				+ "where to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?) "
				+ "order by ts_rank(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) desc "
				+ "limit ?"
			: ""
				+ "select c.chunk_id, c.knowledge_id, c.content, c.metadata_json "
				+ "     , ts_rank(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) as retrieval_score "
				+ "from chunk c "
				+ "join knowledge k on k.id = c.knowledge_id "
				+ "where k.knowledge_base_id = ? "
				+ "  and to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?) "
				+ "order by ts_rank(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) desc "
				+ "limit ?";
		String fuzzySql = scopedKnowledgeBaseId == null
			? ""
				+ "select c.chunk_id, c.knowledge_id, c.content, c.metadata_json "
				+ "     , word_similarity(?, c.content) as retrieval_score "
				+ "from chunk c "
				+ "where word_similarity(?, c.content) > 0.3 "
				+ "order by word_similarity(?, c.content) desc "
				+ "limit ?"
			: ""
				+ "select c.chunk_id, c.knowledge_id, c.content, c.metadata_json "
				+ "     , word_similarity(?, c.content) as retrieval_score "
				+ "from chunk c "
				+ "join knowledge k on k.id = c.knowledge_id "
				+ "where k.knowledge_base_id = ? "
				+ "  and word_similarity(?, c.content) > 0.3 "
				+ "order by word_similarity(?, c.content) desc "
				+ "limit ?";

		try {
			ResultSetExtractor<List<TextChunk>> extractor = rs -> mapRows(rs);
			List<TextChunk> primary = scopedKnowledgeBaseId == null
				? jdbcTemplate.query(sql, extractor, query, query, query, topK)
				: jdbcTemplate.query(sql, extractor, query, scopedKnowledgeBaseId, query, query, topK);
			if (!primary.isEmpty()) {
				long durationMs = (System.nanoTime() - startNs) / 1_000_000;
				log.info(
					"event=pg_bm25_retrieve traceId={} query={} knowledgeBaseId={} topK={} primaryCount={} fallbackUsed=false durationMs={}",
					traceId,
					query,
					scopedKnowledgeBaseId,
					topK,
					primary.size(),
					durationMs
				);
				return primary;
			}
			List<TextChunk> fuzzy = scopedKnowledgeBaseId == null
				? jdbcTemplate.query(fuzzySql, extractor, query, query, query, topK)
				: jdbcTemplate.query(fuzzySql, extractor, query, scopedKnowledgeBaseId, query, query, topK);
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.info(
				"event=pg_bm25_retrieve traceId={} query={} knowledgeBaseId={} topK={} primaryCount=0 fallbackUsed=true fallbackCount={} durationMs={}",
				traceId,
				query,
				scopedKnowledgeBaseId,
				topK,
				fuzzy != null ? fuzzy.size() : 0,
				durationMs
			);
			return fuzzy;
		} catch (Exception e) {
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.warn(
				"event=pg_bm25_retrieve_error traceId={} query={} knowledgeBaseId={} topK={} durationMs={} type={} message={}",
				traceId,
				query,
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
