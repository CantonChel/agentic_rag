package com.agenticrag.app.rag.store;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InMemoryVectorStoreTest {
	@Test
	void returnsMostSimilarChunk() {
		InMemoryVectorStore store = new InMemoryVectorStore();

		TextChunk a = new TextChunk("a", "d1", "a", Arrays.asList(1.0, 0.0), new HashMap<String, Object>());
		TextChunk b = new TextChunk("b", "d1", "b", Arrays.asList(0.0, 1.0), new HashMap<String, Object>());
		store.addChunks(Arrays.asList(a, b));

		List<TextChunk> res = store.similaritySearch(Arrays.asList(0.9, 0.1), 1);
		Assertions.assertEquals(1, res.size());
		Assertions.assertEquals("a", res.get(0).getChunkId());
	}
}

