package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.api.ApiExceptionHandler;
import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceService;
import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceView;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceRecordType;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceStage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = BenchmarkRetrievalTraceController.class)
@Import(ApiExceptionHandler.class)
class BenchmarkRetrievalTraceControllerTest {
	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private BenchmarkRetrievalTraceService benchmarkRetrievalTraceService;

	@Test
	void listRetrievalTracesRequiresTraceId() {
		webTestClient.get()
			.uri("/api/benchmark/retrieval-traces")
			.exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.message").isEqualTo("traceId is required");
	}

	@Test
	void listRetrievalTracesRejectsInvalidStage() {
		webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/benchmark/retrieval-traces")
				.queryParam("traceId", "trace-1")
				.queryParam("stage", "broken")
				.build())
			.exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.message").isEqualTo("invalid retrieval stage");
	}

	@Test
	void listRetrievalTracesReturnsFilteredViews() {
		BenchmarkRetrievalTraceView view = new BenchmarkRetrievalTraceView(
			1L,
			"chunk",
			"trace-1",
			"call-1",
			"search_knowledge_base",
			"kb-1",
			"build-1",
			"hello",
			"doc-1",
			"chunk-1",
			"e1",
			1,
			0.9d,
			"hello world",
			"a.md",
			"context_output",
			null,
			"2026-03-27T00:00:00Z"
		);
		Mockito.when(benchmarkRetrievalTraceService.listTraceViews(
			"trace-1",
			"call-1",
			"search_knowledge_base",
			"kb-1",
			"build-1",
			RetrievalTraceStage.CONTEXT_OUTPUT,
			RetrievalTraceRecordType.CHUNK
		)).thenReturn(List.of(view));

		webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/benchmark/retrieval-traces")
				.queryParam("traceId", "trace-1")
				.queryParam("toolCallId", "call-1")
				.queryParam("toolName", "search_knowledge_base")
				.queryParam("knowledgeBaseId", "kb-1")
				.queryParam("buildId", "build-1")
				.queryParam("stage", "context_output")
				.queryParam("recordType", "chunk")
				.build())
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].traceId").isEqualTo("trace-1")
			.jsonPath("$[0].chunkId").isEqualTo("chunk-1")
			.jsonPath("$[0].stage").isEqualTo("context_output");
	}
}
