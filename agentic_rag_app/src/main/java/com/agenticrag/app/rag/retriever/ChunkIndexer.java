package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;

public interface ChunkIndexer {
	void addChunks(List<TextChunk> chunks);
}

