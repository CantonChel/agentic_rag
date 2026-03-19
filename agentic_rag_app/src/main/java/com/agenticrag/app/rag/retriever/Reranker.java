package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;

public interface Reranker {
	List<TextChunk> rerank(String query, List<TextChunk> candidates, int topK);
}

