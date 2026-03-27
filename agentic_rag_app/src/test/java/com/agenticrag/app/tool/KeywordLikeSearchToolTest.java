package com.agenticrag.app.tool;

import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceService;
import com.agenticrag.app.rag.context.ContextAssembler;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.PostgresBm25Retriever;
import com.agenticrag.app.rag.retriever.PostgresKeywordLikeRetriever;
import com.agenticrag.app.tool.impl.KeywordLikeSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class KeywordLikeSearchToolTest {
	@Test
	void returnsContextAndSidecar() {
		ObjectMapper objectMapper = new ObjectMapper();
		PostgresKeywordLikeRetriever keywordRetriever = Mockito.mock(PostgresKeywordLikeRetriever.class);
		BenchmarkRetrievalTraceService traceService = Mockito.mock(BenchmarkRetrievalTraceService.class);
		KeywordLikeSearchTool tool = new KeywordLikeSearchTool(
			objectMapper,
			new ContextAssembler(),
			providerOf(keywordRetriever),
			providerOf(null),
			traceService
		);

		HashMap<String, Object> metadata = new HashMap<>();
		metadata.put("source", "a.md");
		metadata.put("evidence_id", "e1");
		metadata.put("build_id", "build-1");
		List<TextChunk> chunks = Collections.singletonList(new TextChunk("c1", "d1", "hello", null, metadata));
		Mockito.when(keywordRetriever.retrieve("keyword", 8, "trace-1", "kb-1")).thenReturn(chunks);

		ToolResult result = tool.execute(
			objectMapper.createObjectNode().put("query", "keyword"),
			new ToolExecutionContext("req-1", "u1", "s1", "trace-1", "kb-1", "call-1")
		).block();

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isSuccess());
		Assertions.assertTrue(result.getOutput().contains("<context>"));
		Assertions.assertNotNull(result.getSidecar());
		Assertions.assertEquals("retrieval_context_v1", result.getSidecar().get("type").asText());
		Assertions.assertEquals(1, result.getSidecar().get("items").size());
		Mockito.verify(traceService).persistCollector(Mockito.any());
	}

	private <T> ObjectProvider<T> providerOf(T value) {
		return new ObjectProvider<T>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getIfUnique() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}
}
