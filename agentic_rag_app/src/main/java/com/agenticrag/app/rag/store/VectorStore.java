package com.agenticrag.app.rag.store;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;

public interface VectorStore {
	void addChunks(List<TextChunk> chunks);

	List<TextChunk> similaritySearch(List<Double> queryEmbedding, int topK);
}

