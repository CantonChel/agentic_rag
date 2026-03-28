package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.store.InMemoryVectorStore;
import com.agenticrag.app.rag.store.PostgresVectorStore;
import com.agenticrag.app.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DenseVectorRetriever implements Retriever {
	private static final Logger log = LoggerFactory.getLogger(DenseVectorRetriever.class);
	private final EmbeddingModel embeddingModel;
	private final VectorStore vectorStore;
	private final ObjectProvider<PostgresVectorStore> postgresVectorStore;

	public DenseVectorRetriever(
		EmbeddingModel embeddingModel,
		VectorStore vectorStore,
		ObjectProvider<PostgresVectorStore> postgresVectorStore
	) {
		this.embeddingModel = embeddingModel;
		this.vectorStore = vectorStore;
		this.postgresVectorStore = postgresVectorStore;
	}

	@Override
	public List<TextChunk> retrieve(String query, int topK) {
		return retrieve(query, topK, "n/a", null);
	}

	public List<TextChunk> retrieve(String query, int topK, String traceId) {
		return retrieve(query, topK, traceId, null);
	}

	public List<TextChunk> retrieve(String query, int topK, String traceId, String knowledgeBaseId) {
		if (query == null || query.trim().isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		long startNs = System.nanoTime();
		List<List<Double>> q = embeddingModel.embedTexts(java.util.Collections.singletonList(query));
		List<Double> qe = q != null && !q.isEmpty() ? q.get(0) : null;
		VectorStore store = postgresVectorStore.getIfAvailable();
		if (store == null) {
			store = vectorStore;
		}
		List<TextChunk> out;
		if (store instanceof PostgresVectorStore) {
			out = ((PostgresVectorStore) store).similaritySearch(qe, topK, traceId, knowledgeBaseId);
		} else if (store instanceof InMemoryVectorStore) {
			out = ((InMemoryVectorStore) store).similaritySearch(qe, topK, knowledgeBaseId);
		} else {
			out = store.similaritySearch(qe, topK);
		}
		long durationMs = (System.nanoTime() - startNs) / 1_000_000;
		log.info(
			"event=dense_retrieve traceId={} query={} knowledgeBaseId={} topK={} queryVectorDim={} storeType={} resultCount={} durationMs={}",
			traceId,
			query,
			knowledgeBaseId,
			topK,
			qe != null ? qe.size() : 0,
			store != null ? store.getClass().getSimpleName() : "unknown",
			out != null ? out.size() : 0,
			durationMs
		);
		return out;
	}
}
