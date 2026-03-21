package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.store.CosineSimilarity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingCosineReranker implements Reranker {
	private final EmbeddingModel embeddingModel;

	public EmbeddingCosineReranker(EmbeddingModel embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	@Override
	public List<TextChunk> rerank(String query, List<TextChunk> candidates, int topK) {
		if (query == null || query.trim().isEmpty() || candidates == null || candidates.isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		List<List<Double>> q = embeddingModel.embedTexts(java.util.Collections.singletonList(query));
		List<Double> qe = q != null && !q.isEmpty() ? q.get(0) : null;
		if (qe == null || qe.isEmpty()) {
			return fallback(candidates, topK);
		}

		List<TextChunk> ranked = candidates.stream()
			.filter(c -> c != null && c.getEmbedding() != null && !c.getEmbedding().isEmpty())
			.map(c -> new ScoredChunk(c, CosineSimilarity.cosine(qe, c.getEmbedding())))
			.sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
			.limit(topK)
			.map(ScoredChunk::getChunk)
			.collect(Collectors.toList());
		if (ranked.isEmpty()) {
			return fallback(candidates, topK);
		}
		return ranked;
	}

	private List<TextChunk> fallback(List<TextChunk> candidates, int topK) {
		return candidates.stream()
			.filter(c -> c != null && c.getText() != null && !c.getText().trim().isEmpty())
			.limit(topK)
			.collect(Collectors.toList());
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
