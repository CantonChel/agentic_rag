package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.api.ApiExceptionHandler;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryService;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = BenchmarkTurnExecutionSummaryController.class)
@Import(ApiExceptionHandler.class)
class BenchmarkTurnExecutionSummaryControllerTest {
	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService;

	@Test
	void getTurnSummaryReturnsDetail() {
		Mockito.when(benchmarkTurnExecutionSummaryService.findViewByTurnId("turn-1"))
			.thenReturn(Optional.of(view("turn-1", "session-1", "build-1", "trace-1", "openai", "single_turn", "stop")));

		webTestClient.get()
			.uri("/api/benchmark/turn-summaries/turn-1")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.turnId").isEqualTo("turn-1")
			.jsonPath("$.buildId").isEqualTo("build-1")
			.jsonPath("$.finishReason").isEqualTo("stop");
	}

	@Test
	void getTurnSummaryReturnsNotFoundWhenMissing() {
		Mockito.when(benchmarkTurnExecutionSummaryService.findViewByTurnId("missing")).thenReturn(Optional.empty());

		webTestClient.get()
			.uri("/api/benchmark/turn-summaries/missing")
			.exchange()
			.expectStatus().isNotFound()
			.expectBody()
			.jsonPath("$.message").isEqualTo("turn summary not found");
	}

	@Test
	void listTurnSummariesSupportsFilters() {
		Mockito.when(benchmarkTurnExecutionSummaryService.listViews(
			"session-1",
			"build-1",
			"kb-1",
			"trace-1",
			"openai",
			"single_turn",
			"stop"
		)).thenReturn(List.of(view("turn-1", "session-1", "build-1", "trace-1", "openai", "single_turn", "stop")));

		webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/benchmark/turn-summaries")
				.queryParam("sessionId", "session-1")
				.queryParam("buildId", "build-1")
				.queryParam("knowledgeBaseId", "kb-1")
				.queryParam("traceId", "trace-1")
				.queryParam("provider", "openai")
				.queryParam("evalMode", "single_turn")
				.queryParam("finishReason", "stop")
				.build())
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].turnId").isEqualTo("turn-1")
			.jsonPath("$[0].traceId").isEqualTo("trace-1")
			.jsonPath("$[0].evalMode").isEqualTo("single_turn");
	}

	private BenchmarkTurnExecutionSummaryView view(
		String turnId,
		String sessionId,
		String buildId,
		String traceId,
		String provider,
		String evalMode,
		String finishReason
	) {
		return new BenchmarkTurnExecutionSummaryView(
			turnId,
			sessionId,
			"user-1",
			traceId,
			provider,
			"gpt-test",
			buildId,
			"kb-1",
			"benchmark_build",
			evalMode,
			"default",
			false,
			"question",
			"answer",
			finishReason,
			123L,
			List.of(),
			List.of("trace-1"),
			List.of(),
			null,
			"2026-03-28T00:00:00Z",
			"2026-03-28T00:00:01Z"
		);
	}
}
