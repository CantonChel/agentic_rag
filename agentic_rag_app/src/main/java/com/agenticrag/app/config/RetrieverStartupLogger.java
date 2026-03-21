package com.agenticrag.app.config;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import com.agenticrag.app.rag.retriever.PostgresBm25Retriever;
import com.agenticrag.app.rag.store.PostgresVectorStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RetrieverStartupLogger implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(RetrieverStartupLogger.class);

	private final ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever;
	private final ObjectProvider<PostgresVectorStore> postgresVectorStore;
	private final Environment environment;
	private final ChunkRepository chunkRepository;
	private final EmbeddingRepository embeddingRepository;
	private final List<ChunkIndexer> chunkIndexers;
	private final ObjectMapper objectMapper;

	public RetrieverStartupLogger(
		ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever,
		ObjectProvider<PostgresVectorStore> postgresVectorStore,
		Environment environment,
		ChunkRepository chunkRepository,
		EmbeddingRepository embeddingRepository,
		List<ChunkIndexer> chunkIndexers,
		ObjectMapper objectMapper
	) {
		this.postgresBm25Retriever = postgresBm25Retriever;
		this.postgresVectorStore = postgresVectorStore;
		this.environment = environment;
		this.chunkRepository = chunkRepository;
		this.embeddingRepository = embeddingRepository;
		this.chunkIndexers = chunkIndexers;
		this.objectMapper = objectMapper;
	}

	@Override
	public void run(ApplicationArguments args) {
		boolean pgBm25 = postgresBm25Retriever.getIfAvailable() != null;
		boolean pgVector = postgresVectorStore.getIfAvailable() != null;
		String profiles = String.join(",", environment.getActiveProfiles());
		String datasourceUrl = environment.getProperty("spring.datasource.url", "");
		String pgBm25Enabled = environment.getProperty("rag.retriever.postgres.enabled", "false");
		String pgVectorEnabled = environment.getProperty("rag.vector-store.postgres.enabled", "false");
		log.info("Retriever profile: activeProfiles={}", profiles.isEmpty() ? "default" : profiles);
		log.info("Retriever config: datasource.url={}, rag.retriever.postgres.enabled={}, rag.vector-store.postgres.enabled={}",
			datasourceUrl, pgBm25Enabled, pgVectorEnabled);
		log.info("Retriever selection: bm25={}, vector={}",
			pgBm25 ? "postgres" : "lucene(in-memory)",
			pgVector ? "postgres(pgvector)" : "memory");
		if (!pgBm25) {
			log.warn("BM25 retriever is in-memory (Lucene). It will reset on every restart.");
		}
		if (!pgBm25 || !pgVector) {
			log.warn("Postgres retrievers are not fully enabled. Set SPRING_PROFILES_ACTIVE=postgres or set rag.retriever.postgres.enabled=true and rag.vector-store.postgres.enabled=true.");
		}
		if (!pgBm25 || !pgVector) {
			rebuildInMemoryIndexes();
		}
	}

	private void rebuildInMemoryIndexes() {
		List<ChunkEntity> chunks = chunkRepository.findAll();
		if (chunks.isEmpty()) {
			log.info("In-memory index rebuild skipped: no chunks in database.");
			return;
		}
		Map<String, List<Double>> embeddingByChunk = loadEmbeddingMap();
		List<TextChunk> indexable = new ArrayList<>();
		for (ChunkEntity chunk : chunks) {
			if (chunk == null || chunk.getChunkId() == null || chunk.getChunkId().trim().isEmpty()) {
				continue;
			}
			indexable.add(new TextChunk(
				chunk.getChunkId(),
				chunk.getKnowledgeId(),
				chunk.getContent(),
				embeddingByChunk.get(chunk.getChunkId()),
				parseMetadata(chunk.getMetadataJson())
			));
		}
		if (indexable.isEmpty()) {
			log.info("In-memory index rebuild skipped: no valid chunks.");
			return;
		}
		for (ChunkIndexer indexer : chunkIndexers) {
			if (indexer == null) {
				continue;
			}
			indexer.addChunks(indexable);
		}
		log.info("In-memory indexes rebuilt from database chunks={}", indexable.size());
	}

	private Map<String, List<Double>> loadEmbeddingMap() {
		List<EmbeddingEntity> embeddings = embeddingRepository.findAll();
		if (embeddings.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, List<Double>> out = new HashMap<>();
		for (EmbeddingEntity embedding : embeddings) {
			if (embedding == null || embedding.getChunkId() == null || embedding.getChunkId().trim().isEmpty()) {
				continue;
			}
			List<Double> vector = parseVector(embedding.getVectorJson());
			if (vector == null || vector.isEmpty()) {
				continue;
			}
			out.put(embedding.getChunkId(), vector);
		}
		return out;
	}

	private Map<String, Object> parseMetadata(String metadataJson) {
		if (metadataJson == null || metadataJson.trim().isEmpty()) {
			return Collections.emptyMap();
		}
		try {
			Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
			});
			return metadata != null ? metadata : Collections.emptyMap();
		} catch (Exception ignored) {
			return Collections.emptyMap();
		}
	}

	private List<Double> parseVector(String vectorJson) {
		if (vectorJson == null || vectorJson.trim().isEmpty()) {
			return Collections.emptyList();
		}
		try {
			List<Double> vector = objectMapper.readValue(vectorJson, new TypeReference<List<Double>>() {
			});
			return vector != null ? vector : Collections.emptyList();
		} catch (Exception ignored) {
			return Collections.emptyList();
		}
	}
}
