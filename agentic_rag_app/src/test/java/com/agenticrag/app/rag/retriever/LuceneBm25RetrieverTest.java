package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LuceneBm25RetrieverTest {
	@Test
	void retrievesByExactKeyword() {
		LuceneBm25Retriever r = new LuceneBm25Retriever();
		TextChunk a = new TextChunk("a", "d1", "Agent-X99 服务器 内存 256GB", null, new HashMap<String, Object>());
		TextChunk b = new TextChunk("b", "d1", "通用 服务器 说明书", null, new HashMap<String, Object>());
		r.addChunks(Arrays.asList(a, b));

		List<TextChunk> res = r.retrieve("Agent-X99", 3);
		Assertions.assertFalse(res.isEmpty());
		Assertions.assertEquals("a", res.get(0).getChunkId());
	}

	@Test
	void filtersByKnowledgeBaseIdWhenProvided() {
		LuceneBm25Retriever r = new LuceneBm25Retriever();

		HashMap<String, Object> leftMetadata = new HashMap<>();
		leftMetadata.put("knowledge_base_id", "kb-left");
		HashMap<String, Object> rightMetadata = new HashMap<>();
		rightMetadata.put("knowledge_base_id", "kb-right");

		TextChunk left = new TextChunk("a", "d1", "Agent-X99 服务器 内存 256GB", null, leftMetadata);
		TextChunk right = new TextChunk("b", "d2", "Agent-X99 服务器 内存 128GB", null, rightMetadata);
		r.addChunks(Arrays.asList(left, right));

		List<TextChunk> res = r.retrieve("Agent-X99", 5, "kb-right");

		Assertions.assertEquals(1, res.size());
		Assertions.assertEquals("b", res.get(0).getChunkId());
	}
}
