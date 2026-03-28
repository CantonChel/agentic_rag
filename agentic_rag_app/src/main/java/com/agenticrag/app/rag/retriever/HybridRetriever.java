package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.benchmark.retrieval.RetrievalTraceCollector;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceStage;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.model.TextChunkMetadataHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
	private final ObjectProvider<LuceneBm25Retriever> luceneBm25Retriever;
	private final ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever;
	private final Reranker reranker;

	public HybridRetriever(
		DenseVectorRetriever denseRetriever,
		ObjectProvider<LuceneBm25Retriever> luceneBm25Retriever,
		ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever,
		Reranker reranker
	) {
		this.denseRetriever = denseRetriever;
		this.luceneBm25Retriever = luceneBm25Retriever;
		this.postgresBm25Retriever = postgresBm25Retriever;
		this.reranker = reranker;
	}

	public List<TextChunk> retrieve(String query, int recallTopK, int rerankTopK) {
		return retrieve(query, recallTopK, rerankTopK, "n/a", null);
	}

	public List<TextChunk> retrieve(String query, int recallTopK, int rerankTopK, String traceId) {
		return retrieve(query, recallTopK, rerankTopK, traceId, null);
	}

	public List<TextChunk> retrieve(String query, int recallTopK, int rerankTopK, String traceId, String knowledgeBaseId) {
		return retrieve(query, recallTopK, rerankTopK, traceId, knowledgeBaseId, null);
	}

	public List<TextChunk> retrieve(
		String query,
		int recallTopK,
		int rerankTopK,
		String traceId,
		String knowledgeBaseId,
		RetrievalTraceCollector collector
	) {
		if (query == null || query.trim().isEmpty()) {
			return new ArrayList<>();
		}
		long startNs = System.nanoTime();

		int recall = recallTopK > 0 ? recallTopK : 20;
		int rerank = rerankTopK > 0 ? rerankTopK : 5;

		Retriever bm25Candidate = postgresBm25Retriever.getIfAvailable();
		if (bm25Candidate == null) {
			bm25Candidate = luceneBm25Retriever.getIfAvailable();
		}
		if (bm25Candidate == null) {
			log.warn("event=hybrid_bm25_missing traceId={} query={} reason=no_pg_or_lucene_retriever", traceId, query);
			bm25Candidate = (q, k) -> new ArrayList<>();
		}

		final Retriever bm25RetrieverResolved = bm25Candidate;

		CompletableFuture<List<TextChunk>> denseF = CompletableFuture.supplyAsync(
			() -> denseRetriever.retrieve(query, recall, traceId, knowledgeBaseId)
		);
		CompletableFuture<List<TextChunk>> bm25F = CompletableFuture.supplyAsync(
			() -> retrieveBm25(bm25RetrieverResolved, query, recall, traceId, knowledgeBaseId)
		);

		List<TextChunk> dense = denseF.join();
		List<TextChunk> bm25Chunks = bm25F.join();
		recordStage(collector, RetrievalTraceStage.DENSE, dense);
		recordStage(collector, RetrievalTraceStage.BM25, bm25Chunks);

		List<TextChunk> fused = fuseRrf(dense, bm25Chunks, recall, 60);
		recordStage(collector, RetrievalTraceStage.HYBRID_FUSED, fused);
		List<TextChunk> reranked = reranker.rerank(query, fused, rerank);
		recordStage(collector, RetrievalTraceStage.RERANKED, reranked);
		long durationMs = (System.nanoTime() - startNs) / 1_000_000;
		log.info(
			"event=hybrid_retrieve traceId={} query={} knowledgeBaseId={} recallTopK={} rerankTopK={} denseCount={} bm25Count={} fusedCount={} rerankedCount={} durationMs={}",
			traceId,
			query,
			knowledgeBaseId,
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

	private List<TextChunk> retrieveBm25(
		Retriever retriever,
		String query,
		int topK,
		String traceId,
		String knowledgeBaseId
	) {
		if (retriever instanceof PostgresBm25Retriever) {
			return ((PostgresBm25Retriever) retriever).retrieve(query, topK, traceId, knowledgeBaseId);
		}
		if (retriever instanceof LuceneBm25Retriever) {
			return ((LuceneBm25Retriever) retriever).retrieve(query, topK, knowledgeBaseId);
		}
		return retriever.retrieve(query, topK);
	}

	private List<TextChunk> fuseRrf(List<TextChunk> a, List<TextChunk> b, int topK, int k) {
		Map<String, Scored> scored = new HashMap<>();
		add(scored, a, k);
		add(scored, b, k);
		return scored.values().stream()
			.sorted(Comparator.comparingDouble(Scored::getScore).reversed())
			.limit(topK)
			.map(Scored::toChunkWithScore)
			.collect(Collectors.toList());
	}

	private void add(Map<String, Scored> scored, List<TextChunk> list, int k) {
		if (list == null || list.isEmpty()) {
			return;
		}
		for (int i = 0; i < list.size(); i++) {
			TextChunk c = list.get(i);
			String scoredKey = scoreKey(c);
			if (c == null || scoredKey == null) {
				continue;
			}
			double inc = 1.0 / (k + (i + 1));
			Scored s = scored.get(scoredKey);
			if (s == null) {
				scored.put(scoredKey, new Scored(c, inc));
			} else {
				s.add(inc);
			}
		}
	}

	private void recordStage(RetrievalTraceCollector collector, RetrievalTraceStage stage, List<TextChunk> chunks) {
		if (collector == null || stage == null) {
			return;
		}
		collector.recordStage(stage, chunks);
	}

	private String scoreKey(TextChunk chunk) {
		if (chunk == null || chunk.getChunkId() == null || chunk.getChunkId().trim().isEmpty()) {
			return null;
		}
		String documentId = chunk.getDocumentId() != null ? chunk.getDocumentId().trim() : "";
		return documentId + ":" + chunk.getChunkId().trim();
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

		private TextChunk toChunkWithScore() {
			Map<String, Object> additions = new LinkedHashMap<>();
			additions.put("retrieval_score", score);
			return TextChunkMetadataHelper.withAdditionalMetadata(chunk, additions);
		}
	}
}
