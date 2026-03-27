package com.agenticrag.app.tool;

import com.agenticrag.app.benchmark.retrieval.RetrievalTraceCollector;
import com.agenticrag.app.rag.context.ContextAssembler;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.HybridRetriever;
import com.agenticrag.app.tool.impl.KnowledgeSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KnowledgeSearchToolTest {
	@Test
	void returnsContextAndSidecar() {
		ObjectMapper objectMapper = new ObjectMapper();
		HybridRetriever hybridRetriever = Mockito.mock(HybridRetriever.class);
		KnowledgeSearchTool tool = new KnowledgeSearchTool(objectMapper, hybridRetriever, new ContextAssembler());

		HashMap<String, Object> metadata = new HashMap<>();
		metadata.put("source", "a.md");
		metadata.put("evidence_id", "e1");
		metadata.put("build_id", "build-1");
		List<TextChunk> chunks = Collections.singletonList(new TextChunk("c1", "d1", "hello", null, metadata));
		Mockito.when(hybridRetriever.retrieve(
			Mockito.eq("hello"),
			Mockito.eq(20),
			Mockito.eq(5),
			Mockito.eq("trace-1"),
			Mockito.eq("kb-1"),
			Mockito.any(RetrievalTraceCollector.class)
		)).thenReturn(chunks);

		ToolResult result = tool.execute(
			objectMapper.createObjectNode().put("query", "hello"),
			new ToolExecutionContext("req-1", "u1", "s1", "trace-1", "kb-1", "call-1")
		).block();

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isSuccess());
		Assertions.assertTrue(result.getOutput().contains("<context>"));
		Assertions.assertNotNull(result.getSidecar());
		Assertions.assertEquals("retrieval_context_v1", result.getSidecar().get("type").asText());
		Assertions.assertEquals(1, result.getSidecar().get("items").size());
	}
}
