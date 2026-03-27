package com.agenticrag.app.rag.store;

import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.rag.model.TextChunk;
import java.util.ArrayList;
import java.util.Collections;
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
		return similaritySearch(queryEmbedding, topK, "n/a", null);
	}

	public List<TextChunk> similaritySearch(List<Double> queryEmbedding, int topK, String traceId) {
		return similaritySearch(queryEmbedding, topK, traceId, null);
	}

	public List<TextChunk> similaritySearch(List<Double> queryEmbedding, int topK, String traceId, String knowledgeBaseId) {
		if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		long startNs = System.nanoTime();

		String vecLiteral = toPgvectorLiteral(queryEmbedding);
		String sql = ""
			+ "select e.chunk_id, e.knowledge_id, e.content, k.knowledge_base_id "
			+ "from embedding e "
			+ "join knowledge k on k.id = e.knowledge_id "
			+ "where (? is null or k.knowledge_base_id = ?) "
			+ "order by e.vector_json::vector <-> ?::vector "
			+ "limit ?";

		try {
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, knowledgeBaseId, knowledgeBaseId, vecLiteral, topK);
			if (rows == null || rows.isEmpty()) {
				long durationMs = (System.nanoTime() - startNs) / 1_000_000;
				log.info(
					"event=pg_vector_search traceId={} knowledgeBaseId={} topK={} queryVectorDim={} candidateRows=0 durationMs={}",
					traceId,
					knowledgeBaseId,
					topK,
					queryEmbedding.size(),
					durationMs
				);
				return new ArrayList<>();
			}
			List<TextChunk> out = new ArrayList<>();
			for (Map<String, Object> row : rows) {
				if (row == null) {
					continue;
				}
				String chunkId = row.get("chunk_id") != null ? String.valueOf(row.get("chunk_id")) : null;
				if (chunkId == null || chunkId.trim().isEmpty()) {
					continue;
				}
				String knowledgeId = row.get("knowledge_id") != null ? String.valueOf(row.get("knowledge_id")) : null;
				String content = row.get("content") != null ? String.valueOf(row.get("content")) : "";
				String resolvedKnowledgeBaseId = row.get("knowledge_base_id") != null ? String.valueOf(row.get("knowledge_base_id")) : knowledgeBaseId;
				Map<String, Object> metadata = resolvedKnowledgeBaseId == null
					? Collections.emptyMap()
					: Collections.singletonMap("knowledge_base_id", resolvedKnowledgeBaseId);
				out.add(new TextChunk(chunkId, knowledgeId, content, null, metadata));
			}
			if (out.isEmpty()) {
				long durationMs = (System.nanoTime() - startNs) / 1_000_000;
				log.info(
					"event=pg_vector_search traceId={} knowledgeBaseId={} topK={} queryVectorDim={} candidateRows={} resolvedChunkIds=0 durationMs={}",
					traceId,
					knowledgeBaseId,
					topK,
					queryEmbedding.size(),
					rows.size(),
					durationMs
				);
				return new ArrayList<>();
			}
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.info(
				"event=pg_vector_search traceId={} knowledgeBaseId={} topK={} queryVectorDim={} candidateRows={} resolvedChunkIds={} contentRows={} resultCount={} durationMs={}",
				traceId,
				knowledgeBaseId,
				topK,
				queryEmbedding.size(),
				rows.size(),
				out.size(),
				rows.size(),
				out.size(),
				durationMs
			);
			return out;
		} catch (Exception e) {
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.warn(
				"event=pg_vector_search_error traceId={} knowledgeBaseId={} topK={} queryVectorDim={} durationMs={} type={} message={}",
				traceId,
				knowledgeBaseId,
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
