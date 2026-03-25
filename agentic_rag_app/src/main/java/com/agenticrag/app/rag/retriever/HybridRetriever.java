package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class HybridRetriever {
	private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
	private final DenseVectorRetriever denseRetriever;
	private final LuceneBm25Retriever bm25Retriever;
	private final ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever;
	private final Reranker reranker;

	public HybridRetriever(
		DenseVectorRetriever denseRetriever,
		LuceneBm25Retriever bm25Retriever,
		ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever,
		Reranker reranker
	) {
		this.denseRetriever = denseRetriever;
		this.bm25Retriever = bm25Retriever;
		this.postgresBm25Retriever = postgresBm25Retriever;
		this.reranker = reranker;
	}

	public List<TextChunk> retrieve(String query, int recallTopK, int rerankTopK) {
		return retrieve(query, recallTopK, rerankTopK, "n/a");
	}

	public List<TextChunk> retrieve(String query, int recallTopK, int rerankTopK, String traceId) {
		if (query == null || query.trim().isEmpty()) {
			return new ArrayList<>();
		}
		long startNs = System.nanoTime();

		int recall = recallTopK > 0 ? recallTopK : 20;
		int rerank = rerankTopK > 0 ? rerankTopK : 5;

		Retriever bm25Candidate = postgresBm25Retriever.getIfAvailable();
		if (bm25Candidate == null) {
			bm25Candidate = bm25Retriever;
		}

		final Retriever bm25RetrieverResolved = bm25Candidate;

		CompletableFuture<List<TextChunk>> denseF = CompletableFuture.supplyAsync(() -> denseRetriever.retrieve(query, recall, traceId));
		CompletableFuture<List<TextChunk>> bm25F = CompletableFuture.supplyAsync(() -> {
			if (bm25RetrieverResolved instanceof PostgresBm25Retriever) {
				return ((PostgresBm25Retriever) bm25RetrieverResolved).retrieve(query, recall, traceId);
			}
			return bm25RetrieverResolved.retrieve(query, recall);
		});

		List<TextChunk> dense = denseF.join();
		List<TextChunk> bm25Chunks = bm25F.join();

		List<TextChunk> fused = fuseRrf(dense, bm25Chunks, recall, 60);
		List<TextChunk> reranked = reranker.rerank(query, fused, rerank);
		long durationMs = (System.nanoTime() - startNs) / 1_000_000;
		log.info(
			"event=hybrid_retrieve traceId={} query={} recallTopK={} rerankTopK={} denseCount={} bm25Count={} fusedCount={} rerankedCount={} durationMs={}",
			traceId,
			query,
			recall,
			rerank,
			dense != null ? dense.size() : 0,
			bm25Chunks != null ? bm25Chunks.size() : 0,
			fused != null ? fused.size() : 0,
			reranked != null ? reranked.size() : 0,
			durationMs
		);
		return reranked;
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
