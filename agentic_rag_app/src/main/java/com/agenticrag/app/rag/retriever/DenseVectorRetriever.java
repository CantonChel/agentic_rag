package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DenseVectorRetriever implements Retriever {
	private final EmbeddingModel embeddingModel;
	private final VectorStore vectorStore;

	public DenseVectorRetriever(EmbeddingModel embeddingModel, VectorStore vectorStore) {
		this.embeddingModel = embeddingModel;
		this.vectorStore = vectorStore;
	}

	@Override
	public List<TextChunk> retrieve(String query, int topK) {
		if (query == null || query.trim().isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}
		List<List<Double>> q = embeddingModel.embedTexts(java.util.Collections.singletonList(query));
		List<Double> qe = q != null && !q.isEmpty() ? q.get(0) : null;
		return vectorStore.similaritySearch(qe, topK);
	}
}

