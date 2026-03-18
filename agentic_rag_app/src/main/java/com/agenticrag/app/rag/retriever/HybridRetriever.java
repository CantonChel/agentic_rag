package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class HybridRetriever {
	private final DenseVectorRetriever denseRetriever;
	private final LuceneBm25Retriever bm25Retriever;
	private final Reranker reranker;

	public HybridRetriever(DenseVectorRetriever denseRetriever, LuceneBm25Retriever bm25Retriever, Reranker reranker) {
		this.denseRetriever = denseRetriever;
		this.bm25Retriever = bm25Retriever;
		this.reranker = reranker;
	}

	public List<TextChunk> retrieve(String query, int recallTopK, int rerankTopK) {
		if (query == null || query.trim().isEmpty()) {
			return new ArrayList<>();
		}

		int recall = recallTopK > 0 ? recallTopK : 20;
		int rerank = rerankTopK > 0 ? rerankTopK : 5;

		CompletableFuture<List<TextChunk>> denseF = CompletableFuture.supplyAsync(() -> denseRetriever.retrieve(query, recall));
		CompletableFuture<List<TextChunk>> bm25F = CompletableFuture.supplyAsync(() -> bm25Retriever.retrieve(query, recall));

		List<TextChunk> dense = denseF.join();
		List<TextChunk> bm25 = bm25F.join();

		List<TextChunk> fused = fuseRrf(dense, bm25, recall, 60);
		return reranker.rerank(query, fused, rerank);
	}

	private List<TextChunk> fuseRrf(List<TextChunk> a, List<TextChunk> b, int topK, int k) {
		Map<String, Scored> scored = new HashMap<>();
		add(scored, a, k);
		add(scored, b, k);
		return scored.values().stream()
			.sorted(Comparator.comparingDouble(Scored::getScore).reversed())
			.limit(topK)
			.map(Scored::getChunk)
			.collect(Collectors.toList());
	}

	private void add(Map<String, Scored> scored, List<TextChunk> list, int k) {
		if (list == null || list.isEmpty()) {
			return;
		}
		for (int i = 0; i < list.size(); i++) {
			TextChunk c = list.get(i);
			if (c == null || c.getChunkId() == null) {
				continue;
			}
			double inc = 1.0 / (k + (i + 1));
			Scored s = scored.get(c.getChunkId());
			if (s == null) {
				scored.put(c.getChunkId(), new Scored(c, inc));
			} else {
				s.add(inc);
			}
		}
	}

	private static final class Scored {
		private final TextChunk chunk;
		private double score;

		private Scored(TextChunk chunk, double score) {
			this.chunk = chunk;
			this.score = score;
		}

		private TextChunk getChunk() {
			return chunk;
		}

		private double getScore() {
			return score;
		}

		private void add(double inc) {
			this.score += inc;
		}
	}
}

