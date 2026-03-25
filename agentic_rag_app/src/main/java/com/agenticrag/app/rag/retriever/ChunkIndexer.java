package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;

public interface ChunkIndexer {
	void addChunks(List<TextChunk> chunks);

	default void removeChunkIds(List<String> chunkIds) {
	}

	default void removeKnowledge(String knowledgeId) {
	}
}
