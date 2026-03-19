package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;

public interface Retriever {
	List<TextChunk> retrieve(String query, int topK);
}

