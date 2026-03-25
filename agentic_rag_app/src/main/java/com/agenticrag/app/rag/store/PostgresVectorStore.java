package com.agenticrag.app.rag.store;

import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.rag.model.TextChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Primary
@ConditionalOnProperty(name = "rag.vector-store.postgres.enabled", havingValue = "true")
public class PostgresVectorStore implements VectorStore {
	private static final Logger log = LoggerFactory.getLogger(PostgresVectorStore.class);
	private final JdbcTemplate jdbcTemplate;
	private final EmbeddingRepository embeddingRepository;

	public PostgresVectorStore(JdbcTemplate jdbcTemplate, EmbeddingRepository embeddingRepository) {
		this.jdbcTemplate = jdbcTemplate;
		this.embeddingRepository = embeddingRepository;
	}

	@Override
	public void addChunks(List<TextChunk> chunks) {
		// No-op: embeddings are persisted through JPA in the ingest pipeline.
	}

	@Override
	public List<TextChunk> similaritySearch(List<Double> queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, "n/a");
	}

	public List<TextChunk> similaritySearch(List<Double> queryEmbedding, int topK, String traceId) {
		if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		long startNs = System.nanoTime();

		String vecLiteral = toPgvectorLiteral(queryEmbedding);
		String sql = ""
			+ "select chunk_id, knowledge_id "
			+ "from embedding "
			+ "order by vector_json::vector <-> ?::vector "
			+ "limit ?";

		try {
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, vecLiteral, topK);
			if (rows == null || rows.isEmpty()) {
				long durationMs = (System.nanoTime() - startNs) / 1_000_000;
				log.info(
					"event=pg_vector_search traceId={} topK={} queryVectorDim={} candidateRows=0 durationMs={}",
					traceId,
					topK,
					queryEmbedding.size(),
					durationMs
				);
				return new ArrayList<>();
			}
			List<String> chunkIds = new ArrayList<>();
			Map<String, String> knowledgeByChunk = new HashMap<>();
			for (Map<String, Object> row : rows) {
				if (row == null) {
					continue;
				}
				String chunkId = row.get("chunk_id") != null ? String.valueOf(row.get("chunk_id")) : null;
				if (chunkId == null || chunkId.trim().isEmpty()) {
					continue;
				}
				chunkIds.add(chunkId);
				String knowledgeId = row.get("knowledge_id") != null ? String.valueOf(row.get("knowledge_id")) : null;
				if (knowledgeId != null) {
					knowledgeByChunk.put(chunkId, knowledgeId);
				}
			}
			if (chunkIds.isEmpty()) {
				long durationMs = (System.nanoTime() - startNs) / 1_000_000;
				log.info(
					"event=pg_vector_search traceId={} topK={} queryVectorDim={} candidateRows={} resolvedChunkIds=0 durationMs={}",
					traceId,
					topK,
					queryEmbedding.size(),
					rows.size(),
					durationMs
				);
				return new ArrayList<>();
			}
			List<Object[]> embeddings = embeddingRepository.listChunkContentByChunkIds(chunkIds);
			Map<String, String> contentByChunk = new HashMap<>();
			Map<String, String> fallbackKnowledgeByChunk = new HashMap<>();
			for (Object[] row : embeddings) {
				if (row == null || row.length < 3 || row[0] == null) {
					continue;
				}
				String chunkId = String.valueOf(row[0]);
				contentByChunk.put(chunkId, row[2] != null ? String.valueOf(row[2]) : "");
				if (row[1] != null) {
					fallbackKnowledgeByChunk.put(chunkId, String.valueOf(row[1]));
				}
			}
			List<TextChunk> out = new ArrayList<>();
			for (String chunkId : chunkIds) {
				if (!contentByChunk.containsKey(chunkId)) {
					continue;
				}
				String knowledgeId = knowledgeByChunk.getOrDefault(chunkId, fallbackKnowledgeByChunk.get(chunkId));
				out.add(new TextChunk(chunkId, knowledgeId, contentByChunk.get(chunkId), null, Collections.emptyMap()));
			}
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.info(
				"event=pg_vector_search traceId={} topK={} queryVectorDim={} candidateRows={} resolvedChunkIds={} contentRows={} resultCount={} durationMs={}",
				traceId,
				topK,
				queryEmbedding.size(),
				rows.size(),
				chunkIds.size(),
				embeddings.size(),
				out.size(),
				durationMs
			);
			return out;
		} catch (Exception e) {
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.warn(
				"event=pg_vector_search_error traceId={} topK={} queryVectorDim={} durationMs={} type={} message={}",
				traceId,
				topK,
				queryEmbedding != null ? queryEmbedding.size() : 0,
				durationMs,
				e.getClass().getSimpleName(),
				e.getMessage()
			);
			return new ArrayList<>();
		}
	}

	private String toPgvectorLiteral(List<Double> vec) {
		StringBuilder sb = new StringBuilder();
		sb.append('[');
		for (int i = 0; i < vec.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			Double v = vec.get(i);
			sb.append(v != null ? v : 0.0);
		}
		sb.append(']');
		return sb.toString();
	}
}
