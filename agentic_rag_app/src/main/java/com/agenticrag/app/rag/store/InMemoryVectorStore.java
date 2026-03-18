package com.agenticrag.app.rag.store;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class InMemoryVectorStore implements VectorStore {
	private final Map<String, TextChunk> chunksById = new ConcurrentHashMap<>();

	@Override
	public void addChunks(List<TextChunk> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return;
		}
		for (TextChunk chunk : chunks) {
			if (chunk == null || chunk.getChunkId() == null || chunk.getChunkId().trim().isEmpty()) {
				continue;
			}
			chunksById.put(chunk.getChunkId(), chunk);
		}
	}

	@Override
	public List<TextChunk> similaritySearch(List<Double> queryEmbedding, int topK) {
		if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}

		List<ScoredChunk> scored = new ArrayList<>();
		for (TextChunk chunk : chunksById.values()) {
			if (chunk == null || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
				continue;
			}
			double score = CosineSimilarity.cosine(queryEmbedding, chunk.getEmbedding());
			scored.add(new ScoredChunk(chunk, score));
		}

		if (scored.isEmpty()) {
			return new ArrayList<>();
		}

		return scored.stream()
			.sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
			.limit(topK)
			.map(ScoredChunk::getChunk)
			.collect(Collectors.toList());
	}

	public int size() {
		return chunksById.size();
	}

	public void clear() {
		chunksById.clear();
	}

	private static final class ScoredChunk {
		private final TextChunk chunk;
		private final double score;

		private ScoredChunk(TextChunk chunk, double score) {
			this.chunk = chunk;
			this.score = score;
		}

		private TextChunk getChunk() {
			return chunk;
		}

		private double getScore() {
			return score;
		}
	}
}

