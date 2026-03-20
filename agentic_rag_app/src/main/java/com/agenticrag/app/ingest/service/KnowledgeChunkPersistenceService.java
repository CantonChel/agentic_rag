package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeChunkPersistenceService {
	private final ChunkRepository chunkRepository;
	private final EmbeddingRepository embeddingRepository;
	private final List<ChunkIndexer> chunkIndexers;

	public KnowledgeChunkPersistenceService(
		ChunkRepository chunkRepository,
		EmbeddingRepository embeddingRepository,
		List<ChunkIndexer> chunkIndexers
	) {
		this.chunkRepository = chunkRepository;
		this.embeddingRepository = embeddingRepository;
		this.chunkIndexers = chunkIndexers;
	}

	@Transactional
	public void replaceKnowledgeData(
		String knowledgeId,
		List<ChunkEntity> chunks,
		List<EmbeddingEntity> embeddings,
		List<TextChunk> indexable
	) {
		embeddingRepository.deleteByKnowledgeId(knowledgeId);
		chunkRepository.deleteByKnowledgeId(knowledgeId);
		if (chunks != null && !chunks.isEmpty()) {
			chunkRepository.saveAll(chunks);
		}
		if (embeddings != null && !embeddings.isEmpty()) {
			embeddingRepository.saveAll(embeddings);
		}
		List<TextChunk> forIndex = indexable != null ? indexable : Collections.emptyList();
		if (!forIndex.isEmpty() && chunkIndexers != null) {
			for (ChunkIndexer indexer : chunkIndexers) {
				if (indexer == null) {
					continue;
				}
				indexer.addChunks(forIndex);
			}
		}
	}
}
