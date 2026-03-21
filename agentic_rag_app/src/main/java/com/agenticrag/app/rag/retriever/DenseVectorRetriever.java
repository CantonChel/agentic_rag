package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DenseVectorRetriever implements Retriever {
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
		if (query == null || query.trim().isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		List<List<Double>> q = embeddingModel.embedTexts(java.util.Collections.singletonList(query));
		List<Double> qe = q != null && !q.isEmpty() ? q.get(0) : null;
		VectorStore store = postgresVectorStore.getIfAvailable();
		if (store == null) {
			store = vectorStore;
		}
		return store.similaritySearch(qe, topK);
	}
}
