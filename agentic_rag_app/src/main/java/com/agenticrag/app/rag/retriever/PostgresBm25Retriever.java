package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "rag.retriever.postgres.enabled", havingValue = "true")
public class PostgresBm25Retriever implements Retriever {
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public PostgresBm25Retriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<TextChunk> retrieve(String query, int topK) {
		if (query == null || query.trim().isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}

		String sql = ""
			+ "select chunk_id, knowledge_id, content, metadata_json "
			+ "from chunk "
			+ "where to_tsvector('simple', content) @@ plainto_tsquery('simple', ?) "
			+ "order by ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', ?)) desc "
			+ "limit ?";

		try {
			return jdbcTemplate.query(sql, rs -> {
				List<TextChunk> out = new ArrayList<>();
				while (rs.next()) {
					String chunkId = rs.getString("chunk_id");
					String knowledgeId = rs.getString("knowledge_id");
					String content = rs.getString("content");
					String metadataJson = rs.getString("metadata_json");
					out.add(new TextChunk(chunkId, knowledgeId, content, null, parseMetadata(metadataJson)));
				}
				return out;
			}, query, query, topK);
		} catch (Exception e) {
			return new ArrayList<>();
		}
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
